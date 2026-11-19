package com.fireplatform.legacyingest.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.legacyingest.ingest.LegacyIngestService;
import com.fireplatform.shared.ApiError;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

/**
 * 镜像入口：原始 body 先写磁盘 spool 并立即返回，解析由后台任务完成。
 * 不使用数据库暂存表，避免与业务表结构/迁移耦合；解析失败进入 dead 目录，原始数据不丢。
 */
@Service
public class LegacyMirrorIntakeService {
    public record Accepted(String id, boolean duplicate) { }
    public record Stats(long received, long processed, long deadLetter, long ignored, long total, OffsetDateTime lastReceivedAt,
                        long receivedLastMinute, long processedLastMinute, long deadLastMinute, long authFailuresLastMinute) { }
    record Stored(String id, String ingress, String originalUri, String platform, String messageType,
                          String body, Map<String, String> headers, String sha256, OffsetDateTime receivedAt, int attempts) { }
    private static final Logger log = LoggerFactory.getLogger(LegacyMirrorIntakeService.class);
    private final ObjectMapper mapper;
    private final LegacyIngestService ingest;
    private final Path queue, done, dead, ignored;
    private final ThreadPoolTaskScheduler scheduler;
    private final ArrayBlockingQueue<String> immediate = new ArrayBlockingQueue<>(1024);
    private final AtomicBoolean wakeScheduled = new AtomicBoolean();
    private final ConcurrentHashMap<Long, LongAdder> receivedBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LongAdder> processedBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LongAdder> deadBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LongAdder> authFailureBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAlert = new ConcurrentHashMap<>();
    private volatile long lastDeadAlert = -1;

    public LegacyMirrorIntakeService(ObjectMapper mapper, LegacyIngestService ingest,
            @Value("${fire.legacy.mirror.spool-dir:${java.io.tmpdir}/fire-mirror-spool}") String spoolDir,
            @Qualifier("legacyMirrorScheduler") ThreadPoolTaskScheduler scheduler) {
        this.mapper = mapper; this.ingest = ingest; this.scheduler = scheduler;
        Path base = Path.of(spoolDir);
        this.queue = base.resolve("queue"); this.done = base.resolve("done"); this.dead = base.resolve("dead"); this.ignored = base.resolve("ignored");
        try {
            Files.createDirectories(queue); Files.createDirectories(done); Files.createDirectories(dead); Files.createDirectories(ignored);
        } catch (Exception e) { throw new IllegalStateException("无法创建镜像 spool 目录: " + base, e); }
    }

    public Accepted accept(String ingress, String originalUri, String platform, String messageType,
                           String body, Map<String, String> headers) {
        try {
            ensureDirs();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String sha = sha256(bytes);
            if (exists(sha)) return new Accepted(sha, true);
            Stored stored = new Stored(sha, ingress, truncate(originalUri, 500), platform, messageType, body,
                    safeHeaders(headers), sha, OffsetDateTime.now(ZoneOffset.UTC), 0);
            Path temp = queue.resolve("." + UUID.randomUUID() + ".tmp");
            Files.writeString(temp, mapper.writeValueAsString(stored), StandardCharsets.UTF_8);
            Files.move(temp, queue.resolve(sha + ".json"), StandardCopyOption.ATOMIC_MOVE);
            bump(receivedBuckets);
            if (platform != null) lastSeen.put(platform, System.currentTimeMillis());
            immediate.offer(sha); scheduleImmediate();
            return new Accepted(sha, false);
        } catch (Exception e) {
            throw new IllegalStateException("镜像 raw 落盘失败", e);
        }
    }

    private void scheduleImmediate() {
        if (!wakeScheduled.compareAndSet(false, true)) return;
        try { scheduler.execute(this::drainImmediate); }
        catch (RuntimeException rejected) { wakeScheduled.set(false); }
    }
    private void drainImmediate() {
        try {
            String id;
            while ((id = immediate.poll()) != null) process(queue.resolve(id + ".json"));
        } finally {
            wakeScheduled.set(false);
            if (!immediate.isEmpty()) scheduleImmediate();
        }
    }

