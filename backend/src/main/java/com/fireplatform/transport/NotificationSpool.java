package com.fireplatform.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Local real-notification markers and cursor. The notification domain sees no filesystem paths. */
@Component
public final class NotificationSpool {
    private static final Logger log = LoggerFactory.getLogger(NotificationSpool.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path base;
    private final Path done;
    private final Path dead;
    private final Path cursorFile;

    public NotificationSpool(@Value("${fire.notification.real-spool-dir:${java.io.tmpdir}/fire-real-notifications}") String spoolDir) {
        base = Path.of(spoolDir);
        done = base.resolve("done");
        dead = base.resolve("dead");
        cursorFile = base.resolve("cursor");
        try {
            Files.createDirectories(done);
            Files.createDirectories(dead);
        } catch (Exception error) {
            throw new IllegalStateException("无法创建真实通知目录", error);
        }
    }

    public boolean isDone(String occurrenceKey, String alarmId, String contactId, String channel) {
        return Files.exists(done.resolve(markerKey(occurrenceKey, alarmId, contactId, channel) + ".json"));
    }

    public boolean isDead(String occurrenceKey, String alarmId, String contactId, String channel) {
        return Files.exists(dead.resolve(markerKey(occurrenceKey, alarmId, contactId, channel) + ".json"));
    }

    public void markDone(String occurrenceKey, String alarmId, String contactId, String channel, String status, String providerId) {
        writeMarker(done, occurrenceKey, alarmId, contactId, channel, status, providerId);
    }

    public void markDead(String occurrenceKey, String alarmId, String contactId, String channel, String status, String providerId) {
        writeMarker(dead, occurrenceKey, alarmId, contactId, channel, status, providerId);
    }

    public OffsetDateTime cursor() {
        try {
            if (!Files.exists(cursorFile)) return null;
            return Instant.ofEpochMilli(Long.parseLong(Files.readString(cursorFile).trim())).atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void writeCursor(OffsetDateTime value) {
        try {
            Files.createDirectories(base);
            Files.writeString(cursorFile, String.valueOf(value.toInstant().toEpochMilli()), StandardCharsets.UTF_8);
        } catch (Exception error) {
            log.warn("real notification cursor write failed: {}", error.getClass().getSimpleName());
        }
    }

    public long doneCount() { return count(done); }
    public long deadCount() { return count(dead); }

    private void writeMarker(Path directory, String occurrenceKey, String alarmId, String contactId, String channel, String status, String providerId) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("alarm_id", alarmId);
            value.put("contact_id", contactId);
            value.put("channel", channel);
            value.put("status", status);
            value.put("provider_id", providerId);
            value.put("recorded_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
            Files.writeString(directory.resolve(markerKey(occurrenceKey, alarmId, contactId, channel) + ".json"), mapper.writeValueAsString(value), StandardCharsets.UTF_8);
        } catch (Exception error) {
            log.warn("real notification marker write failed: {}", error.getClass().getSimpleName());
        }
    }

    private static String markerKey(String occurrenceKey, String alarmId, String contactId, String channel) {
        return occurrenceKey.equals("INITIAL")
                ? alarmId + "__" + contactId + "__" + channel
                : alarmId + "__" + occurrenceKey.replace(':', '_') + "__" + contactId + "__" + channel;
    }

    private static long count(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".json")).count();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
