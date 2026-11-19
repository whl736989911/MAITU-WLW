package com.fireplatform.legacyingest.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 旧平台解码器进程边界。
 *
 * <p>子进程只收到完整清空后的环境（Windows 仅补回 SystemRoot），node 可执行文件在构造时解析为绝对路径，
 * 工作目录固定为脚本所在的沙箱目录；Node 以 {@code --permission} 启动（Node 22.13+ / 24 稳定可用），
 * 只授予沙箱目录的读取权限，从而默认禁用子进程、worker、原生 addon、文件写入、inspector 与 WASI。
 * 两点必须如实说明：Node 22/24 的权限模型<b>不限制网络</b>，且允许通过符号链接读取授权目录之外的文件；
 * 真正的隔离边界是脚本内的 QuickJS guest（无宿主绑定），权限模型只是纵深防御。
 */
@Component
public class LegacyCodecRunner {
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final int MAX_RESPONSE_BYTES = 262_144;
    /** legacy_ingest_events.method 为 VARCHAR(64)：超过 64 字符会在落库时失败，故在边界拒绝。 */
    private static final int MAX_EVENT_METHOD_CHARS = 64;
    private static final int MAX_ERROR_MESSAGE_CHARS = 512;
    private static final int MAX_ERROR_MESSAGE_SCAN_CHARS = 16_384;
    private static final int MAX_REDACTION_VALUES = 256;
    private static final int MIN_REDACTABLE_CHARS = 4;
    /** 进程已退出后读取已缓冲输出的上限；不受信任代码的执行截止时间不因此放宽。 */
    private static final long POST_EXIT_DRAIN_MILLIS = 100;
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private final ObjectMapper mapper;
    private final String node;
    private final Path script;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public LegacyCodecRunner(ObjectMapper mapper, @Value("${fire.legacy.codec-node:node}") String node,
                             @Value("${fire.legacy.codec-script:scripts/codec-sandbox/run.mjs}") String script) {
        this(mapper, node, Path.of(script), Duration.ofSeconds(1));
    }

    public LegacyCodecRunner(ObjectMapper mapper, String node, Path script, Duration timeout) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.node = resolveExecutable(Objects.requireNonNull(node, "node"));
        this.script = resolveScript(Objects.requireNonNull(script, "script"));
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** 相对配置按当前工作目录向上查找；找不到时保留绝对路径，由 run() 报 CODEC_SCRIPT_UNAVAILABLE。 */
    private static Path resolveScript(Path configured) {
        if (configured.isAbsolute()) return configured;
        for (Path base = Path.of("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path candidate = base.resolve(configured);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return configured.toAbsolutePath();
    }

    /**
     * 子进程环境会被清空，因此裸名字（如默认的 "node"）必须在父 JVM 的 PATH 中解析成绝对路径，
     * 绝不能依赖子进程自身的 PATH。
     */
    private static String resolveExecutable(String configured) {
        Path direct = Path.of(configured);
        if (direct.isAbsolute()) return direct.toString();
        if (configured.indexOf('/') >= 0 || configured.indexOf('\\') >= 0) return direct.toAbsolutePath().toString();
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) return configured;
        List<String> names = WINDOWS ? List.of("node.exe", "node") : List.of("node");
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            String directory = entry.strip();
            if (directory.length() >= 2 && directory.startsWith("\"") && directory.endsWith("\"")) {
                directory = directory.substring(1, directory.length() - 1);
            }
            if (directory.isEmpty()) directory = ".";
            for (String name : names) {
                Path candidate = Path.of(directory, name);
                if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().toString();
            }
        }
        return configured;
    }

