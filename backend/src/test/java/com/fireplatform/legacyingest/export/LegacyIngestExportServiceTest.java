package com.fireplatform.legacyingest.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fireplatform.shared.ApiError;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyIngestExportServiceTest {
    @Test
    void writesFixedHeadersInOrderAndUtf8Bom() {
        byte[] bytes = LegacyIngestExportService.csv(
            List.of("id", "ingress", "vendor_platform", "vendor_device_id", "device_id", "state", "failure_code", "observed_at", "received_at", "duplicate_count", "payload_sha256"), List.of());
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        assertEquals("\"id\",\"ingress\",\"vendor_platform\",\"vendor_device_id\",\"device_id\",\"state\",\"failure_code\",\"observed_at\",\"received_at\",\"duplicate_count\",\"payload_sha256\"\r\n",
            new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
    }

    @Test
    void writesReconcileHeadersInOrder() {
        byte[] bytes = LegacyIngestExportService.csv(List.of("run_id", "kind", "legacy_id", "new_id", "detail", "created_at"), List.of());
        assertEquals("\"run_id\",\"kind\",\"legacy_id\",\"new_id\",\"detail\",\"created_at\"\r\n",
            new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsRowsAboveBound() {
        var service = new LegacyIngestExportService(null, null);
        ApiError error = assertThrows(ApiError.class, () -> service.export(null, null,
            com.fireplatform.shared.ScopeFilter.Selection.all(),
            new LegacyIngestExportService.ExportRequest("MESSAGES", null, null, null, 50_001, "ALL_AUTHORIZED", null, "export-bound-test")));
        assertEquals(400, error.status);
    }

    @Test
    void protectsFormulaCellsAndQuotesCsvContent() {
        byte[] bytes = LegacyIngestExportService.csv(List.of("value", "empty"), java.util.Arrays.asList(new String[]{"=HYPERLINK(\"x\")", "+SUM(A1)"}, new String[]{"line,\"quoted\"\nnext", null}));
        String content = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"'=HYPERLINK(\"\"x\"\")\",\"'+SUM(A1)\""));
        assertTrue(content.contains("\"line,\"\"quoted\"\"\nnext\",\"\""));
    }

    @Test
    void prefixesApostropheBeforeOriginalLeadingWhitespaceAndDoesNotEscapeNegativeNumbers() {
        List<String[]> rows = java.util.Arrays.asList(
            new String[]{"=1"}, new String[]{"  =1"}, new String[]{"\t=1"}, new String[]{"\r=1"},
            new String[]{"+SUM(A1)"}, new String[]{"-1+1"}, new String[]{"@SUM(A1)"}, new String[]{"-5"});
        String content = new String(LegacyIngestExportService.csv(List.of("value"), rows), 3,
            LegacyIngestExportService.csv(List.of("value"), rows).length - 3, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\"value\"\r\n\"'=1\"\r\n\"'  =1\"\r\n\"'\t=1\"\r\n\"'\r=1\"\r\n"));
        assertTrue(content.contains("\"'+SUM(A1)\""));
        assertTrue(content.contains("\"'-1+1\""));
        assertTrue(content.contains("\"'@SUM(A1)\""));
        assertTrue(content.endsWith("\"-5\"\r\n"));
    }
}
