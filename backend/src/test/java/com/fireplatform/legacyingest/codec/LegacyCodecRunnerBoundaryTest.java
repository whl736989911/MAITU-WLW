package com.fireplatform.legacyingest.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 只验证 JVM 侧进程边界本身：环境清空、Node 权限模型生效、输出形状/边界/错误文本清洗。
 * 真实旧平台向量的端到端一致性仍由 CodecSandboxTest / LegacyCodecParityTest 覆盖。
 */
class LegacyCodecRunnerBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir Path sandbox;

    @Test void childEnvironmentHasNoInheritedVariables() throws Exception {
        LegacyCodecRunner runner = runnerWithScript("""
            const names = Object.keys(process.env);
            process.stdout.write(JSON.stringify({ ok: true, events: [{ method: 'env.probe', params: { names } }], error: null }));
            """);
        CodecResult result = runner.run("function rawDataToProtocol(){return [];}", "00", Map.of());
        assertTrue(result.ok(), result::errorMessage);
        JsonNode params = (JsonNode) result.events().getFirst().params();
        List<String> names = new ArrayList<>();
        params.path("names").forEach(node -> names.add(node.asText()));
        assertFalse(names.stream().anyMatch(name -> name.equalsIgnoreCase("PATH")), "PATH leaked: " + names);
        assertFalse(names.contains("JAVA_HOME"), "JAVA_HOME leaked: " + names);
        assertFalse(names.contains("NODE_OPTIONS"), "NODE_OPTIONS leaked: " + names);
        // 唯一允许补回的变量是 Windows 启动所需的 SystemRoot；Linux 下应为空。
        assertTrue(names.stream().allMatch("SystemRoot"::equals), "unexpected inherited env: " + names);
    }

    @Test void childCannotSpawnProcessesOrWriteFiles() throws Exception {
        LegacyCodecRunner runner = runnerWithScript("""
            import { spawn } from 'node:child_process';
            import { writeFileSync } from 'node:fs';
            let childCode = 'allowed';
            let writeCode = 'allowed';
            try { spawn(process.execPath, ['-e', '0']); }
            catch (error) { childCode = error && error.code ? error.code : 'denied'; }
            try { writeFileSync(new URL('./probe-write.txt', import.meta.url), 'x'); }
            catch (error) { writeCode = error && error.code ? error.code : 'denied'; }
            process.stdout.write(JSON.stringify({ ok: true, events: [{ method: 'permission.probe', params: { childCode, writeCode } }], error: null }));
            """);
        CodecResult result = runner.run("function rawDataToProtocol(){return [];}", "00", Map.of());
        assertTrue(result.ok(), result::errorMessage);
        JsonNode params = (JsonNode) result.events().getFirst().params();
        assertEquals("ERR_ACCESS_DENIED", params.path("childCode").asText());
        assertEquals("ERR_ACCESS_DENIED", params.path("writeCode").asText());
    }

    @Test void rejectsEventMethodBeyondStorageBound() throws Exception {
        String method = "m".repeat(65);
        LegacyCodecRunner runner = runnerForJson(
            "{ ok: true, events: [{ method: '" + method + "', params: {} }], error: null }");
        CodecResult result = runner.run("function rawDataToProtocol(){return [];}", "00", Map.of());
        assertFalse(result.ok());
        assertEquals("CODEC_INVALID_OUTPUT", result.errorCode());
        assertTrue(result.events().isEmpty());
    }

    @Test void acceptsEventMethodAtStorageBound() throws Exception {
        String method = "m".repeat(64);
        LegacyCodecRunner runner = runnerForJson(
            "{ ok: true, events: [{ method: '" + method + "', params: {} }], error: null }");
        CodecResult result = runner.run("function rawDataToProtocol(){return [];}", "00", Map.of());
        assertTrue(result.ok(), result::errorMessage);
        assertEquals(method, result.events().getFirst().method());
    }

    @Test void guestErrorTextIsSanitizedAndCodeMustMatchContract() throws Exception {
        LegacyCodecRunner runner = runnerForJson(
            "{ ok: false, events: [], error: { code: 'bad code', message: 'failed for SECRET-TOKEN-42' } }");
        CodecResult result = runner.run("function rawDataToProtocol(){return [];}", "00",
            Map.of("token", "SECRET-TOKEN-42"));
        assertFalse(result.ok());
        assertEquals("CODEC_EXECUTION_FAILED", result.errorCode());
        assertNotNull(result.errorMessage());
        assertFalse(result.errorMessage().contains("SECRET-TOKEN-42"));
        assertTrue(result.errorMessage().contains("[redacted]"));
        assertTrue(result.events().isEmpty());
    }

    @Test void nonBooleanOkIsMalformedOutputNotSuccess() throws Exception {
        LegacyCodecRunner runner = runnerForJson(
            "{ ok: 'true', events: [{ method: 'thing.event.other', params: {} }], error: null }");
        CodecResult result = runner.run("function rawDataToProtocol(){return [];}", "00", Map.of());
        assertFalse(result.ok());
        assertEquals("CODEC_INVALID_OUTPUT", result.errorCode());
        assertTrue(result.events().isEmpty());
    }

    private LegacyCodecRunner runnerForJson(String jsonLiteral) throws IOException {
        return runnerWithScript("process.stdout.write(JSON.stringify(" + jsonLiteral + "));\n");
    }

    private LegacyCodecRunner runnerWithScript(String script) throws IOException {
        Path file = sandbox.resolve("probe.mjs");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        String node = System.getProperty("fire.codec.node", "node");
        return new LegacyCodecRunner(mapper, node, file, Duration.ofSeconds(5));
    }
}
