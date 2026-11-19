package com.fireplatform.legacyingest.codec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 真实 pa_code 与真实报文样本的离线一致性：本仓 Node 沙箱必须逐字段复现旧平台解析结果。
 * fixtures 来自旧平台第二轮导出（B-pa_code / B-samples），设备和 SIM 标识使用合成值。
 */
class LegacyCodecParityTest {
    private static final Path FIXTURES = Path.of("src/test/resources/legacy-codecs");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void nodeSandboxReproducesLegacyDecoderOutputForAllSamples() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(FIXTURES.resolve("samples")), "缺少旧平台解码样本 fixtures");
        String node = System.getenv("FIRE_NODE") == null ? "node" : System.getenv("FIRE_NODE");
        try {
            new ProcessBuilder(node, "--version").redirectErrorStream(true).start().waitFor();
        } catch (Exception unavailable) {
            Assumptions.assumeTrue(false, "本机没有 node，跳过真实解码一致性检查");
        }
        LegacyCodecRunner runner = new LegacyCodecRunner(mapper, node, Path.of("scripts/codec-sandbox/run.mjs"), Duration.ofSeconds(10));
        var samples = Files.list(FIXTURES.resolve("samples")).filter(path -> path.toString().endsWith(".json")).sorted().toList();
        assertThat(samples).hasSizeGreaterThanOrEqualTo(5);
        int checked = 0;
        for (Path sample : samples) {
            String product = sample.getFileName().toString().replace(".json", "");
            Path decoder = FIXTURES.resolve("decoders").resolve(product + ".js");
            assertThat(decoder).as("decoder for " + product).isRegularFile();
            JsonNode fixture = mapper.readTree(sample.toFile());
            CodecResult result = runner.run(Files.readString(decoder), fixture.path("payload_hex").asText(), Map.of());
            assertThat(result.ok()).as(product + " decode: " + result.errorCode() + " " + result.errorMessage()).isTrue();
            JsonNode actual = mapper.valueToTree(toNode(result.events()));
            // 我们的 DecodedEvent 只承载 method 与 params；旧平台的 version 恒为 "2.0"，不进入事件模型。
            JsonNode expected = fixture.get("expected").deepCopy();
            expected.forEach(event -> ((com.fasterxml.jackson.databind.node.ObjectNode) event).remove("version"));
            assertSemanticallyEqual(expected, actual, product);
            checked++;
        }
        assertThat(checked).isEqualTo(samples.size());
    }

    private List<Map<String, Object>> toNode(List<CodecResult.DecodedEvent> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CodecResult.DecodedEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", event.method());
            row.put("params", event.params());
            out.add(row);
        }
        return out;
    }

    /** 忽略对象键顺序、允许数值 1e-6 误差（旧平台会把 float 四舍五入）。 */
    private void assertSemanticallyEqual(JsonNode expected, JsonNode actual, String product) {
        if (expected.isNumber() && actual.isNumber()) {
            assertThat(new BigDecimal(actual.asText()).compareTo(new BigDecimal(expected.asText())))
                .as(product + " numeric " + actual + " vs " + expected).isZero();
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            Set<String> left = new TreeSet<>(), right = new TreeSet<>();
            expected.fieldNames().forEachRemaining(left::add);
            actual.fieldNames().forEachRemaining(right::add);
            assertThat(right).as(product + " keys").isEqualTo(left);
            for (String key : left) assertSemanticallyEqual(expected.get(key), actual.get(key), product + "/" + key);
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            assertThat(actual.size()).as(product + " array size").isEqualTo(expected.size());
            for (int index = 0; index < expected.size(); index++) assertSemanticallyEqual(expected.get(index), actual.get(index), product + "[" + index + "]");
            return;
        }
        assertThat(actual).as(product).isEqualTo(expected);
    }
}