    public CodecResult run(String source, String payloadHex, Map<String, ?> attrs) {
        if (source == null || payloadHex == null) return CodecResult.failure("INVALID_INPUT");
        byte[] request;
        try {
            request = mapper.writeValueAsBytes(Map.of("source", source, "payloadHex", payloadHex,
                "attrs", attrs == null ? Map.of() : attrs));
        } catch (Exception e) {
            return CodecResult.failure("INVALID_INPUT");
        }
        if (request.length > MAX_REQUEST_BYTES) return CodecResult.failure("INPUT_LIMIT");

        Path realScript;
        try {
            if (!Files.isRegularFile(script)) return CodecResult.failure("CODEC_SCRIPT_UNAVAILABLE");
            realScript = script.toRealPath();
        } catch (IOException e) {
            return CodecResult.failure("CODEC_SCRIPT_UNAVAILABLE");
        }
        Path sandbox = realScript.getParent();
        if (sandbox == null || !Files.isDirectory(sandbox)) return CodecResult.failure("CODEC_SCRIPT_UNAVAILABLE");

        Process process = null;
        ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
        long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeout.toMillis()));
        try {
            process = start(sandbox, realScript);
            Process child = process;
            // 写 stdin 也放到虚拟线程：即使宿主脚本不读 stdin，总截止时间仍然有效。
            io.submit(() -> writeRequest(child, request));
            Future<byte[]> stdout = io.submit(() -> readLimited(child.getInputStream(), MAX_RESPONSE_BYTES));
            if (!child.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS)) {
                return CodecResult.failure("CODEC_TIMEOUT");
            }
            if (child.exitValue() != 0) return CodecResult.failure("CODEC_PROCESS_FAILED");
            long drainMillis = Math.max(remainingMillis(deadline), POST_EXIT_DRAIN_MILLIS);
            byte[] output = stdout.get(drainMillis, TimeUnit.MILLISECONDS);
            return parse(output, attrs);
        } catch (TimeoutException e) {
            return CodecResult.failure("CODEC_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CodecResult.failure("CODEC_INTERRUPTED");
        } catch (ExecutionException e) {
            return CodecResult.failure("CODEC_OUTPUT_FAILED");
        } catch (IOException e) {
            return CodecResult.failure("CODEC_PROCESS_FAILED");
        } catch (RuntimeException e) {
            return CodecResult.failure("CODEC_PROCESS_FAILED");
        } finally {
            killTree(process);
            io.shutdownNow();
            try {
                io.awaitTermination(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Process start(Path sandbox, Path realScript) throws IOException {
        List<String> command = new ArrayList<>(4);
        command.add(node);
        command.add("--permission");
        command.add("--allow-fs-read=" + sandbox);
        command.add(realScript.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(sandbox.toFile());
        // stderr 一律丢弃：既避免管道写满死锁，也绝不把宿主侧诊断/源码片段带进错误文本。
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> environment = builder.environment();
        environment.clear();
        if (WINDOWS) {
            // 清空环境后 Windows 进程启动仍需要 SystemRoot（系统路径，非机密）；Linux 下环境完全为空。
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null && !systemRoot.isBlank()) environment.put("SystemRoot", systemRoot);
        }
        return builder.start();
    }

    private static void writeRequest(Process process, byte[] request) {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(request);
            stdin.flush();
        } catch (IOException ignored) {
            // 子进程提前退出/不读 stdin 时由退出码与 stdout 协议决定最终结果。
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return 0;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    /** 强行结束自己创建的子进程及其全部后代进程（超时/中断路径）。 */
    private static void killTree(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants;
        try {
            descendants = process.toHandle().descendants().toList();
        } catch (RuntimeException e) {
            descendants = List.of();
        }
        for (ProcessHandle descendant : descendants) {
            try {
                descendant.destroyForcibly();
            } catch (RuntimeException ignored) {
            }
        }
        process.destroyForcibly();
        try {
            process.waitFor(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 只接受协议规定的 stdout JSON 结构。畸形输出一律失败，绝不合成 “OTHER” 事件。
     * 事件 method 受存储边界约束；guest 提供的错误码必须符合固定模式，错误文本经过有界清洗与 attrs 值脱敏。
     */
    private CodecResult parse(byte[] output, Map<String, ?> attrs) {
        JsonNode root;
        try (JsonParser parser = mapper.getFactory().createParser(output)) {
            root = mapper.readTree(parser);
            if (parser.nextToken() != null) return CodecResult.failure("CODEC_INVALID_OUTPUT");
        } catch (Exception e) {
            return CodecResult.failure("CODEC_INVALID_OUTPUT");
        }
        if (root == null || !root.isObject()) return CodecResult.failure("CODEC_INVALID_OUTPUT");
        JsonNode ok = root.get("ok");
        if (ok == null || !ok.isBoolean()) return CodecResult.failure("CODEC_INVALID_OUTPUT");

        if (!ok.booleanValue()) {
            JsonNode error = root.get("error");
            String code = "CODEC_EXECUTION_FAILED";
            if (error != null && error.isObject()) {
                JsonNode codeNode = error.get("code");
                if (codeNode != null && codeNode.isTextual()) {
                    String candidate = codeNode.asText();
                    if (candidate.length() <= 64 && ERROR_CODE.matcher(candidate).matches()) code = candidate;
                }
            }
            String message = error != null && error.isObject() ? safeMessage(error, attrs) : null;
            return new CodecResult(false, List.of(), code, message == null ? code : message);
        }

        JsonNode array = root.get("events");
        if (array == null || !array.isArray()) return CodecResult.failure("CODEC_INVALID_OUTPUT");
        if (array.isEmpty()) return CodecResult.failure("CODEC_EMPTY_EVENTS");
        List<CodecResult.DecodedEvent> events = new ArrayList<>(Math.min(array.size(), 1024));
        for (JsonNode event : array) {
            if (!event.isObject()) return CodecResult.failure("CODEC_INVALID_OUTPUT");
            JsonNode methodNode = event.get("method");
            if (methodNode == null || !methodNode.isTextual() || !validMethod(methodNode.asText())) {
                return CodecResult.failure("CODEC_INVALID_OUTPUT");
            }
            JsonNode params = event.get("params");
            if (params == null) params = mapper.createObjectNode();
            events.add(new CodecResult.DecodedEvent(methodNode.asText(), params));
        }
        return CodecResult.success(events);
    }

    private static boolean validMethod(String method) {
        if (method.isEmpty() || method.length() > MAX_EVENT_METHOD_CHARS || method.isBlank()) return false;
        for (int index = 0; index < method.length(); index++) {
            if (Character.isISOControl(method.charAt(index))) return false;
        }
        return true;
    }

    /**
     * guest 错误消息是不可信数据：截断扫描长度、去掉控制字符、把 attrs 中的文本值替换为 [redacted]，
     * 最终长度受 MAX_ERROR_MESSAGE_CHARS 限制。这里绝不追加源码、请求体或宿主异常文本。
     */
    private String safeMessage(JsonNode error, Map<String, ?> attrs) {
        JsonNode messageNode = error.get("message");
        if (messageNode == null || !messageNode.isTextual()) return null;
        String text = messageNode.asText();
        if (text.isEmpty()) return null;
        if (text.length() > MAX_ERROR_MESSAGE_SCAN_CHARS) text = text.substring(0, MAX_ERROR_MESSAGE_SCAN_CHARS);
        for (String value : sensitiveValues(attrs)) {
            if (text.contains(value)) text = text.replace(value, "[redacted]");
        }
        StringBuilder cleaned = new StringBuilder(Math.min(text.length(), MAX_ERROR_MESSAGE_CHARS));
        for (int index = 0; index < text.length() && cleaned.length() < MAX_ERROR_MESSAGE_CHARS; index++) {
            char character = text.charAt(index);
            cleaned.append(Character.isISOControl(character) ? ' ' : character);
        }
        return cleaned.isEmpty() ? null : cleaned.toString();
    }

    private List<String> sensitiveValues(Map<String, ?> attrs) {
        if (attrs == null || attrs.isEmpty()) return List.of();
        List<String> values = new ArrayList<>();
        try {
            collectSensitive(mapper.valueToTree(attrs), values);
        } catch (RuntimeException ignored) {
            return List.of();
        }
        values.sort(Comparator.comparingInt(String::length).reversed());
        return values;
    }

    private static void collectSensitive(JsonNode node, List<String> values) {
        if (values.size() >= MAX_REDACTION_VALUES || node == null) return;
        if (node.isTextual()) {
            String text = node.asText();
            if (text.length() >= MIN_REDACTABLE_CHARS) values.add(text);
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext() && values.size() < MAX_REDACTION_VALUES) {
                collectSensitive(fields.next().getValue(), values);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (values.size() >= MAX_REDACTION_VALUES) return;
                collectSensitive(child, values);
            }
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (out.size() + count > limit) throw new IllegalStateException("Output limit exceeded");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
