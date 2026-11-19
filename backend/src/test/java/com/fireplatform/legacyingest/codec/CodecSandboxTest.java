package com.fireplatform.legacyingest.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CodecSandboxTest {
    private static LegacyCodecRunner runner;
    private static Path appendix;

    @BeforeAll static void setup() {
        ObjectMapper mapper = new ObjectMapper();
        runner = new LegacyCodecRunner(mapper, System.getProperty("fire.codec.node", "node"), findScript(), Duration.ofSeconds(1));
        appendix = findAppendix();
    }

    @Test void decodesArchivedTbs110Heartbeat() throws Exception {
        String source = Files.readString(appendix.resolve("codecs/334.js"));
        CodecResult result = runner.run(source, "B2000000", Map.of());
        assertTrue(result.ok(), () -> String.valueOf(result.errorMessage()));
        assertEquals("thing.event.heartbeat", result.events().getFirst().method());
    }

    @Test void decodesArchivedJtyTcn1010Heartbeat() throws Exception {
        String source = Files.readString(appendix.resolve("codecs/323.js"));
        String payload = "{\"messageType\":\"dataReport\",\"serviceId\":2,\"payload\":{" +
            "\"battery_value\":55,\"terminal_type\":\"TBS-N101\",\"heartbeat_time\":\"30\",\"cell_ID\":\"01\"}}";
        CodecResult result = runner.run(source, HexFormat.of().formatHex(payload.getBytes(StandardCharsets.UTF_8)), Map.of());
        assertTrue(result.ok(), () -> String.valueOf(result.errorMessage()));
        assertEquals("thing.event.heartbeat", result.events().getFirst().method());
    }

    @Test void invalidHexIsReturnedAsFailure() {
        CodecResult result = runner.run("function rawDataToProtocol(x) { return []; }", "not-hex", Map.of());
        assertFalse(result.ok());
        assertNotNull(result.errorCode());
    }

    @Test void infiniteDecoderIsKilledWithoutAffectingJvm() {
        CodecResult timedOut = runner.run("function rawDataToProtocol(x) { while (true) {} }", "00", Map.of());
        assertFalse(timedOut.ok());
        assertEquals("CODEC_TIMEOUT", timedOut.errorCode());
        CodecResult after = runner.run("function rawDataToProtocol(x) { return JSON.stringify([{method:'thing.event.other',params:[{}]}]); }", "00", Map.of());
        assertTrue(after.ok());
    }

    private static Path findScript() {
        Path current = Path.of("").toAbsolutePath();
        for (Path path = current; path != null; path = path.getParent()) {
            Path candidate = path.resolve("scripts/codec-sandbox/run.mjs");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return Path.of("scripts/codec-sandbox/run.mjs");
    }

    private static Path findAppendix() {
        String configured = System.getenv("LEGACY_APPENDIX");
        if (configured != null && Files.isDirectory(Path.of(configured))) return Path.of(configured);
        for (String candidate : new String[]{"D:/烟感/_incoming/omp-docs/appendix", "/mnt/d/烟感/_incoming/omp-docs/appendix"}) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) return path;
        }
        throw new IllegalStateException("Legacy appendix not found; set LEGACY_APPENDIX");
    }
}
