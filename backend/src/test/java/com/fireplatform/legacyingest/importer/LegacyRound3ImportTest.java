package com.fireplatform.legacyingest.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** round3 真实只读导出：36 单位 / 296 场所 / 85 产品 / 3,053 属性 / 1,998 设备 / 63 解码器。 */
@SpringBootTest(properties={"app.roles=all","app.simulation-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:legacy_round3_import;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class LegacyRound3ImportTest {
    private static final Path APPENDIX = round3Appendix();
    private static final String INITIAL_PASSWORD = "Legacy-round3-" + UUID.randomUUID();
    @Autowired LegacyDictionaryImporter importer;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource static void appendix(DynamicPropertyRegistry registry) {
        registry.add("LEGACY_APPENDIX", APPENDIX::toString);
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> INITIAL_PASSWORD);
        registry.add("APP_DEMO_CUSTOMER_PASSWORD", () -> INITIAL_PASSWORD);
        registry.add("APP_DEMO_CUSTOMER_B_PASSWORD", () -> INITIAL_PASSWORD);
    }

    @BeforeAll static void requireArchive() {
        Assumptions.assumeTrue(Files.isDirectory(APPENDIX.resolve("legacy")) && Files.isDirectory(APPENDIX.resolve("codecs")),
                "round3 转换后的 appendix 不存在，跳过");
    }

    @BeforeEach void clearImportedRows() {
        jdbc.update("DELETE FROM user_tenant_grants WHERE tenant_id LIKE 'LEGACY-%'");
        jdbc.update("DELETE FROM user_device_scopes WHERE tenant_id LIKE 'LEGACY-%'");
        jdbc.update("DELETE FROM device_aliases WHERE device_id LIKE 'LEGACY-TSF-%'");
        jdbc.update("DELETE FROM devices WHERE id LIKE 'LEGACY-TSF-%'");
        jdbc.update("DELETE FROM product_version_attributes WHERE product_version_id IN (SELECT id FROM product_versions WHERE legacy_product_id IS NOT NULL)");
        jdbc.update("DELETE FROM legacy_product_decoders");
        jdbc.update("DELETE FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:%'");
        jdbc.update("DELETE FROM product_versions WHERE legacy_product_id IS NOT NULL");
        jdbc.update("DELETE FROM products WHERE id LIKE 'LEGACY-PRODUCT-%'");
        jdbc.update("DELETE FROM places WHERE id LIKE 'LEGACY-PLACE-%'");
        jdbc.update("DELETE FROM tenants WHERE id LIKE 'LEGACY-%'");
    }

    @Test void importsRound3DictionariesAndThreeCompanyLedger() {
        var stats = importer.importDictionaries();
        assertThat(stats.tenants()).isEqualTo(36);
        // 296 个场所中 12 个的 p_cid=1（该单位不在 org.csv 的单位列表里），导入 284 个并保留差异记录。
        assertThat(stats.places()).isEqualTo(284);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:PLACE_TENANT_MISSING:%'", Integer.class)).isEqualTo(12);
        // 3,053 条属性中 463 条属于 12 个未出现在产品导出里的 p_id；其余 2,590 条可挂到版本上。
        assertThat(stats.attributes()).isEqualTo(2_590);
        // 85 行产品中：TBS-N151 断行损坏 2 行、型号为空 2 行，剩余 81 个版本。
        assertThat(stats.versions()).isEqualTo(81);
        // 63 个解码器里有 2 个（487/490）对应型号为空的损坏产品，没有版本可挂，实际导入 61 个。
        assertThat(stats.decoders()).isEqualTo(61);

        String actor = jdbc.queryForObject("SELECT id FROM app_users WHERE username='admin'", String.class);
        var devices = importer.importDevices(UUID.randomUUID().toString(), List.of(), actor);
        // 1,998 行里 1 行标注单位 296，却引用单位 327 的场所 560；
        // 跨租户场所引用被拒绝并记录，不静默改租户。
        assertThat(devices.devices()).isEqualTo(1_997);
        assertThat(devices.aliases()).isEqualTo(3_994);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:PILOT_PLACE_MISSING:%'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id LIKE 'LEGACY-TSF-%' AND tenant_id IN ('LEGACY-294','LEGACY-296','LEGACY-297')", Integer.class)).isEqualTo(1_997);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id LIKE 'LEGACY-TSF-%' AND physical_state='FIRE'", Integer.class)).isEqualTo(27);
        // 幂等：重复导入不新增设备或别名
        var repeated = importer.importDevices(UUID.randomUUID().toString(), List.of());
        assertThat(repeated.devices()).isZero();
        assertThat(repeated.aliases()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id LIKE 'LEGACY-TSF-%'", Integer.class)).isEqualTo(1_997);
        // 导入完成后执行者应能直接看到这三个旧单位的数据。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id IN ('LEGACY-294','LEGACY-296','LEGACY-297')", Integer.class, actor)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scopes WHERE user_id=? AND tenant_id IN ('LEGACY-294','LEGACY-296','LEGACY-297')", Integer.class, actor)).isEqualTo(3);
    }

    @Test void importsOnlyRequestedCompanies() {
        importer.importDictionaries();
        String actor = jdbc.queryForObject("SELECT id FROM app_users WHERE username='admin'", String.class);
        var stats = importer.importDevices(UUID.randomUUID().toString(), List.of("294"), actor);
        assertThat(stats.devices()).isEqualTo(666);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id LIKE 'LEGACY-TSF-%' AND tenant_id='LEGACY-294'", Integer.class)).isEqualTo(666);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id LIKE 'LEGACY-TSF-%' AND tenant_id<>'LEGACY-294'", Integer.class)).isZero();
    }

    private static Path round3Appendix() {
        for (String candidate : List.of("../artifacts/legacy-round3/appendix", "artifacts/legacy-round3/appendix",
                "/mnt/d/烟感/fire-platform/artifacts/legacy-round3/appendix")) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path.resolve("legacy"))) return path;
        }
        return Path.of("../artifacts/legacy-round3/appendix");
    }
}
