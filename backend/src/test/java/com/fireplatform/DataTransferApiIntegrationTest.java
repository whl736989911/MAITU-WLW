package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","app.data-transfer-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.notification-contact-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","spring.datasource.url=jdbc:h2:mem:data-transfer-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataTransferApiIntegrationTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry){IngestionIntegrationTest.environment(registry);}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired JsonPointerParser parser;
    @Autowired com.fireplatform.notification.officialaccount.WechatOfficialAccountSecretStorage wechatSecrets;
    private String root;
    private IngestionIntegrationTest fixture;

    @BeforeEach void initializeFixture() throws Exception {
        jdbc.update("DELETE FROM data_transfer_control_requests");jdbc.update("DELETE FROM data_transfer_job_rows");jdbc.update("DELETE FROM data_transfer_jobs");jdbc.update("DELETE FROM data_transfer_exports");jdbc.update("DELETE FROM data_transfer_plans");
        for(String table:List.of("notification_rule_receivers","notification_rules","notification_contacts","notification_wechat_binding_history","notification_wechat_oauth_states","notification_wechat_bindings"))jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE notification_contact_key_guard SET fingerprint=NULL WHERE id=1");
        jdbc.update("UPDATE notification_wechat_config SET app_id=NULL,enabled=FALSE WHERE id=1");
        fixture=new IngestionIntegrationTest();fixture.mvc=mvc;fixture.json=json;fixture.jdbc=jdbc;fixture.bootstrap=bootstrap;fixture.parser=parser;fixture.reset();root=fixture.root;
    }

    @Test void completedConfirmReplayReturnsSameJobWithoutCreatingTheUnitAgain() throws Exception {
        JsonNode template=body(call(HttpMethod.GET,"/api/management/data-transfer/template?kind=UNITS&format=CSV",root,null,null,null).andExpect(status().isOk()));
        String content=new String(Base64.getDecoder().decode(template.get("content_base64").asText()),StandardCharsets.UTF_8);
        var cells=new ArrayList<String>(java.util.Collections.nCopies(18,""));String name="Transfer "+UUID.randomUUID().toString().substring(0,8);cells.set(1,name);
        content+=cells.stream().map(DataTransferApiIntegrationTest::csvCell).collect(java.util.stream.Collectors.joining(","))+"\r\n";
        var previewRequest=json.createObjectNode().put("kind","UNITS").put("format","CSV").put("content_base64",Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))).putNull("tenant_id");
        JsonNode preview=body(call(HttpMethod.POST,"/api/management/data-transfer/preview",root,null,null,previewRequest).andExpect(status().isOk()));
        String plan=preview.get("plan_id").asText(),sha=preview.get("source_sha256").asText(),key="confirm-"+UUID.randomUUID();
        var confirm=json.createObjectNode().put("plan_id",plan).put("expected_sha256",sha).put("reason","Idempotent integration fixture").putNull("tenant_id");
        JsonNode first=body(call(HttpMethod.POST,"/api/management/data-transfer/confirm",root,null,key,confirm).andExpect(status().isOk()));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);String state=first.get("status").asText();
        while(!"COMPLETED".equals(state)&&System.nanoTime()<deadline){Thread.sleep(25);state=jdbc.queryForObject("SELECT status FROM data_transfer_jobs WHERE id=?",String.class,first.get("id").asText());}
        assertThat(state).isEqualTo("COMPLETED");
        JsonNode replay=body(call(HttpMethod.POST,"/api/management/data-transfer/confirm",root,null,key,confirm).andExpect(status().isOk()));
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE name=?",Long.class,name)).isEqualTo(1);
    }
    @Test void unitExportsHonorEmptyScopedAndSelectedRangesAcrossAllAuthorizedUnits() throws Exception {
        var filtered=json.createObjectNode().put("kind","UNITS").put("format","CSV").put("reason","Scoped export fixture").putNull("tenant_id");
        filtered.set("filters",json.valueToTree(Map.of("unit_ids","tenant-a","place_ids","")));filtered.set("selected_ids",json.valueToTree(List.of("tenant-a")));
        JsonNode selected=body(call(HttpMethod.POST,"/api/management/data-transfer/exports",root,null,"unit-selected-"+UUID.randomUUID(),filtered).andExpect(status().isOk()));
        assertThat(selected.get("row_count").asInt()).isEqualTo(1);
        var empty=json.createObjectNode().put("kind","UNITS").put("format","CSV").put("reason","Empty range fixture").putNull("tenant_id");
        empty.set("filters",json.valueToTree(Map.of("unit_ids","","place_ids","")));empty.set("selected_ids",json.nullNode());
        JsonNode none=body(call(HttpMethod.POST,"/api/management/data-transfer/exports",root,null,"unit-empty-"+UUID.randomUUID(),empty).andExpect(status().isOk()));
        assertThat(none.get("row_count").asInt()).isZero();
        var outside=json.createObjectNode().put("kind","UNITS").put("format","CSV").put("reason","Reject outside range fixture").putNull("tenant_id");
        outside.set("filters",json.valueToTree(Map.of("unit_ids","tenant-a","place_ids","")));outside.set("selected_ids",json.valueToTree(List.of("tenant-b")));
        call(HttpMethod.POST,"/api/management/data-transfer/exports",root,null,"unit-outside-"+UUID.randomUUID(),outside).andExpect(status().isForbidden());
    }
    @Test void disabledUnitCannotLeakIntoAggregateEventExports() throws Exception {
        insertCapturedEvent("disabled-export-source", "tenant-b", "disabled-export-event", "disabled-export-message", "device-b");
        long revision=jdbc.queryForObject("SELECT revision FROM tenants WHERE id='tenant-b'",Long.class);
        call(HttpMethod.PUT,"/api/management/units/tenant-b",root,null,"disable-export-"+UUID.randomUUID(),
            Map.of("name","Ingestion test tenant B","enabled",false,"expected_revision",revision)).andExpect(status().isOk());

        var explicit=json.createObjectNode().put("kind","EVENTS").put("format","CSV").put("reason","Disabled-unit scope regression").put("tenant_id","tenant-b");
        explicit.set("filters",json.createObjectNode());
        explicit.set("selected_ids",json.nullNode());
        call(HttpMethod.POST,"/api/management/data-transfer/exports",root,null,"explicit-disabled-"+UUID.randomUUID(),explicit)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UNIT_DISABLED"));

        var aggregate=json.createObjectNode().put("kind","EVENTS").put("format","CSV").put("reason","Disabled-unit scope regression").putNull("tenant_id");
        aggregate.set("filters",json.createObjectNode());
        aggregate.set("selected_ids",json.nullNode());
        JsonNode exported=body(call(HttpMethod.POST,"/api/management/data-transfer/exports",root,null,
            "aggregate-disabled-"+UUID.randomUUID(),aggregate).andExpect(status().isOk()));
        JsonNode artifact=body(call(HttpMethod.GET,"/api/management/data-transfer/exports/"+exported.get("id").asText()+"/download",root,null,null,null).andExpect(status().isOk()));
        String csv=new String(Base64.getDecoder().decode(artifact.get("content_base64").asText()),StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain("GENERIC_MESSAGE:disabled-export-event");
    }

    @Test void eventExportFiltersByCurrentDevicePlace() throws Exception {
        jdbc.update("UPDATE devices SET place_id=? WHERE id='device-a'",fixture.placeId);
        insertCapturedEvent("selected-place-source", "tenant-a", "selected-place-event", "selected-place-message", "device-a");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES ('same-unit-unplaced','tenant-a','Unplaced smoke','same-unit-unplaced','legacy-model','Unknown','NORMAL',FALSE,'LEGACY_UNVERIFIED')");
        insertCapturedEvent("unselected-place-source", "tenant-a", "unselected-place-event", "unselected-place-message", "same-unit-unplaced");
        var request=json.createObjectNode().put("kind","EVENTS").put("format","CSV").put("reason","Selected place export").putNull("tenant_id");
        request.set("filters",json.createObjectNode().put("unit_ids","").put("place_ids",fixture.placeId));
        JsonNode exported=body(call(HttpMethod.POST,"/api/management/data-transfer/exports",root,null,
            "place-event-export-"+UUID.randomUUID(),request).andExpect(status().isOk()));
        assertThat(exported.path("row_count").asInt()).isEqualTo(1);
        JsonNode artifact=body(call(HttpMethod.GET,"/api/management/data-transfer/exports/"+exported.path("id").asText()+"/download",
            root,null,null,null).andExpect(status().isOk()));
        String csv=new String(Base64.getDecoder().decode(artifact.path("content_base64").asText()),StandardCharsets.UTF_8);
        assertThat(csv).contains("GENERIC_MESSAGE:selected-place-event").doesNotContain("GENERIC_MESSAGE:unselected-place-event");
    }

    private void insertCapturedEvent(String sourceId,String tenant,String rawId,String sourceMessageId,String deviceId) {
        jdbc.update("INSERT INTO ingestion_sources(id,tenant_id,name,protocol,is_simulated,enabled,revision,token_hash,token_generation,created_at) VALUES (?,?,?,'GENERIC_HTTP_JSON_V1',FALSE,TRUE,0,?,0,CURRENT_TIMESTAMP)",
            sourceId,tenant,"disabled export source","0".repeat(64));
        byte[] payload="{\"event\":\"FIRE\"}".getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO raw_messages(id,tenant_id,source_id,source_name,source_message_id,vendor_device_id,device_id,device_name,device_address,received_at,payload_utf8,payload_bytes,payload_sha256,binding_state,capture_provenance) VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?,?,'BOUND','AUTHENTICATED_SOURCE')",
            rawId,tenant,sourceId,"disabled export source",sourceMessageId,"vendor-"+deviceId,deviceId,"device","address",payload,(long)payload.length,"0".repeat(64));
    }
    @Test void managementDeviceExportsUseAuthorizedAggregateScopeAndEncryptedArtifacts() throws Exception {
        var selectedRequest=json.createObjectNode().put("kind","DEVICES").put("format","CSV").put("reason","Selected device export fixture").putNull("tenant_id");
        selectedRequest.set("filters",json.valueToTree(Map.of("tenant_id","tenant-a")));selectedRequest.set("selected_ids",json.valueToTree(List.of("device-a")));
        JsonNode selected=body(call(HttpMethod.POST,"/api/management/data-transfer/exports",root,null,"device-selected-"+UUID.randomUUID(),selectedRequest).andExpect(status().isOk()));
        assertThat(selected.get("row_count").asInt()).isEqualTo(1);
        String cipher=jdbc.queryForObject("SELECT content FROM data_transfer_exports WHERE id=?",String.class,selected.get("id").asText());
        assertThat(cipher).doesNotContain("device-a").doesNotContain("device-b");
        JsonNode artifact=body(call(HttpMethod.GET,"/api/management/data-transfer/exports/"+selected.get("id").asText()+"/download",root,null,null,null).andExpect(status().isOk()));
        byte[] bytes=Base64.getDecoder().decode(artifact.get("content_base64").asText());
        String csv=new String(bytes,StandardCharsets.UTF_8);
        assertThat(csv).contains("device-a").doesNotContain("device-b");
        assertThat(artifact.get("content_sha256").asText()).isEqualTo(selected.get("content_sha256").asText());
        var emptyRequest=json.createObjectNode().put("kind","DEVICES").put("format","CSV").put("reason","Empty device export fixture").putNull("tenant_id");
        emptyRequest.set("filters",json.valueToTree(Map.of("tenant_id","")));emptyRequest.set("selected_ids",json.valueToTree(List.of()));
        JsonNode empty=body(call(HttpMethod.POST,"/api/management/data-transfer/exports",root,null,"device-empty-"+UUID.randomUUID(),emptyRequest).andExpect(status().isOk()));
        assertThat(empty.get("row_count").asInt()).isZero();
        var aggregateRequest=json.createObjectNode().put("kind","DEVICES").put("format","CSV").put("reason","Aggregate device export fixture").putNull("tenant_id");
        aggregateRequest.set("filters",json.valueToTree(Map.of()));aggregateRequest.set("selected_ids",json.nullNode());
        JsonNode aggregate=body(call(HttpMethod.POST,"/api/management/data-transfer/exports",root,null,"device-aggregate-"+UUID.randomUUID(),aggregateRequest).andExpect(status().isOk()));
        assertThat(aggregate.get("row_count").asInt()).isGreaterThanOrEqualTo(2);
    }
    @Test void customerDeviceExportNeverCrossesItsSessionTenant() throws Exception {
        String customer=login("customer-a",IngestionIntegrationTest.INITIAL,"customer");
        call(HttpMethod.POST,"/api/auth/change-password",customer,null,null,Map.of("current_password",IngestionIntegrationTest.INITIAL,"new_password",IngestionIntegrationTest.PASSWORD)).andExpect(status().isNoContent());
        customer=login("customer-a",IngestionIntegrationTest.PASSWORD,"customer");
        String customerId=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,'exports:read'),(?,'exports:write')",customerId,customerId);
        var request=json.createObjectNode().put("kind","DEVICES").put("format","CSV").put("reason","Customer scoped export fixture").put("tenant_id","tenant-a");
        JsonNode export=body(call(HttpMethod.POST,"/api/customer/data-transfer/exports",customer,null,"customer-export-"+UUID.randomUUID(),request).andExpect(status().isOk()));
        assertThat(export.get("row_count").asInt()).isEqualTo(1);
        JsonNode artifact=body(call(HttpMethod.GET,"/api/customer/data-transfer/exports/"+export.get("id").asText()+"/download",customer,null,null,null).andExpect(status().isOk()));
        String csv=new String(Base64.getDecoder().decode(artifact.get("content_base64").asText()),StandardCharsets.UTF_8);
        assertThat(csv).contains("device-a").doesNotContain("device-b");
    }

    @Test void customerImportsOwnPlacesAndDevicesWithRowFailuresAndScopedDownloads() throws Exception {
        String customer=customerWriter();
        String foreignPlace="foreign-transfer-place";
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES (?,'tenant-b','Foreign place')",foreignPlace);
        JsonNode placePreview=customerPreview(customer,"PLACES",List.of(
            List.of("Imported child",fixture.placeId),List.of("Rejected foreign parent",foreignPlace),List.of("",fixture.placeId)));
        assertThat(placePreview.get("valid_count").asInt()).isEqualTo(2);
        assertThat(placePreview.get("error_count").asInt()).isEqualTo(1);
        JsonNode placeJob=customerConfirm(customer,placePreview);
        assertThat(placeJob.get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(placeJob.get("success_count").asInt()).isEqualTo(1);
        assertThat(placeJob.get("error_count").asInt()).isEqualTo(2);
        JsonNode placeRows=body(call(HttpMethod.GET,"/api/customer/data-transfer/jobs/"+placeJob.get("id").asText()+"/rows",customer,null,null,null).andExpect(status().isOk()));
        assertThat(placeRows.get(0).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(placeRows.get(1).get("status").asText()).isEqualTo("FAILED");
        assertThat(placeRows.get(2).get("status").asText()).isEqualTo("INVALID");
        String child=placeRows.get(0).get("result_id").asText();
        assertThat(jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id=? AND tenant_id='tenant-a'",String.class,child)).isEqualTo(fixture.placeId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE name='Rejected foreign parent'",Long.class)).isZero();

        JsonNode version=fixture.published(fixture.product().get("id").asText(),"1");
        String eui="customer-import-"+UUID.randomUUID();
        JsonNode devicePreview=customerPreview(customer,"DEVICES",List.of(
            List.of("Imported own smoke",eui,"Own installation",version.get("id").asText(),child,"消防","烟感"),
            List.of("Invalid identifier","invalid eui","Own installation",version.get("id").asText(),child,"",""),
            List.of("Foreign installation","foreign-"+UUID.randomUUID(),"Foreign installation",version.get("id").asText(),foreignPlace,"","")));
        assertThat(devicePreview.get("valid_count").asInt()).isEqualTo(1);
        assertThat(devicePreview.get("error_count").asInt()).isEqualTo(2);
        JsonNode deviceJob=customerConfirm(customer,devicePreview);
        assertThat(deviceJob.get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(deviceJob.get("success_count").asInt()).isEqualTo(1);
        assertThat(deviceJob.get("error_count").asInt()).isEqualTo(2);
        String imported=jdbc.queryForObject("SELECT id FROM devices WHERE tenant_id='tenant-a' AND device_eui=? AND place_id=? AND product_version_id=? AND is_simulated=FALSE AND integration_state='PENDING_INTEGRATION'",String.class,eui,child,version.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id='tenant-b'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE name IN ('Invalid identifier','Foreign installation')",Long.class)).isZero();
        call(HttpMethod.GET,"/api/customer/data-transfer/jobs/"+deviceJob.get("id").asText()+"/rows",customer,null,null,null)
            .andExpect(status().isOk()).andExpect(jsonPath("$[2].status").value("INVALID"));
        JsonNode errorFile=body(call(HttpMethod.GET,"/api/customer/data-transfer/jobs/"+deviceJob.get("id").asText()+"/errors/download?format=CSV",customer,null,null,null).andExpect(status().isOk()));
        byte[] errorBytes=Base64.getDecoder().decode(errorFile.get("content_base64").asText());
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(errorBytes))).isEqualTo(errorFile.get("content_sha256").asText());
        assertThat(new String(errorBytes,StandardCharsets.UTF_8)).contains("\"3\"","\"4\"");

        String unselected=body(call(HttpMethod.POST,"/api/management/places",root,"tenant-a",null,Map.of("name","Unselected own root")).andExpect(status().isOk())).get("id").asText();
        var exportRequest=json.createObjectNode().put("kind","PLACES").put("format","CSV").put("reason","Own place export").put("tenant_id","tenant-a");
        exportRequest.set("filters",json.createObjectNode().put("place_ids",fixture.placeId));
        JsonNode export=body(call(HttpMethod.POST,"/api/customer/data-transfer/exports",customer,null,"own-places-"+UUID.randomUUID(),exportRequest).andExpect(status().isOk()));
        assertThat(export.get("row_count").asInt()).isEqualTo(2);
        JsonNode artifact=body(call(HttpMethod.GET,"/api/customer/data-transfer/exports/"+export.get("id").asText()+"/download",customer,null,null,null).andExpect(status().isOk()));
        String exported=new String(Base64.getDecoder().decode(artifact.get("content_base64").asText()),StandardCharsets.UTF_8);
        assertThat(exported).contains(fixture.placeId,child,"Imported child").doesNotContain(unselected,"Unselected own root",foreignPlace,"Foreign place");
        var foreignRequest=json.createObjectNode().put("kind","PLACES").put("format","CSV").put("tenant_id","tenant-b").put("content_base64",Base64.getEncoder().encodeToString("\"name\",\"parent_place_id\"\r\n\"Not allowed\",\"\"\r\n".getBytes(StandardCharsets.UTF_8)));
        call(HttpMethod.POST,"/api/customer/data-transfer/preview",customer,null,null,foreignRequest).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history WHERE device_id=? AND to_place_id=? AND action='REGISTER'",Long.class,imported,child)).isEqualTo(1);
    }

    @Test void customerDeviceImportCannotCreateItsOwnDeviceScope() throws Exception {
        String customer=customerWriter();
        String user=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id=? AND tenant_id='tenant-a'",user);
        jdbc.update("DELETE FROM user_device_scopes WHERE user_id=? AND tenant_id='tenant-a'",user);
        JsonNode version=fixture.published(fixture.product().get("id").asText(),"1");
        String eui="scope-refused-"+UUID.randomUUID();
        JsonNode preview=customerPreview(customer,"DEVICES",List.of(List.of("No scope device",eui,"Own address",version.get("id").asText(),fixture.placeId,"","")));
        assertThat(preview.get("valid_count").asInt()).isZero();
        assertThat(preview.get("rows").get(0).get("status").asText()).isEqualTo("INVALID");
        call(HttpMethod.POST,"/api/customer/data-transfer/confirm",customer,null,"no-scope-confirm",customerConfirmRequest(preview))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,eui)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scopes WHERE user_id=? AND tenant_id='tenant-a'",Long.class,user)).isZero();
    }

    @Test void deviceWorkbookKeepsPhysicalRowsAndRejectsUnavailablePlacesThroughConfirmation() throws Exception {
        String customer=customerWriter();
        String user=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        String child="transfer-child", outside="transfer-outside", foreign="transfer-foreign", archived="transfer-archived";
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES (?,'tenant-a','Allowed child',?)",child,fixture.placeId);
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES (?,'tenant-a','Outside subtree'),(?,'tenant-b','Foreign place')",outside,foreign);
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id,archived_at) VALUES (?,'tenant-a','Archived child',?,CURRENT_TIMESTAMP)",archived,fixture.placeId);
        jdbc.update("UPDATE user_device_scopes SET mode='PLACE_SUBTREE',revision=revision+1 WHERE user_id=? AND tenant_id='tenant-a'",user);
        jdbc.update("INSERT INTO user_device_scope_places(user_id,tenant_id,place_id) VALUES (?,'tenant-a',?)",user,fixture.placeId);
        String version=fixture.published(fixture.product().get("id").asText(),"1").get("id").asText();
        JsonNode template=body(call(HttpMethod.GET,"/api/customer/data-transfer/template?kind=DEVICES&format=XLSX",customer,null,null,null)
            .andExpect(status().isOk()));
        String content;
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(template.get("content_base64").asText())));
            var out=new ByteArrayOutputStream()) {
            var sheet=workbook.getSheetAt(0);
            sheet.createRow(2).createCell(0).setCellValue(" ");
            var targets=List.of(child, foreign, "transfer-missing", outside, archived);
            int[] physicalRows={4,6,7,9,10};
            for(int i=0;i<targets.size();i++){
                var row=sheet.createRow(physicalRows[i]-1);
                var cells=List.of("Workbook device "+i,"xlsx-device-"+i,"Installation address",version,targets.get(i),"","");
                for(int c=0;c<cells.size();c++)row.createCell(c).setCellValue(cells.get(c));
            }
            workbook.write(out);
            content=Base64.getEncoder().encodeToString(out.toByteArray());
        }
        JsonNode preview=body(call(HttpMethod.POST,"/api/customer/data-transfer/preview",customer,null,null,
            Map.of("kind","DEVICES","format","XLSX","tenant_id","tenant-a","content_base64",content)).andExpect(status().isOk()));
        Iterable<JsonNode> previewRows=preview.get("rows");
        assertAll(
            () -> assertThat(preview.get("row_count").asInt()).isEqualTo(5),
            () -> assertThat(previewRows).extracting(row->row.get("row_no").asInt()).containsExactly(4,6,7,9,10),
            () -> assertThat(previewRows).extracting(row->row.get("status").asText()).containsExactly("VALID","INVALID","INVALID","INVALID","INVALID"));

        String key="workbook-confirm";
        var confirm=customerConfirmRequest(preview);
        jdbc.update("UPDATE user_device_scope_places SET place_id=? WHERE user_id=? AND tenant_id='tenant-a'",outside,user);
        call(HttpMethod.POST,"/api/customer/data-transfer/confirm",customer,null,key,confirm)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_INVALID"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_transfer_jobs WHERE plan_id=?",Long.class,preview.get("plan_id").asText())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui LIKE 'xlsx-device-%'",Long.class)).isZero();

        jdbc.update("UPDATE user_device_scope_places SET place_id=? WHERE user_id=? AND tenant_id='tenant-a'",fixture.placeId,user);
        JsonNode job=customerConfirm(customer,preview,key);
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(job.get("success_count").asInt()).isEqualTo(1);
        assertThat(job.get("error_count").asInt()).isEqualTo(4);
        String jobId=job.get("id").asText(), jobPath="/api/customer/data-transfer/jobs/"+jobId;
        Iterable<JsonNode> rows=body(call(HttpMethod.GET,jobPath+"/rows",customer,null,null,null).andExpect(status().isOk()));
        assertThat(rows).extracting(row->row.get("row_no").asInt()+":"+row.get("status").asText())
            .containsExactly("4:SUCCEEDED","6:INVALID","7:INVALID","9:INVALID","10:INVALID");
        Iterable<JsonNode> errors=body(call(HttpMethod.GET,jobPath+"/errors",customer,null,null,null).andExpect(status().isOk()));
        assertThat(errors).extracting(row->row.get("row_no").asInt()).containsExactly(6,7,9,10);
        call(HttpMethod.POST,"/api/customer/data-transfer/confirm",customer,null,key,confirm)
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(jobId));
        assertThat(jdbc.queryForList("SELECT idem_key FROM idempotency_records WHERE user_id=? AND operation='device-register'",String.class,user))
            .containsExactly("data-transfer:"+jobId+":4");
        assertThat(jdbc.queryForList("SELECT device_eui || ':' || tenant_id || ':' || place_id FROM devices WHERE device_eui LIKE 'xlsx-device-%'",String.class))
            .containsExactly("xlsx-device-0:tenant-a:"+child);
    }

    @Test void managementContactPreviewUsesTheSamePhoneAndTypePolicyAsManualWrites() throws Exception {
        JsonNode template=body(call(HttpMethod.GET,"/api/management/data-transfer/template?kind=CONTACTS&format=CSV",root,"tenant-a",null,null).andExpect(status().isOk()));
        StringBuilder csv=new StringBuilder(new String(Base64.getDecoder().decode(template.get("content_base64").asText()),StandardCharsets.UTF_8));
        for(var row:List.of(List.of("Valid duty contact","DUTY_OFFICER","13800001001","false"),
                List.of("Invalid phone","DUTY_OFFICER","not-a-phone","false"),
                List.of("Invalid role","NOT_A_CONTACT_TYPE","13800001002","false")))
            csv.append(row.stream().map(DataTransferApiIntegrationTest::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        JsonNode preview=body(call(HttpMethod.POST,"/api/management/data-transfer/preview",root,"tenant-a",null,Map.of("kind","CONTACTS","format","CSV","tenant_id","tenant-a","content_base64",Base64.getEncoder().encodeToString(csv.toString().getBytes(StandardCharsets.UTF_8)))).andExpect(status().isOk()));
        assertThat(preview.get("valid_count").asInt()).isEqualTo(1);
        assertThat(preview.get("error_count").asInt()).isEqualTo(2);
        assertThat(preview.get("rows").get(0).get("status").asText()).isEqualTo("VALID");
        assertThat(preview.get("rows").get(1).get("status").asText()).isEqualTo("INVALID");
        assertThat(preview.get("rows").get(2).get("status").asText()).isEqualTo("INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_contacts",Long.class)).isZero();
    }

    @Test void customerContactImportValidatesBeforeEnqueueAndRetainsPhoneOnBlankUpdate() throws Exception {
        String customer=notificationWriter();
        JsonNode original=contact(customer,"Existing contact","13800001002",null);
        String existingId=original.get("id").asText(),originalCipher=jdbc.queryForObject("SELECT phone_cipher FROM notification_contacts WHERE id=?",String.class,existingId);
        JsonNode foreign=body(call(HttpMethod.POST,"/api/management/notification-contacts",root,"tenant-b",UUID.randomUUID().toString(),Map.of("name","Other unit contact","contact_type","DUTY_OFFICER","phone","13800001009","enabled",true,"reason","Isolated scope fixture")).andExpect(status().isCreated()));
        JsonNode preview=customerPreview(customer,"UNIT_CONTACTS",List.of(
            List.of("","Imported mobile","DUTY_OFFICER","13800001001","false",""),
            List.of("","Invalid phone","DUTY_OFFICER","not-a-phone","false",""),
            List.of("","Missing phone","DUTY_OFFICER","","false",""),
            List.of("","Invalid role","BAD_TYPE","13800001003","false",""),
            List.of("","Imported landline","CONTROL_ROOM","010-12345678","false",""),
            List.of(existingId,"Renamed existing","DUTY_OFFICER","","true","0"),
            List.of(existingId,"Bad revision","DUTY_OFFICER","","true","not-an-integer"),
            List.of(existingId,"Negative revision","DUTY_OFFICER","","true","-1"),
            List.of(foreign.get("id").asText(),"Foreign update","DUTY_OFFICER","","true","0")));
        assertThat(preview.get("valid_count").asInt()).isEqualTo(4);
        assertThat(preview.get("error_count").asInt()).isEqualTo(5);
        JsonNode job=customerConfirm(customer,preview);
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(job.get("success_count").asInt()).isEqualTo(3);
        assertThat(job.get("error_count").asInt()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT phone_cipher FROM notification_contacts WHERE id=?",String.class,existingId)).isEqualTo(originalCipher);
        assertThat(jdbc.queryForObject("SELECT name FROM notification_contacts WHERE id=?",String.class,existingId)).isEqualTo("Renamed existing");
        assertThat(jdbc.queryForObject("SELECT name FROM notification_contacts WHERE id=?",String.class,foreign.get("id").asText())).isEqualTo("Other unit contact");
        assertThat(jdbc.queryForList("SELECT phone_masked FROM notification_contacts WHERE tenant_id='tenant-a'",String.class)).containsExactlyInAnyOrder("138****1001","138****1002","010-****5678");
        JsonNode rows=body(call(HttpMethod.GET,"/api/customer/data-transfer/jobs/"+job.get("id").asText()+"/rows",customer,null,null,null).andExpect(status().isOk()));
        assertThat(rows.get(6).get("status").asText()).isEqualTo("INVALID");
        assertThat(rows.get(8).get("status").asText()).isEqualTo("FAILED");
        var exportInput=json.createObjectNode().put("kind","UNIT_CONTACTS").put("format","CSV").put("tenant_id","tenant-a").put("reason","Private contact export");
        exportInput.set("filters",json.valueToTree(Map.of("unit_ids","tenant-a","place_ids","")));
        JsonNode exported=body(call(HttpMethod.POST,"/api/customer/data-transfer/exports",customer,null,UUID.randomUUID().toString(),exportInput).andExpect(status().isOk()));
        assertThat(exported.get("row_count").asInt()).isEqualTo(3);
        JsonNode file=body(call(HttpMethod.GET,"/api/customer/data-transfer/exports/"+exported.get("id").asText()+"/download",customer,null,null,null).andExpect(status().isOk()));
        String text=new String(Base64.getDecoder().decode(file.get("content_base64").asText()),StandardCharsets.UTF_8);
        assertThat(text).contains("Imported mobile","Imported landline","Renamed existing").doesNotContain("13800001001","13800001002","010-12345678","Other unit contact");
    }

    @Test void disabledFaultRuleRoundtripPreservesTwoWechatLevelsAndItsIndependentInterval() throws Exception {
        String customer=notificationWriter(),appId="wx-transfer-fixture";
        jdbc.update("UPDATE notification_wechat_config SET app_id=? WHERE id=1",appId);
        String firstUser=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        JsonNode secondUser=body(call(HttpMethod.POST,"/api/management/users",root,"tenant-a",UUID.randomUUID().toString(),Map.of("username","wechat-transfer-second","name","Second isolated recipient","initial_password",IngestionIntegrationTest.INITIAL,"permissions",List.of("summary:read"))).andExpect(status().isOk()));
        JsonNode first=contact(customer,"First multi-channel contact","13800001005",binding(firstUser,appId,"one"));
        JsonNode second=contact(customer,"Second multi-channel contact","13800001006",binding(secondUser.get("id").asText(),appId,"two"));
        String receivers=json.writeValueAsString(List.of(
            Map.of("channel","SMS","contact_id",first.get("id").asText(),"level",1),
            Map.of("channel","VOICE","contact_id",second.get("id").asText(),"level",1),
            Map.of("channel","WECHAT","contact_id",first.get("id").asText(),"level",1),
            Map.of("channel","WECHAT","contact_id",second.get("id").asText(),"level",2)));
        JsonNode preview=customerPreview(customer,"UNIT_NOTIFICATION_RULES",List.of(
            List.of("","Independent fault channels","FAULT",receivers,"0","0","5","false"),
            List.of("","Cannot enable by import","FAULT",receivers,"0","0","5","true"),
            List.of("","Invalid Wechat interval","FAULT",receivers,"0","0","61","false")));
        assertThat(preview.get("valid_count").asInt()).isEqualTo(1);
        assertThat(preview.get("error_count").asInt()).isEqualTo(2);
        JsonNode originalJob=customerConfirm(customer,preview);
        assertThat(originalJob.get("success_count").asInt()).isEqualTo(1);
        String originalId=jdbc.queryForObject("SELECT id FROM notification_rules WHERE name='Independent fault channels'",String.class);
        assertThat(jdbc.queryForObject("SELECT wechat_escalation_interval_seconds FROM notification_rules WHERE id=?",Long.class,originalId)).isEqualTo(300);
        call(HttpMethod.POST,"/api/customer/notification-settings/rules",customer,null,UUID.randomUUID().toString(),
            Map.of("name","Device scoped draft","enabled",false,"event_scope","FAULT","scope_type","DEVICE",
                "scope_id","device-a","sms_escalation_interval_minutes",0,"voice_escalation_interval_minutes",0,
                "reason","Scoped rule must not be exported as a unit draft",
                "receivers",List.of(Map.of("channel","SMS","contact_id",first.get("id").asText(),"level",1))))
            .andExpect(status().isCreated());
        var exportInput=json.createObjectNode().put("kind","UNIT_NOTIFICATION_RULES").put("format","CSV").put("tenant_id","tenant-a").put("reason","Multi-channel roundtrip fixture");
        exportInput.set("filters",json.valueToTree(Map.of("unit_ids","tenant-a","place_ids","")));
        JsonNode exported=body(call(HttpMethod.POST,"/api/customer/data-transfer/exports",customer,null,UUID.randomUUID().toString(),exportInput).andExpect(status().isOk()));
        assertThat(exported.get("row_count").asInt()).isEqualTo(1);
        JsonNode file=body(call(HttpMethod.GET,"/api/customer/data-transfer/exports/"+exported.get("id").asText()+"/download",customer,null,null,null).andExpect(status().isOk()));
        JsonNode restoredPreview=body(call(HttpMethod.POST,"/api/customer/data-transfer/preview",customer,null,null,Map.of("kind","UNIT_NOTIFICATION_RULES","format","CSV","tenant_id","tenant-a","content_base64",file.get("content_base64").asText())).andExpect(status().isOk()));
        assertThat(restoredPreview.get("valid_count").asInt()).isEqualTo(1);
        JsonNode restoredJob=customerConfirm(customer,restoredPreview);
        assertThat(restoredJob.get("success_count").asInt()).isEqualTo(1);
        var rules=jdbc.queryForList("SELECT id,enabled,event_scope,wechat_escalation_interval_seconds FROM notification_rules WHERE name='Independent fault channels'");
        assertThat(rules).hasSize(2);
        for(var rule:rules) {
            assertThat(rule.get("enabled")).isEqualTo(false);
            assertThat(rule.get("event_scope")).isEqualTo("FAULT");
            assertThat(((Number)rule.get("wechat_escalation_interval_seconds")).longValue()).isEqualTo(300);
            assertThat(jdbc.queryForList("SELECT contact_id FROM notification_rule_receivers WHERE rule_id=? AND channel='WECHAT' ORDER BY level",String.class,rule.get("id"))).containsExactly(first.get("id").asText(),second.get("id").asText());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts",Long.class)).isZero();
    }

    private String notificationWriter() throws Exception {
        String token=customerWriter(),user=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        for(String permission:List.of("unit-notifications:read","unit-notifications:write"))
            jdbc.update("INSERT INTO user_permissions(user_id,permission) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM user_permissions WHERE user_id=? AND permission=?)",user,permission,user,permission);
        return token;
    }

    private JsonNode contact(String token,String name,String phone,String binding) throws Exception {
        var input=json.createObjectNode().put("name",name).put("contact_type","DUTY_OFFICER").put("phone",phone).put("enabled",true).put("reason","Isolated transfer fixture, no sends");
        if(binding!=null)input.put("wechat_binding_id",binding);
        return body(call(HttpMethod.POST,"/api/customer/notification-settings/contacts",token,null,UUID.randomUUID().toString(),input).andExpect(status().isCreated()));
    }

    private String binding(String user,String appId,String suffix) {
        // Isolated database fixture only; this is not OAuth or a real subscriber acceptance claim.
        String id="transfer-binding-"+suffix,openid="fixture-openid-"+suffix;
        var secret=wechatSecrets.protect(openid);
        jdbc.update("INSERT INTO notification_wechat_bindings(id,tenant_id,user_id,app_id,openid_cipher,openid_nonce,openid_masked,openid_fingerprint,status,active_slot,follow_state,revision,bound_at,last_seen_at) VALUES (?,'tenant-a',?,?,?,?,?,?,'ACTIVE','ACTIVE','SUBSCRIBED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,user,appId,secret.cipher(),secret.nonce(),secret.masked(),com.fireplatform.notification.officialaccount.WechatOfficialAccountBindingService.openidFingerprint(appId,openid));
        return id;
    }

    private String customerWriter() throws Exception {
        String token=login("customer-a",IngestionIntegrationTest.INITIAL,"customer");
        call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",IngestionIntegrationTest.INITIAL,"new_password",IngestionIntegrationTest.PASSWORD)).andExpect(status().isNoContent());
        token=login("customer-a",IngestionIntegrationTest.PASSWORD,"customer");
        String user=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        for(String permission:List.of("imports:read","imports:write","exports:read","exports:write","units:read","units:write","device:read","device:write"))
            jdbc.update("INSERT INTO user_permissions(user_id,permission) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM user_permissions WHERE user_id=? AND permission=?)",user,permission,user,permission);
        return token;
    }

    private JsonNode customerPreview(String token,String kind,List<List<String>> rows) throws Exception {
        JsonNode template=body(call(HttpMethod.GET,"/api/customer/data-transfer/template?kind="+kind+"&format=CSV",token,null,null,null).andExpect(status().isOk()));
        StringBuilder csv=new StringBuilder(new String(Base64.getDecoder().decode(template.get("content_base64").asText()),StandardCharsets.UTF_8));
        for(List<String> row:rows)csv.append(row.stream().map(DataTransferApiIntegrationTest::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        return body(call(HttpMethod.POST,"/api/customer/data-transfer/preview",token,null,null,Map.of("kind",kind,"format","CSV","tenant_id","tenant-a","content_base64",Base64.getEncoder().encodeToString(csv.toString().getBytes(StandardCharsets.UTF_8)))).andExpect(status().isOk()));
    }

    private Map<String,String> customerConfirmRequest(JsonNode preview) {
        return Map.of("plan_id",preview.get("plan_id").asText(),"expected_sha256",preview.get("source_sha256").asText(),
            "tenant_id","tenant-a","reason","Own-unit import verification");
    }

    private JsonNode customerConfirm(String token,JsonNode preview) throws Exception {
        return customerConfirm(token,preview,"customer-confirm-"+UUID.randomUUID());
    }

    private JsonNode customerConfirm(String token,JsonNode preview,String key) throws Exception {
        JsonNode first=body(call(HttpMethod.POST,"/api/customer/data-transfer/confirm",token,null,key,customerConfirmRequest(preview)).andExpect(status().isOk()));
        String state=first.get("status").asText();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while(List.of("QUEUED","RUNNING").contains(state)&&System.nanoTime()<deadline){Thread.sleep(25);state=jdbc.queryForObject("SELECT status FROM data_transfer_jobs WHERE id=?",String.class,first.get("id").asText());}
        assertThat(state).isNotIn("QUEUED","RUNNING");
        return body(call(HttpMethod.GET,"/api/customer/data-transfer/jobs/"+first.get("id").asText(),token,null,null,null).andExpect(status().isOk()));
    }

    private String login(String name,String password,String audience)throws Exception{return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText();}

    private ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input)throws Exception{var builder=request(method,path);if(token!=null)builder.header("Authorization","Bearer "+token);if(tenant!=null)builder.header("X-Tenant-Id",tenant);if(key!=null)builder.header("Idempotency-Key",key);if(input!=null)builder.contentType("application/json").content(json.writeValueAsString(input));return mvc.perform(builder);}
    private JsonNode body(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString());}
    private static String csvCell(String value){return "\""+value.replace("\"","\"\"")+"\"";}
}
