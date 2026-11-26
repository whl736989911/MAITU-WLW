package com.fireplatform.legacyingest.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "app.roles=all", "app.storage=h2-dev", "app.simulation-enabled=false",
    "spring.datasource.url=jdbc:h2:mem:legacy_master_device_import;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "APP_BOOTSTRAP_PASSWORD=LegacyMasterImportTest-Synthetic"
})
@Transactional
class LegacyMasterDeviceImportTest {
    @Autowired JdbcTemplate jdbc;
    @TempDir Path appendix;

    @Test void matchesExactProductWithoutFallingBackToModelOrPublishingSnapshotAlarms() throws Exception {
        Path legacy = Files.createDirectory(appendix.resolve("legacy"));
        Files.createDirectory(appendix.resolve("codecs"));
        Files.writeString(legacy.resolve("companies.tsv"), "c_id\tc_name\n301\tLegacy test unit\n");
        Files.writeString(legacy.resolve("places.tsv"), "p_id\tp_name\tp_cid\n11\tLegacy test place\t301\n");
        Files.writeString(legacy.resolve("products.tsv"), "p_id\tp_name\tp_model\tp_manufacturer\n"
            + "101\tFirst product\tshared-model\tSame manufacturer\n102\tSecond product\tshared-model\tSame manufacturer\n");
        Files.writeString(legacy.resolve("product_attrs.tsv"), "pa_id\tpa_pid\n");
        Files.writeString(legacy.resolve("devices_tsf.tsv"), "d_id\td_eui\td_name\td_company_id\td_pid\td_model\td_product_id\td_device_id\td_address\td_status\n"
            + "901\t000-a\tFirst device\t301\t11\tshared-model\t101\t000-uplink-a\t\t3\n"
            + "902\t000-b\tSecond device\t301\t11\tshared-model\t102\t000-uplink-b\t\t2\n"
            + "903\t000-c\tUnknown product\t301\t11\tshared-model\t999\t000-uplink-c\t\t2\n"
            + "904\t000-d\tMissing product\t301\t11\tshared-model\t\t000-uplink-d\t\t2\n");
        var importer = new LegacyDictionaryImporter(jdbc, appendix.toString(), "");
        importer.importDictionaries();
        var preview = importer.previewDevices(List.of("301"));
        assertThat(preview.importableNew()).isEqualTo(2);
        assertThat(importer.importDevices("master-device-test", List.of("301")).devices()).isEqualTo(2);
        assertThat(jdbc.query("""
            SELECT d.id, pv.legacy_product_id, d.physical_state, d.integration_state
            FROM devices d JOIN product_versions pv ON pv.id = d.product_version_id
            WHERE d.tenant_id = 'LEGACY-301' ORDER BY d.id
            """, (rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))))
            .containsExactly(List.of("LEGACY-TSF-901", "101", "UNKNOWN", "PENDING_INTEGRATION"),
                             List.of("LEGACY-TSF-902", "102", "UNKNOWN", "PENDING_INTEGRATION"));
        assertThat(jdbc.queryForList("SELECT id FROM devices WHERE tenant_id='LEGACY-301' ORDER BY id", String.class))
            .containsExactly("LEGACY-TSF-901", "LEGACY-TSF-902");
    }
}
