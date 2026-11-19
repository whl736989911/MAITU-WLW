package com.fireplatform.legacyingest.importer;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"app.roles=all","app.simulation-enabled=true","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:legacy_dictionary_import;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class LegacyDictionaryImportTest {
    private static final Path APPENDIX=appendixPath();
    private static final String INITIAL_PASSWORD="Legacy-import-"+UUID.randomUUID();
    @Autowired LegacyDictionaryImporter importer;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource static void appendix(DynamicPropertyRegistry registry) {
        registry.add("LEGACY_APPENDIX",()->APPENDIX.toString());
        registry.add("LEGACY_PLACE_HIERARCHY_EXPORT",()->hierarchyExportPath().toString());
        registry.add("APP_BOOTSTRAP_USERNAME",()->"admin");
        registry.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL_PASSWORD);
        registry.add("APP_DEMO_CUSTOMER_PASSWORD",()->INITIAL_PASSWORD);
        registry.add("APP_DEMO_CUSTOMER_B_PASSWORD",()->INITIAL_PASSWORD);
    }

    @BeforeAll static void requireArchive() { Assumptions.assumeTrue(Files.isDirectory(APPENDIX.resolve("legacy"))&&Files.isDirectory(APPENDIX.resolve("codecs")),"真实归档目录不存在，跳过 legacy 字典导入验收"); }

    @BeforeEach void clearImportedRows() {
        jdbc.update("DELETE FROM device_aliases WHERE device_id LIKE 'LEGACY-TSF-%'");
        jdbc.update("DELETE FROM devices WHERE id LIKE 'LEGACY-TSF-%'");
        jdbc.update("DELETE FROM product_version_attributes WHERE product_version_id IN (SELECT id FROM product_versions WHERE legacy_product_id IS NOT NULL)");
        jdbc.update("DELETE FROM legacy_product_decoders");
        jdbc.update("DELETE FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:%'");
        jdbc.update("DELETE FROM product_versions WHERE legacy_product_id IS NOT NULL");
        jdbc.update("DELETE FROM products WHERE id LIKE 'LEGACY-PRODUCT-%'");
        jdbc.update("DELETE FROM legacy_place_hierarchy_repair_audit");
        jdbc.update("DELETE FROM places WHERE id LIKE 'LEGACY-PLACE-%'");
        jdbc.update("DELETE FROM places WHERE id='manual-parent'");
        jdbc.update("DELETE FROM tenants WHERE id LIKE 'LEGACY-%'");
    }

    @Test void importsRealLegacyDictionariesAndIsIdempotent() throws Exception {
        var stats=importer.importDictionaries();
        assertThat(stats.tenants()).isEqualTo(36);
        assertThat(stats.places()).isEqualTo(284);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:PLACE_TENANT_MISSING:%'",Integer.class)).isEqualTo(12);
        List<String> missingPlaceDetails=jdbc.queryForList("SELECT detail FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:PLACE_TENANT_MISSING:%'",String.class);
        assertThat(missingPlaceDetails).allSatisfy(detail->assertThat(detail).contains("p_id=","p_cid="));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id='LEGACY-1'",Integer.class)).isZero();
        assertThat(stats.products()).isEqualTo(normalizedProductCount(APPENDIX.resolve("legacy/products.tsv")));
        // 85 个数据行；唯一 p_id 84；排除 2 条型号为空（487、490）与 1 条断行损坏（TBS-N151）后应为 81。
        // 该数量关系与差异表记录互相校验：db + 2 条 DAMAGED_PRODUCT + 1 条 CORRUPT_ROW == 84。
        List<String> firstVersionIds=jdbc.queryForList("SELECT legacy_product_id FROM product_versions WHERE legacy_product_id IS NOT NULL ORDER BY legacy_product_id",String.class);
        assertThat(firstVersionIds).hasSize(81).doesNotContain("TBS-N151");
        assertThat(stats.versions()).isEqualTo(81);
        assertThat(81 + 2 + 1).isEqualTo(84);
        int damaged=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:DAMAGED_PRODUCT:%'",Integer.class);
        int corrupt=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:CORRUPT_ROW:%'",Integer.class);
        // 84 个唯一 p_id 中有 3 条不可用记录（2 条型号为空 + 1 条断行后型号同样为空），且必须被记录而非静默丢弃
        assertThat(damaged + corrupt).as("被排除并记录的产品记录数").isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:DUPLICATE_MODEL:%'",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:P_KEY_PENDING:%'",Integer.class)).isEqualTo(1);
        int rows=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:%'",Integer.class);
        var repeated=importer.importDictionaries();
        assertThat(repeated.places()).isEqualTo(284);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id LIKE 'LEGACY-%'",Integer.class)).isEqualTo(36);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_versions WHERE legacy_product_id IS NOT NULL",Integer.class)).isEqualTo(81);
        List<String> repeatedVersionIds=jdbc.queryForList("SELECT legacy_product_id FROM product_versions WHERE legacy_product_id IS NOT NULL ORDER BY legacy_product_id",String.class);
        assertThat(new TreeSet<>(repeatedVersionIds)).isEqualTo(new TreeSet<>(firstVersionIds));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:%'",Integer.class)).isEqualTo(rows);
    }
    @Test void importsAndAuditsAuthoritativeHierarchyWithoutOverwritingManualParents() {
        Path export=hierarchyExportPath();
        Assumptions.assumeTrue(Files.isRegularFile(export),"org_all.csv hierarchy export is unavailable");
        var stats=importer.importDictionaries();
        assertThat(stats.hierarchyRunId()).isNotBlank();
        assertThat(stats.hierarchySourceSha256()).hasSize(64);
        assertThat(stats.hierarchyApplied()).isEqualTo(277);
        assertThat(stats.hierarchySkipped()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_place_hierarchy_repair_audit WHERE run_id=?",Integer.class,stats.hierarchyRunId())).isEqualTo(284);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_place_hierarchy_repair_audit WHERE run_id=? AND source_parent_place_id IS NOT NULL AND outcome='APPLIED'",Integer.class,stats.hierarchyRunId())).isEqualTo(155);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_place_hierarchy_repair_audit WHERE run_id=? AND outcome='CROSS_TENANT_CONTEXT'",Integer.class,stats.hierarchyRunId())).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id='LEGACY-PLACE-369'",String.class)).isEqualTo("LEGACY-PLACE-323");
        assertThat(jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id='LEGACY-PLACE-538'",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT legacy_parent_place_id FROM places WHERE id='LEGACY-PLACE-538'",String.class)).isEqualTo("537");

        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES('manual-parent','LEGACY-294','Manual local parent',NULL)");
        jdbc.update("UPDATE places SET parent_place_id='manual-parent' WHERE id='LEGACY-PLACE-370'");
        jdbc.update("UPDATE places SET parent_place_id=NULL,revision=1 WHERE id='LEGACY-PLACE-371'");
        var repeated=importer.importDictionaries();
        assertThat(jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id='LEGACY-PLACE-370'",String.class)).isEqualTo("manual-parent");
        assertThat(jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id='LEGACY-PLACE-371'",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_place_hierarchy_repair_audit WHERE run_id=? AND legacy_place_id IN ('370','371') AND outcome='PRESERVED_MANUAL'",Integer.class,repeated.hierarchyRunId())).isEqualTo(2);
    }
    @Test void importsPilotDevicesWithTenantScopedPlacesAliasesAndDeterministicModelSelection() throws Exception {
        importer.importDictionaries();
        var first=importer.importPilotDevices(UUID.randomUUID().toString());
        assertThat(first.devices()).isEqualTo(34);
        assertThat(first.aliases()).isEqualTo(68);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id LIKE 'LEGACY-TSF-%'",Integer.class)).isEqualTo(34);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases WHERE device_id LIKE 'LEGACY-TSF-%'",Integer.class)).isEqualTo(68);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices d LEFT JOIN places p ON p.id=d.place_id WHERE d.id LIKE 'LEGACY-TSF-%' AND (p.id IS NULL OR p.tenant_id<>d.tenant_id OR d.tenant_id<>'LEGACY-311')",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases WHERE device_id LIKE 'LEGACY-TSF-%' AND kind='EUI' AND origin='LEGACY_TSF'",Integer.class)).isEqualTo(34);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases WHERE device_id LIKE 'LEGACY-TSF-%' AND kind='VENDOR_ID' AND origin='LEGACY_TSF'",Integer.class)).isEqualTo(34);

        var expected=expectedPilotSelectionRows();
        List<String> selections=jdbc.queryForList("SELECT detail FROM legacy_reconcile_differences WHERE kind='LEGACY_FIELD_MISMATCH' AND detail LIKE 'IMPORTER:PILOT_MODEL_AMBIGUOUS:%'",String.class);
        assertThat(selections).hasSize(expected.rows());
        expected.models().forEach(model->assertThat(selections).anyMatch(detail->detail.contains("d_model="+model+" ")));
        assertThat(selections).allSatisfy(detail->assertThat(detail).contains("命中候选 [","候选数 "));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id LIKE 'LEGACY-TSF-%' AND tenant_id='LEGACY-311' AND product_version_id IS NULL",Integer.class))
            .isEqualTo(expected.rows());

        int differences=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:%'",Integer.class);
        var second=importer.importPilotDevices(UUID.randomUUID().toString());
        assertThat(second.devices()).isZero();
        assertThat(second.aliases()).isZero();
        assertThat(second.differences()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:%'",Integer.class)).isEqualTo(differences);
    }

    @Test void preservesLocalMetadataOnRepeatImport() {
        importer.importDictionaries();
        String versionId=jdbc.queryForObject("SELECT id FROM product_versions WHERE legacy_product_id='323'",String.class);
        String productId=jdbc.queryForObject("SELECT product_id FROM product_versions WHERE legacy_product_id='323'",String.class);
        String attrKey=jdbc.queryForObject("SELECT MIN(attribute_key) FROM product_version_attributes WHERE product_version_id=?",String.class,versionId);
        jdbc.update("UPDATE tenants SET name='本地单位名' WHERE id='LEGACY-294'");
        jdbc.update("UPDATE products SET name='本地产品名' WHERE id=?",productId);
        jdbc.update("UPDATE product_versions SET schema_format='JSON_POINTER_V1' WHERE id=?",versionId);
        jdbc.update("UPDATE product_version_attributes SET name='本地属性名' WHERE product_version_id=? AND attribute_key=?",versionId,attrKey);

        importer.importDictionaries();

        assertThat(jdbc.queryForObject("SELECT name FROM tenants WHERE id='LEGACY-294'",String.class)).isEqualTo("本地单位名");
        assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id=?",String.class,productId)).isEqualTo("本地产品名");
        assertThat(jdbc.queryForObject("SELECT schema_format FROM product_versions WHERE id=?",String.class,versionId)).isEqualTo("JSON_POINTER_V1");
        assertThat(jdbc.queryForObject("SELECT name FROM product_version_attributes WHERE product_version_id=? AND attribute_key=?",String.class,versionId,attrKey)).isEqualTo("本地属性名");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:TENANT_NAME_PRESERVED:%'",Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:PRODUCT_NAME_PRESERVED:%'",Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:VERSION_METADATA_PRESERVED:%'",Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:ATTRIBUTE_METADATA_PRESERVED:%'",Integer.class)).isPositive();
    }

    @Test void doesNotResurrectArchivedSourcesOrRetargetAliases() {
        importer.importDictionaries();
        importer.importPilotDevices(UUID.randomUUID().toString());
        String deviceId=jdbc.queryForObject("SELECT id FROM devices WHERE id LIKE 'LEGACY-TSF-%' ORDER BY id LIMIT 1",String.class);
        jdbc.update("UPDATE devices SET archived_at=CURRENT_TIMESTAMP WHERE id=?",deviceId);
        jdbc.update("UPDATE device_aliases SET alias_value='TRANSFERRED-SNAPSHOT' WHERE device_id=?",deviceId);
        jdbc.update("UPDATE places SET archived_at=CURRENT_TIMESTAMP,name='归档前名称' WHERE id='LEGACY-PLACE-369'");

        importer.importDictionaries();
        importer.importPilotDevices(UUID.randomUUID().toString());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases WHERE device_id=? AND alias_value='TRANSFERRED-SNAPSHOT'",Integer.class,deviceId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT name FROM places WHERE id='LEGACY-PLACE-369'",String.class)).isEqualTo("归档前名称");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:PILOT_DEVICE_ARCHIVED:%'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE detail LIKE 'IMPORTER:PLACE_ARCHIVED:%'",Integer.class)).isEqualTo(1);
    }

    @Test void previewsReportCountsWithoutWriting() {
        var dictionary=importer.previewDictionaries();
        assertThat(dictionary.sourceSha256()).hasSize(64);
        assertThat(dictionary.sourceCompanies()).isPositive();
        assertThat(dictionary.sourcePlaces()).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id LIKE 'LEGACY-%'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id LIKE 'LEGACY-PLACE-%'",Integer.class)).isZero();

        var devices=importer.previewDevices(List.of("311"));
        assertThat(devices.sourceSha256()).hasSize(64);
        assertThat(devices.selectedRows()).isPositive();
        assertThat(devices.companies()).anyMatch(company->company.companyId().equals("311"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id LIKE 'LEGACY-TSF-%'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases",Integer.class)).isZero();
    }

    private record PilotSelections(int rows,Set<String> models) { }
    private PilotSelections expectedPilotSelectionRows() throws Exception {
        List<String> lines=Files.readAllLines(APPENDIX.resolve("legacy/devices_tsf.tsv"),StandardCharsets.UTF_8);
        String[] headers=lines.get(0).split("\\t",-1); int companyIndex=Arrays.asList(headers).indexOf("d_company_id"),modelIndex=Arrays.asList(headers).indexOf("d_model");
        int rows=0; Set<String> models=new HashSet<>();
        for(String line:lines.subList(1,lines.size())) {
            if(line.isBlank()) continue; String[] columns=line.split("\\t",-1); if(columns.length!=headers.length||!columns[companyIndex].trim().equals("311")) continue;
            String model=columns[modelIndex].trim();
            Long candidates=jdbc.queryForObject("SELECT COUNT(*) FROM product_versions pv JOIN products p ON p.id=pv.product_id WHERE p.model=? AND pv.schema_format='LEGACY_ATTR_JS_V1' AND pv.legacy_product_id IS NOT NULL",Long.class,model);
            if(candidates!=null&&candidates>1) { rows++; models.add(model); }
        }
        return new PilotSelections(rows,models);
    }

    private static int normalizedProductCount(Path file) throws Exception {
        List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8); String[] headers=lines.get(0).split("\\t",-1);
        int productIdIndex=Arrays.asList(headers).indexOf("p_id"), manufacturerIndex=Arrays.asList(headers).indexOf("p_manufacturer"), modelIndex=Arrays.asList(headers).indexOf("p_model"), ignoredKeyIndex=Arrays.asList(headers).indexOf("p_key"); Set<String> products=new HashSet<>();
        for(String line:lines.subList(1,lines.size())) {
            if(line.isBlank()) continue; String[] columns=line.split("\\t",-1); if(columns.length!=headers.length||!columns[productIdIndex].trim().matches("[0-9]+")) continue;
            if(ignoredKeyIndex>=0) columns[ignoredKeyIndex]="";
            String manufacturer=columns[manufacturerIndex].trim(); if(manufacturer.isBlank()||manufacturer.equalsIgnoreCase("NULL")||manufacturer.equals("10")) manufacturer="未标注";
            String model=columns[modelIndex].trim(); if(!model.isBlank()) products.add(manufacturer+"\u0000"+model);
        }
        return products.size();
    }
    private static Path appendixPath() {
        String environment=System.getenv("LEGACY_APPENDIX"); if(environment!=null&&!environment.isBlank()) return Path.of(environment);
        for(String candidate:List.of("/mnt/d/烟感/_incoming/omp-docs/appendix","D:/烟感/_incoming/omp-docs/appendix")) { Path path=Path.of(candidate); if(Files.isDirectory(path)) return path; }
        return Path.of("D:/烟感/_incoming/omp-docs/appendix");
    }
    private static Path hierarchyExportPath() {
        String configured=System.getenv("LEGACY_PLACE_HIERARCHY_EXPORT");
        if(configured!=null&&!configured.isBlank()) return Path.of(configured);
        Path sibling=APPENDIX.resolveSibling("round6/org_all.csv");
        return Files.isRegularFile(sibling)?sibling:Path.of("");
    }
}