    @Scheduled(initialDelayString = "${fire.legacy.mirror-scan-ms:2000}", fixedDelayString = "${fire.legacy.mirror-scan-ms:2000}", scheduler="legacyMirrorScheduler")
    public synchronized void drain() {
        ensureDirs();
        recoverStale();
        try (Stream<Path> files = Files.list(queue)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparingLong(this::modified))
                .limit(50)
                .forEach(this::process);
        } catch (Exception e) {
            log.warn("legacy mirror spool scan failed: {}", e.toString());
        }
    }

    public Stats stats() {
        ensureDirs();
        long received = count(queue, ".json") + count(queue, ".processing");
        long processed = count(done, ".json");
        long deadCount = count(dead, ".json");
        long ignoredCount = count(ignored, ".json");
        return new Stats(received, processed, deadCount, ignoredCount, received + processed + deadCount + ignoredCount, lastReceived(),
                window(receivedBuckets), window(processedBuckets), window(deadBuckets), window(authFailureBuckets));
    }

    public void recordAuthFailure() { bump(authFailureBuckets); }

    /** 本地告警：死信增长、队列积压、通道断流。只写日志，不依赖旧平台。 */
    @Scheduled(initialDelayString = "${fire.legacy.mirror-alert-ms:60000}", fixedDelayString = "${fire.legacy.mirror-alert-ms:60000}")
    public void checkAlerts() {
        ensureDirs();
        long deadNow = count(dead, ".json");
        if (lastDeadAlert < 0) lastDeadAlert = deadNow;
        else if (deadNow > lastDeadAlert) {
            log.error("legacy mirror DEAD LETTER grew {} -> {} (dir={})", lastDeadAlert, deadNow, dead);
            lastDeadAlert = deadNow;
        }
        long queued = count(queue, ".json") + count(queue, ".processing");
        if (queued > 1000) log.error("legacy mirror queue backlog={} (>1000)", queued);
        long now = System.currentTimeMillis();
        lastSeen.forEach((platform, seen) -> {
            if (now - seen > 600_000 && lastAlert.getOrDefault(platform, 0L) < seen) {
                log.error("legacy mirror channel {} has no data for {}s", platform, (now - seen) / 1000);
                lastAlert.put(platform, seen);
            }
        });
    }

    private static void bump(ConcurrentHashMap<Long, LongAdder> buckets) {
        long minute = System.currentTimeMillis() / 60_000;
        buckets.computeIfAbsent(minute, ignored -> new LongAdder()).increment();
    }

    private static long window(ConcurrentHashMap<Long, LongAdder> buckets) {
        long minute = System.currentTimeMillis() / 60_000;
        long total = 0;
        for (long key = minute - 4; key <= minute; key++) {
            LongAdder adder = buckets.get(key);
            if (adder != null) total += adder.sum();
        }
        buckets.keySet().removeIf(key -> key < minute - 60);
        return total;
    }

    private synchronized void process(Path candidate) {
        Path active = candidate.resolveSibling(candidate.getFileName().toString().replace(".json", ".processing"));
        try {
            Files.move(candidate, active, StandardCopyOption.ATOMIC_MOVE);
            Stored stored = mapper.readValue(Files.readString(active, StandardCharsets.UTF_8), Stored.class);
            ingest.ingest(stored.ingress(), stored.platform(), stored.messageType(), stored.body());
            bump(processedBuckets);
            Files.move(active, done.resolve(stored.sha256() + ".json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException alreadyClaimed) {
            // A scheduled/manual drain may already have consumed this durable candidate.
        } catch (ApiError error) {
            if (error.getMessage() != null && error.getMessage().contains("缺少设备标识")) ignore(active, error.getMessage());
            else if (error.status >= 400 && error.status < 500) moveDead(active, error.getMessage());
            else retry(active, error.getMessage());
        } catch (Exception error) {
            retry(active, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void retry(Path active, String error) {
        try {
            Stored stored = mapper.readValue(Files.readString(active, StandardCharsets.UTF_8), Stored.class);
            int attempts = stored.attempts() + 1;
            if (attempts >= 3) { moveDead(active, error); return; }
            Stored next = new Stored(stored.id(), stored.ingress(), stored.originalUri(), stored.platform(),
                    stored.messageType(), stored.body(), stored.headers(), stored.sha256(), stored.receivedAt(), attempts);
            Files.writeString(active, mapper.writeValueAsString(next), StandardCharsets.UTF_8);
            Files.move(active, queue.resolve(stored.sha256() + ".json"), StandardCopyOption.REPLACE_EXISTING);
            log.warn("legacy mirror retry id={} attempts={} error={}", stored.id(), attempts, error);
        } catch (Exception e) { moveDead(active, "retry failed: " + e.getMessage()); }
    }

    private void ignore(Path active, String reason) {
        try {
            String name = active.getFileName().toString().replace(".processing", ".json");
            Files.move(active, ignored.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(ignored.resolve(name + ".reason"), truncate(reason, 500), StandardCharsets.UTF_8);
            log.info("legacy mirror ignored non-device message {}: {}", name, reason);
        } catch (Exception e) { log.warn("legacy mirror ignore move failed: {}", e.toString()); }
    }

    private void moveDead(Path active, String error) {
        try {
            String name = active.getFileName().toString().replace(".processing", ".json");
            Files.move(active, dead.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(dead.resolve(name + ".error"), truncate(error, 1000), StandardCharsets.UTF_8);
            bump(deadBuckets);
            log.warn("legacy mirror dead letter {}: {}", name, error);
        } catch (Exception e) { log.warn("legacy mirror dead letter move failed: {}", e.toString()); }
    }

    /** 进程重启后把超过 10 分钟仍处于 processing 的文件放回队列。 */
    private void recoverStale() {
        long cutoff = Instant.now().minus(Duration.ofMinutes(10)).toEpochMilli();
        try (Stream<Path> files = Files.list(queue)) {
            files.filter(path -> path.getFileName().toString().endsWith(".processing"))
                .filter(path -> modified(path) < cutoff)
                .forEach(path -> {
                    try { Files.move(path, path.resolveSibling(path.getFileName().toString().replace(".processing", ".json")), StandardCopyOption.REPLACE_EXISTING); }
                    catch (Exception ignored) { }
                });
        } catch (Exception ignored) { }
    }

    private void ensureDirs() {
        try {
            Files.createDirectories(queue); Files.createDirectories(done); Files.createDirectories(dead); Files.createDirectories(ignored);
        } catch (Exception e) { throw new IllegalStateException("无法创建镜像 spool 目录", e); }
    }

    private boolean exists(String sha) {
        return Files.exists(queue.resolve(sha + ".json")) || Files.exists(queue.resolve(sha + ".processing"))
                || Files.exists(done.resolve(sha + ".json")) || Files.exists(dead.resolve(sha + ".json"));
    }

    private long count(Path dir, String suffix) {
        try (Stream<Path> files = Files.list(dir)) { return files.filter(path -> path.getFileName().toString().endsWith(suffix)).count(); }
        catch (Exception e) { return 0; }
    }

    private OffsetDateTime lastReceived() {
        try (Stream<Path> files = Files.list(queue)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                .map(path -> Instant.ofEpochMilli(modified(path)).atOffset(ZoneOffset.UTC))
                .max(Comparator.naturalOrder()).orElse(null);
        } catch (Exception e) { return null; }
    }

    private long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch (Exception e) { return 0; } }
    private Map<String, String> safeHeaders(Map<String, String> headers) {
        Map<String, String> safe = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((key, value) -> {
            if (!key.equalsIgnoreCase("X-Ingest-Token") && !key.equalsIgnoreCase("Authorization")) safe.put(key, value);
        });
        return safe;
    }
    private static String truncate(String value, int max) { return value == null ? null : value.length() <= max ? value : value.substring(0, max); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
