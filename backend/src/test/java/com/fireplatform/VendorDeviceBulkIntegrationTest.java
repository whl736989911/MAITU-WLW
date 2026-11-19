package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireplatform.hikvision.HikvisionClient;
import com.fireplatform.hikvision.HikvisionIngestion;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties={"app.roles=all","fire.hikvision.enabled=false","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:vendor-device-bulk;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class VendorDeviceBulkIntegrationTest {
    private static final String BASE="/api/management/vendor-devices/import";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry){IngestionIntegrationTest.environment(registry);}
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fireplatform.bootstrap.Bootstrap bootstrap;
    @Autowired com.fireplatform.product.JsonPointerParser parser;
    @Autowired HikvisionIngestion ingestion;
    IngestionIntegrationTest fixture;
    String profile,foreignPlaceId;

    @BeforeEach void setup() throws Exception {
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=NULL");
        fixture=new IngestionIntegrationTest();fixture.mvc=mvc;fixture.json=json;fixture.jdbc=jdbc;fixture.bootstrap=bootstrap;fixture.parser=parser;fixture.reset();
        foreignPlaceId=fixture.body(fixture.call(HttpMethod.POST,"/api/management/places",fixture.root,"tenant-b",UUID.randomUUID().toString(),Map.of("name","Foreign vendor bulk fixture")).andExpect(status().isOk())).get("id").asText();
        profile="legacy-profile-"+UUID.randomUUID();
        String product="legacy-product-"+UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,name,manufacturer,model,description,state) VALUES(?,?,?,?,?,'ACTIVE')",product,"Imported legacy model","Fixture manufacturer","Fixture model","Local test fixture");
        jdbc.update("INSERT INTO product_versions(id,product_id,version_number,status,revision,schema_json,capabilities_json,parser_tested,schema_format,legacy_product_id,created_at) VALUES(?,?,1,'DRAFT',0,'{}','{}',FALSE,'LEGACY_ATTR_JS_V1',?,CURRENT_TIMESTAMP)",profile,product,"legacy-"+UUID.randomUUID());
    }

    @Test void importsTwoLegacyDevicesWithoutPublishedModelAndReplaysSameBatch() throws Exception {
        String serial1="legacy-bulk-"+UUID.randomUUID(),serial2="legacy-bulk-"+UUID.randomUUID(),key="legacy-bulk-"+UUID.randomUUID();
        var body=payload("LEGACY_REALTIME",List.of(row("Legacy one",serial1,fixture.placeId,profile,null),row("Legacy two",serial2,fixture.placeId,profile,"upstream-"+UUID.randomUUID())));
        JsonNode first=fixture.body(call(key,body).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui IN (?,?)",Long.class,serial1,serial2)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_versions WHERE status='PUBLISHED'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases WHERE alias_value IN (?,?)",Long.class,serial1,serial2)).isEqualTo(2);
        JsonNode repeated=fixture.body(call(key,body).andExpect(status().isOk()));
        assertThat(repeated.get("items").get(0).get("id").asText()).isEqualTo(first.get("items").get(0).get("id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui IN (?,?)",Long.class,serial1,serial2)).isEqualTo(2);
    }

    @Test void importsAndBindsTwoHikvisionDevicesWithoutOverwritingExistingAssignment() throws Exception {
        var devices=List.of(discover("bulk-hik-a-"+UUID.randomUUID()),discover("bulk-hik-b-"+UUID.randomUUID()));
        String assignedSerial="bulk-hik-assigned-"+UUID.randomUUID();
        var assigned=discover(assignedSerial);
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id='device-a' WHERE external_id=?",assigned.get(0));
        String key="hik-bulk-"+UUID.randomUUID();
        var body=payload("HIKVISION_FIRECLOUD",List.of(row("Hik one",devices.get(0).get(1),fixture.placeId,null,null),row("Hik two",devices.get(1).get(1),fixture.placeId,null,null)));
        JsonNode first=fixture.body(call(key,body).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)));
        for(int i=0;i<2;i++) {
            String id=first.get("items").get(i).get("id").asText();
            assertThat(jdbc.queryForObject("SELECT local_device_id FROM hikvision_discovered_devices WHERE external_id=?",String.class,devices.get(i).get(0))).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_source_bindings WHERE device_id=? AND driver_key='HIKVISION_FIRECLOUD' AND state='ACTIVE'",Long.class,id)).isEqualTo(1);
        }
        JsonNode repeated=fixture.body(call(key,body).andExpect(status().isOk()));
        assertThat(repeated.get("items").get(0).get("id").asText()).isEqualTo(first.get("items").get(0).get("id").asText());
        assertThat(jdbc.queryForObject("SELECT local_device_id FROM hikvision_discovered_devices WHERE external_id=?",String.class,assigned.get(0))).isEqualTo("device-a");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui IN (?,?)",Long.class,devices.get(0).get(1),devices.get(1).get(1))).isEqualTo(2);
    }

    @Test void secondLegacyFailureRollsBackFirstDeviceAliasesAndIdempotency() throws Exception {
        String firstSerial="legacy-rollback-"+UUID.randomUUID(),taken="fixture-a",key="legacy-rollback-"+UUID.randomUUID();
        var body=payload("LEGACY_REALTIME",List.of(row("Must roll back",firstSerial,fixture.placeId,profile,null),row("Already owned",taken,fixture.placeId,profile,null)));
        call(key,body).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第2行")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,firstSerial)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases WHERE alias_value=?",Long.class,firstSerial)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idem_key LIKE 'vdb-%'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idem_key=?",Long.class,key)).isZero();
    }

    @Test void secondHikvisionFailureRollsBackLocalDeviceAndLeavesFirstDiscoveryUnassigned() throws Exception {
        var first=discover("rollback-hik-"+UUID.randomUUID());
        String missing="missing-hik-"+UUID.randomUUID(),key="hik-rollback-"+UUID.randomUUID();
        var body=payload("HIKVISION_FIRECLOUD",List.of(row("Must roll back",first.get(1),fixture.placeId,null,null),row("Undiscovered",missing,fixture.placeId,null,null)));
        call(key,body).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第2行")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,first.get(1))).isZero();
        assertThat(jdbc.queryForObject("SELECT local_device_id FROM hikvision_discovered_devices WHERE external_id=?",String.class,first.get(0))).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_source_bindings WHERE source_external_id=?",Long.class,first.get(0))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idem_key=?",Long.class,key)).isZero();
    }

    @Test void foreignPlaceAndMissingIntegrationPermissionCannotCrossBoundaries() throws Exception {
        String foreignPlace=foreignPlaceId;
        var body=payload("LEGACY_REALTIME",List.of(row("Foreign place", "foreign-place-"+UUID.randomUUID(),foreignPlace,profile,null)));
        call("foreign-place-"+UUID.randomUUID(),body).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-a' AND device_eui LIKE 'foreign-place-%'",Long.class)).isZero();

        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) SELECT 'bulk-device-only','bulk-device-only','Device only','PLATFORM',password_hash,FALSE,TRUE FROM app_users WHERE id='bootstrap-admin'");
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES('bulk-device-only','device:write')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bulk-device-only','tenant-a')");
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES('bulk-device-only','tenant-a','ALL_TENANT',1)");
        String token=fixture.login("bulk-device-only",IngestionIntegrationTest.PASSWORD,"management");
        var discovered=discover("no-hik-permission-"+UUID.randomUUID());
        var hikBody=payload("HIKVISION_FIRECLOUD",List.of(row("Denied",discovered.get(1),fixture.placeId,null,null)));
        fixture.call(HttpMethod.POST,BASE,token,"tenant-a","no-integration-"+UUID.randomUUID(),hikBody).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,discovered.get(1))).isZero();
        assertThat(jdbc.queryForObject("SELECT local_device_id FROM hikvision_discovered_devices WHERE external_id=?",String.class,discovered.get(0))).isNull();
    }

    @Test void duplicateAliasAcrossRowsRollsBackAndLeavesNoIdempotency() throws Exception {
        String serial1="legacy-alias-"+UUID.randomUUID(),serial2="legacy-alias-"+UUID.randomUUID();
        String alias="legacy-upstream-"+UUID.randomUUID(),key="legacy-alias-batch-"+UUID.randomUUID();
        var body=payload("LEGACY_REALTIME",List.of(row("Alias one",serial1,fixture.placeId,profile,alias),row("Alias two",serial2,fixture.placeId,profile,alias)));
        call(key,body).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第2行")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui IN (?,?)",Long.class,serial1,serial2)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases WHERE alias_value=?",Long.class,alias)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idem_key=?",Long.class,key)).isZero();
    }

    @Test void xlsxTemplateRoundTripsAndRejectsNumericIdentifiers() throws Exception {
        JsonNode template=fixture.body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/management/vendor-devices/import/template")
            .param("source","LEGACY_REALTIME").header("Authorization","Bearer "+fixture.root)).andExpect(status().isOk()));
        assertThat(template.get("media_type").asText()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        byte[] workbook=java.util.Base64.getDecoder().decode(template.get("content_base64").asText());
        String serial="legacy-xlsx-"+UUID.randomUUID();
        byte[] filled;
        try(var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(workbook));var out=new java.io.ByteArrayOutputStream()) {
            var sheet=book.getSheetAt(0);
            var row=sheet.createRow(1);
            row.createCell(0,org.apache.poi.ss.usermodel.CellType.STRING).setCellValue("XLSX legacy");
            row.createCell(1,org.apache.poi.ss.usermodel.CellType.STRING).setCellValue(serial);
            row.createCell(2,org.apache.poi.ss.usermodel.CellType.STRING).setCellValue(fixture.placeId);
            row.createCell(5,org.apache.poi.ss.usermodel.CellType.STRING).setCellValue(profile);
            book.write(out);filled=out.toByteArray();
        }
        JsonNode grid=fixture.body(fixture.call(HttpMethod.POST,"/api/management/vendor-devices/import/spreadsheet",fixture.root,"tenant-a",null,
            Map.of("source","LEGACY_REALTIME","content_base64",java.util.Base64.getEncoder().encodeToString(filled))).andExpect(status().isOk()));
        assertThat(grid.get("rows").size()).isEqualTo(2);
        assertThat(grid.get("rows").get(0).size()).isEqualTo(7);
        assertThat(grid.get("rows").get(0).get(0).asText()).isEqualTo("设备名称");
        assertThat(grid.get("rows").get(1).get(1).asText()).isEqualTo(serial);
        assertThat(grid.get("rows").get(1).get(2).asText()).isEqualTo(fixture.placeId);

        byte[] numeric;
        try(var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(workbook));var out=new java.io.ByteArrayOutputStream()) {
            var row=book.getSheetAt(0).createRow(1);
            row.createCell(0,org.apache.poi.ss.usermodel.CellType.STRING).setCellValue("Numeric serial");
            row.createCell(1,org.apache.poi.ss.usermodel.CellType.NUMERIC).setCellValue(1234567890123d);
            row.createCell(2,org.apache.poi.ss.usermodel.CellType.STRING).setCellValue(fixture.placeId);
            row.createCell(5,org.apache.poi.ss.usermodel.CellType.STRING).setCellValue(profile);
            book.write(out);numeric=out.toByteArray();
        }
        fixture.call(HttpMethod.POST,"/api/management/vendor-devices/import/spreadsheet",fixture.root,"tenant-a",null,
            Map.of("source","LEGACY_REALTIME","content_base64",java.util.Base64.getEncoder().encodeToString(numeric)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("必须是文本单元格")));
    }

    @Test void guidedXlsxUsesTheSelectedUnitsLocationsAndResolvesChoicesWithoutWriting() throws Exception {
        var response=fixture.body(fixture.call(HttpMethod.GET,BASE+"/template?source=LEGACY_REALTIME&guided=true",fixture.root,null,null,null)
            .andExpect(status().isOk()));
        byte[] template=java.util.Base64.getDecoder().decode(response.path("content_base64").asText());
        String serial="001234567890321";
        String selectedPlace=jdbc.queryForObject("SELECT name FROM places WHERE id=?",String.class,fixture.placeId);
        String unit=jdbc.queryForObject("SELECT name FROM tenants WHERE id='tenant-a'",String.class);
        String otherUnit=jdbc.queryForObject("SELECT name FROM tenants WHERE id='tenant-b'",String.class);
        String workbook, wrongUnitWorkbook, mixedWorkbook, previousTemplate, model;
        try(var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(template));var out=new java.io.ByteArrayOutputStream()) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getRow(0)).extracting(cell->cell.getStringCellValue())
                .containsExactly("设备名称","设备标识","归属单位","安装位置","设备型号","安装地址","上报标识");
            assertThat(book.getNumberOfSheets()).isEqualTo(2);
            assertThat(namedChoices(book,dropdownFormula(sheet,2,1,200))).contains(unit,otherUnit);
            String formula=dropdownFormula(sheet,3,1,200);
            assertThat(formula).contains("$C2").doesNotContain("$C$2");
            var row=sheet.createRow(2);
            for(var value:List.of("Guided smoke",serial,unit,unit+" · "+selectedPlace,
                namedChoices(book,dropdownFormula(sheet,4,1,200)).stream().filter(v->v.startsWith("Imported legacy model ·")).findFirst().orElseThrow(),
                "Entrance ceiling")) row.createCell(row.getPhysicalNumberOfCells()).setCellValue(value);
            model=row.getCell(4).getStringCellValue();
            var second=sheet.createRow(3);
            second.createCell(2).setCellValue(otherUnit);
            var checkA=sheet.getRow(2).createCell(7);
            var checkB=second.createCell(7);
            checkA.setCellFormula("INDEX("+formula.replace("$C2","$C3")+",1)");
            checkB.setCellFormula("INDEX("+formula.replace("$C2","$C4")+",1)");
            var calculator=book.getCreationHelper().createFormulaEvaluator();
            assertThat(calculator.evaluate(checkA).getStringValue()).startsWith(unit+" · ");
            assertThat(calculator.evaluate(checkB).getStringValue()).startsWith(otherUnit+" · ");
            row.removeCell(checkA);sheet.removeRow(second);
            book.write(out);
            workbook=java.util.Base64.getEncoder().encodeToString(out.toByteArray());
            out.reset();row.getCell(2).setCellValue(otherUnit);book.write(out);
            wrongUnitWorkbook=java.util.Base64.getEncoder().encodeToString(out.toByteArray());
            out.reset();row.getCell(2).setCellValue(unit);
            var different=sheet.createRow(3);
            different.createCell(0).setCellValue("Other smoke");
            different.createCell(2).setCellValue(otherUnit);
            book.write(out);
            mixedWorkbook=java.util.Base64.getEncoder().encodeToString(out.toByteArray());
            sheet.removeRow(different);
            var head=sheet.getRow(0);
            for (int col=2;col<6;col++) head.getCell(col).setCellValue(head.getCell(col+1).getStringCellValue());
            head.removeCell(head.getCell(6));
            for (int col=2;col<5;col++) row.getCell(col).setCellValue(row.getCell(col+1).getStringCellValue());
            row.removeCell(row.getCell(5));
            book.createSheet("归属设置").createRow(1).createCell(1).setCellValue(unit);
            out.reset();book.write(out);
            previousTemplate=java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        }
        var request=Map.of("source","LEGACY_REALTIME","content_base64",workbook);
        fixture.call(HttpMethod.POST,BASE+"/spreadsheet",fixture.root,null,null,Map.of("source","LEGACY_REALTIME","content_base64",wrongUnitWorkbook))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第3行「安装位置」")));
        fixture.call(HttpMethod.POST,BASE+"/spreadsheet",fixture.root,null,null,Map.of("source","LEGACY_REALTIME","content_base64",mixedWorkbook))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第4行「归属单位」")));
        fixture.call(HttpMethod.POST,BASE+"/spreadsheet",fixture.root,null,null,Map.of("source","LEGACY_REALTIME","content_base64",previousTemplate))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unit.id").value("tenant-a"));
        var preview=fixture.body(fixture.call(HttpMethod.POST,BASE+"/spreadsheet",fixture.root,null,null,request).andExpect(status().isOk()));
        assertThat(preview.path("unit").path("id").asText()).isEqualTo("tenant-a");
        assertThat(preview.path("unit").path("name").asText()).isEqualTo(unit);
        assertThat(preview.path("model_labels").path(profile).asText()).isEqualTo(model);
        var grid=preview.path("rows").get(2);
        assertThat(grid.get(1).asText()).isEqualTo(serial);
        assertThat(grid.get(2).asText()).isEqualTo(fixture.placeId);
        assertThat(grid.get(5).asText()).isEqualTo(profile);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isZero();
        var importRow=new java.util.LinkedHashMap<>(row(grid.get(0).asText(),grid.get(1).asText(),grid.get(2).asText(),grid.get(5).asText(),null));
        importRow.put("address",grid.get(4).asText());
        fixture.call(HttpMethod.POST,BASE,fixture.root,"tenant-b","wrong-unit-guided-xlsx",payload("LEGACY_REALTIME",List.of(importRow)))
            .andExpect(status().isBadRequest());
        var confirmed=fixture.body(call("guided-xlsx-confirm",payload("LEGACY_REALTIME",List.of(importRow))).andExpect(status().isOk()));
        assertThat(jdbc.queryForObject("SELECT address FROM devices WHERE device_eui=? AND place_id=? AND product_version_id=?",String.class,serial,fixture.placeId,profile)).isEqualTo("Entrance ceiling");
        var replay=fixture.body(call("guided-xlsx-confirm",payload("LEGACY_REALTIME",List.of(importRow))).andExpect(status().isOk()));
        String confirmedId=confirmed.at("/items/0/id").asText();
        assertThat(replay.at("/items/0/id").asText()).isEqualTo(confirmedId);
        assertThat(jdbc.queryForList("SELECT id FROM devices WHERE device_eui=?",String.class,serial)).containsExactly(confirmedId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history WHERE device_id=?",Long.class,confirmedId)).isEqualTo(1);

        jdbc.update("UPDATE places SET name='Renamed after template download' WHERE id=?",fixture.placeId);
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES ('replacement-place','tenant-a',?)",selectedPlace);
        fixture.call(HttpMethod.POST,BASE+"/spreadsheet",fixture.root,null,null,request).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第3行「安装位置」")));
    }

    private static String dropdownFormula(org.apache.poi.ss.usermodel.Sheet sheet,int column,int first,int last) {
        return sheet.getDataValidations().stream().filter(rule->java.util.Arrays.stream(rule.getRegions().getCellRangeAddresses())
            .anyMatch(range->range.isInRange(first,column) && range.isInRange(last,column))).findFirst().orElseThrow().getValidationConstraint().getFormula1();
    }

    private static List<String> namedChoices(org.apache.poi.ss.usermodel.Workbook book,String name) {
        var named=book.getName(name);
        var refs=new org.apache.poi.ss.util.AreaReference(named.getRefersToFormula(),org.apache.poi.ss.SpreadsheetVersion.EXCEL2007).getAllReferencedCells();
        return java.util.Arrays.stream(refs).map(ref->book.getSheet(ref.getSheetName()).getRow(ref.getRow()).getCell(ref.getCol()).getStringCellValue()).toList();
    }


    @Test void originalXlsPreviewsWithoutWritesAndImportsTheExactTextIdentity() throws Exception {
        String serial="012345678901234";
        String place=jdbc.queryForObject("SELECT name FROM places WHERE id=?",String.class,fixture.placeId);
        var response=previewOriginal("tenant-a",originalFile(serial,place)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("fixture-only-secret");
        JsonNode row=json.readTree(response).path("rows").get(0);
        assertThat(row.path("serial").asText()).isEqualTo(serial);
        assertThat(row.path("source_row").asInt()).isEqualTo(3);
        assertThat(row.path("place_id").asText()).isEqualTo(fixture.placeId);
        assertThat(row.path("legacy_profile_id").asText()).isEqualTo(profile);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isZero();
        var input=Map.of("source","LEGACY_REALTIME","rows",List.of(row));
        var confirmed=fixture.body(call("original-xls",input).andExpect(status().isOk()));
        var replay=fixture.body(call("original-xls",input).andExpect(status().isOk()));
        String deviceId=confirmed.at("/items/0/id").asText();
        assertThat(replay.at("/items/0/id").asText()).isEqualTo(deviceId);
        assertThat(jdbc.queryForList("SELECT id FROM devices WHERE device_eui=? AND place_id=?",String.class,serial,fixture.placeId))
            .containsExactly(deviceId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history WHERE device_id=?",Long.class,deviceId)).isEqualTo(1);
    }

    @Test void originalXlsRejectsNumericIdsAndAmbiguousOrOutsidePositions() throws Exception {
        var same=jdbc.queryForObject("SELECT name FROM places WHERE id=?",String.class,fixture.placeId);
        var outside=jdbc.queryForObject("SELECT name FROM places WHERE id=?",String.class,foreignPlaceId);
        previewOriginal("tenant-a",originalFile(123456789012345d,same)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第3行「设备编号」：必须是文本")));
        previewOriginal("tenant-a",originalFile("012345678901235",outside)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第3行「所属位置」")));
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES ('same-place','tenant-a',?,?)",same,fixture.placeId);
        previewOriginal("tenant-a",originalFile("012345678901236",same)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(same+" / "+same)));
        previewOriginal("tenant-a",originalFile("012345678901236",same+" / "+same)).andExpect(status().isOk())
            .andExpect(jsonPath("$.rows[0].place_id").value("same-place"));
    }

    @Test void originalXlsCannotOverwriteAnIdentityClaimedAfterPreview() throws Exception {
        String serial="012345678901237", other="012345678901238";
        String place=jdbc.queryForObject("SELECT name FROM places WHERE id=?",String.class,fixture.placeId);
        var first=fixture.body(previewOriginal("tenant-a",originalFile(serial,place)).andExpect(status().isOk())).path("rows").get(0);
        var second=(com.fasterxml.jackson.databind.node.ObjectNode)fixture.body(previewOriginal("tenant-a",originalFile(other,place)).andExpect(status().isOk())).path("rows").get(0);
        second.put("source_row",8);
        var claimed=fixture.body(call("claim-after-preview",payload("LEGACY_REALTIME",List.of(row("Claimed later",other,fixture.placeId,profile,null)))).andExpect(status().isOk()));
        call("reject-original-batch",Map.of("source","LEGACY_REALTIME","rows",List.of(first,second))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第8行")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isZero();
        var claimedId=claimed.path("items").get(0).path("id").asText();
        fixture.call(HttpMethod.POST,"/api/management/devices/"+claimedId+"/archive",fixture.root,"tenant-a","archive-after-preview",
            Map.of("expected_revision",0,"reason","Archived after spreadsheet preview")).andExpect(status().isOk());
        previewOriginal("tenant-a",originalFile(other,place)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TERMINAL_IDENTIFICATION_HAS_HISTORY"));
        call("reject-archived-original-batch",Map.of("source","LEGACY_REALTIME","rows",List.of(first,second))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TERMINAL_IDENTIFICATION_HAS_HISTORY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,other)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions previewOriginal(String tenant,String encoded) throws Exception {
        return fixture.call(HttpMethod.POST,BASE+"/legacy-terminal/preview",fixture.root,tenant,null,Map.of("content_base64",encoded));
    }

    private static String originalFile(Object serial,String place) throws Exception {
        try(var workbook=new HSSFWorkbook();var out=new java.io.ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("终端设备");
            var header=sheet.createRow(0);
            var names=List.of("所属系统","应用","平台","产品","设备名称","所属位置","设备编号","入网模式","imei","频点",
                "AppKey","AppEUI","NwkSKey","AppSKey","DevAddr","安装人","安装人电话","安装位置","GPS坐标");
            for(int i=0;i<names.size();i++)header.createCell(i).setCellValue(names.get(i));
            workbook.createSheet("hidden1").createRow(0).createCell(0).setCellValue("旧下拉候选不是设备");
            workbook.setSheetHidden(1,true);
            var data=sheet.createRow(2);
            data.createCell(3).setCellValue("Imported legacy model");
            data.createCell(4).setCellValue("Fixture old smoke");
            data.createCell(5).setCellValue(place);
            if(serial instanceof Number n)data.createCell(6).setCellValue(n.doubleValue());else data.createCell(6).setCellValue((String)serial);
            data.createCell(7).setCellValue("CAT1");
            if(serial instanceof String s)data.createCell(8).setCellValue(s);
            data.createCell(10).setCellValue("fixture-only-secret");
            data.createCell(17).setCellValue(205);
            workbook.write(out);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }

    private List<String> discover(String serial) throws Exception {
        String external="bulk-external-"+UUID.randomUUID();
        ingestion.accept(HikvisionClient.Stream.DEVICE,json.valueToTree(List.of(Map.of("deviceID",external,"deviceSerial",serial,"deviceName","Fixture discovered","deviceType","600002","deviceModel","800235"))),OffsetDateTime.now().minusMinutes(1));
        return List.of(external,serial);
    }
    private Map<String,Object> payload(String source,List<Map<String,Object>> rows) { return Map.of("source",source,"rows",rows); }
    private Map<String,Object> row(String name,String serial,String place,String legacyProfile,String upstream) {
        var row=new java.util.LinkedHashMap<String,Object>();row.put("name",name);row.put("serial",serial);row.put("place_id",place);
        if(legacyProfile!=null)row.put("legacy_profile_id",legacyProfile);if(upstream!=null)row.put("upstream_device_id",upstream);return row;
    }
    private org.springframework.test.web.servlet.ResultActions call(String key,Object body) throws Exception { return fixture.call(HttpMethod.POST,BASE,fixture.root,"tenant-a",key,body); }
}
