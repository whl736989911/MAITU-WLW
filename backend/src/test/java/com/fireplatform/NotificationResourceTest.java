package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.notification.NotificationProviderAttemptService;
import com.fireplatform.notification.NotificationProviderService;
import com.fireplatform.notification.NotificationResourceService;
import com.fireplatform.notification.PaymentOrderService;
import com.fireplatform.notification.PaymentSecretStorage;
import com.fireplatform.shared.ApiError;
import com.fireplatform.transport.WechatPayV2Client;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","WECHAT_PAY_MCH_ID=","WECHAT_PAY_APP_ID=","WECHAT_PAY_API_V2_KEY=","spring.datasource.url=jdbc:h2:mem:notification-resourcetests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class NotificationResourceTest {
    static final String INITIAL="Initial-"+UUID.randomUUID(), PASSWORD="Changed-"+UUID.randomUUID();
    static final String TEST_MCH="1900000109", TEST_APP="wx1234567890abcdef", TEST_KEY="0123456789abcdef0123456789abcdef";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); r.add("APP_NOTIFICATION_CONTACT_KEY",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired NotificationResourceService resources; @Autowired NotificationProviderAttemptService attempts; @Autowired NotificationProviderService providers; @Autowired PaymentOrderService payments; @Autowired PaymentSecretStorage paymentSecrets;
    @Autowired com.fireplatform.transport.AliyunNotificationClient notificationClient;
    @Autowired PlatformTransactionManager transactions;
    String root;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        for(String table:List.of("payment_reconciliation_differences","payment_reconciliation_runs","payment_paid_transactions","notification_provider_attempts","notification_resource_orders","notification_resource_ledger","notification_resource_accounts","notification_recharge_grants","notification_recharge_policy","device_suppression_members","device_suppressions","reparse_attempts","reparse_jobs","message_outbox","device_message_watermark","device_task_applications","processing_attempts","processing_tasks","notification_automation_intents","alarm_trace_entries","message_previews","raw_messages","active_source_bindings","source_bindings","ingestion_sources", "device_place_history", "device_version_history", "device_profile_snapshots","profile_rules","device_battery_observations","device_events","alarms","devices","places","product_versions","products","identity_command_records","idempotency_records","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        resetVerifiedMerchant();
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant-a','Notification test tenant A')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES ('device-a','tenant-a','Notification fixture A','n09-a','fixture','A','NORMAL',FALSE,'LEGACY_UNVERIFIED')");
        N04TestScopes.grantExisting(jdbc); root=login("admin",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        root=login("admin",PASSWORD,"management");
        call(HttpMethod.POST,"/api/management/users",root,"tenant-a",UUID.randomUUID().toString(),
            Map.of("username","customer-a","name","Notification fixture customer","initial_password",INITIAL,"permissions",com.fireplatform.shared.Limits.CUSTOMER_PERMISSIONS))
            .andExpect(status().isOk());
    }
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input) throws Exception { var builder=request(method,path); if(token!=null) builder.header("Authorization","Bearer "+token); if(tenant!=null) builder.header("X-Tenant-Id",tenant); if(key!=null) builder.header("Idempotency-Key",key); if(input!=null) builder.contentType("application/json").content(json.writeValueAsString(input)); return mvc.perform(builder); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    String login(String name,String password,String audience) throws Exception { return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",name,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText(); }
    Map<String,Object> org(String channel,long quantity){ return Map.of("owner_type","ORGANIZATION","owner_id","tenant-a","channel",channel,"quantity",quantity,"reference","N09 资源测试"); }
    Map<String,Object> providerBody(){ var m=new java.util.HashMap<String,Object>(); m.put("sms_enabled",false); m.put("voice_enabled",false); m.put("sms_sign_name","测试签名"); m.put("sms_template_code","SMS_TEST"); m.put("tts_code","TTS_TEST"); m.put("tts_called_show_number",null); m.put("sms_template_variables",Map.of("deviceName","device_name")); m.put("voice_template_variables",Map.of()); m.put("sms_endpoint","https://dysmsapi.aliyuncs.com/"); m.put("voice_endpoint","https://dyvmsapi.aliyuncs.com/"); m.put("access_key_id",null); m.put("access_key_secret",null); m.put("test_mode_enabled",true); m.put("test_phone","18896992408"); m.put("expected_revision",jdbc.queryForObject("SELECT revision FROM notification_provider_config WHERE id=1",Long.class)); return m; }
    Map<String,Object> order(String channel,long quantity){ return Map.of("owner_type","ORGANIZATION","owner_id","tenant-a","channel",channel,"quantity",quantity); }

    @Test void senderUsesOnlyCommittedProviderSettings() throws Exception {
        call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,providerBody()).andExpect(status().isOk());
        var baseline=providers.get();
        var input=providerBody();
        input.put("access_key_id","isolated-notification-ak");
        input.put("access_key_secret","isolated-notification-secret");
        input.put("sms_enabled",true);
        var transaction=new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            try {
                call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,input).andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(notificationClient.enabled()).isFalse();
            throw new IllegalStateException("roll back provider update");
        })).isInstanceOf(IllegalStateException.class).hasMessage("roll back provider update");
        assertThat(providers.get()).isEqualTo(baseline);
        assertThat(notificationClient.enabled()).isFalse();

        try {
            call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,input).andExpect(status().isOk());
            assertThat(notificationClient.enabled()).isTrue();
        } finally {
            call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,providerBody()).andExpect(status().isOk());
        }
    }

    @Test void missingOrderReturnsNotFoundInsteadOfStorageFailure() throws Exception {
        call(HttpMethod.GET,"/api/management/notification-resources/orders/"+UUID.randomUUID(),root,"tenant-a",null,null).andExpect(status().isNotFound());
    }

    @Test void manualRechargeAllowsEmptyReference() throws Exception {
        var body=body(call(HttpMethod.POST,"/api/management/notification-resources/recharge",root,"tenant-a",UUID.randomUUID().toString(),Map.of("owner_type","ORGANIZATION","owner_id","tenant-a","channel","SMS","quantity",1)).andExpect(status().isOk()));
        assertThat(body.get("sms_total").asLong()).isEqualTo(1);
    }

    @Test void pricingRechargeOrderAndSendReservationAreTransactional() throws Exception {
        call(HttpMethod.GET,"/api/management/summary",root,null,null,null).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/management/devices?page=1",root,null,null,null).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/management/alarms?page=1",root,null,null,null).andExpect(status().isOk());
        call(HttpMethod.GET,"/api/management/ops-overview",root,null,null,null).andExpect(status().isOk());
        var pricing=body(call(HttpMethod.PUT,"/api/management/notification-resources/pricing",root,"tenant-a",null,Map.of("sms_price_cents",150,"voice_price_cents",200,"expected_revision",resources.pricing().revision())).andExpect(status().isOk()));
        assertThat(pricing.get("sms_price_cents").asLong()).isEqualTo(150);
        var account=body(call(HttpMethod.POST,"/api/management/notification-resources/recharge",root,"tenant-a",UUID.randomUUID().toString(),org("SMS",10)).andExpect(status().isOk()));
        assertThat(account.get("sms_total").asLong()).isEqualTo(10);
        var order=body(call(HttpMethod.POST,"/api/management/notification-resources/orders",root,"tenant-a",UUID.randomUUID().toString(),order("SMS",5)).andExpect(status().isOk()));
        assertThat(order.get("amount_cents").asLong()).isEqualTo(750);
        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        String orderId=order.get("id").asText();
        var paid=body(call(HttpMethod.POST,"/api/management/notification-resources/orders/"+orderId+"/confirm",root,"tenant-a",null,Map.of("reference","线下到账")).andExpect(status().isOk()));
        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT sms_total FROM notification_resource_accounts WHERE tenant_id='tenant-a' AND owner_type='ORGANIZATION'",Long.class)).isEqualTo(15);
        var reservation=resources.reserve("tenant-a","device-a","SMS");
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE id=?",Long.class,reservation.accountId())).isEqualTo(1);
        resources.release(reservation,"provider-failed");
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE id=?",Long.class,reservation.accountId())).isZero();
        call(HttpMethod.POST,"/api/management/notification-resources/orders",root,"tenant-a",UUID.randomUUID().toString(),order("VOICE",1)).andExpect(status().isOk()).andExpect(jsonPath("$.amount_cents").value(200));
        var configured=body(call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,providerBody()).andExpect(status().isOk()));
        assertThat(configured.get("test_mode_enabled").asBoolean()).isTrue();
        assertThat(configured.get("test_phone_masked").asText()).endsWith("2408");
        assertThat(providers.testRecipient()).isEqualTo("+8618896992408");
        var attempt=attempts.begin("tenant-a","FIRE","ALARM","alarm-a","device-a","contact-a","SMS","INITIAL",1,null,null);
        assertThat(attempts.latest("tenant-a","FIRE","ALARM","alarm-a","contact-a","SMS","INITIAL").status()).isEqualTo("PENDING");
        attempts.finish(attempt.id(),"SENT","OK","provider-1");
        assertThat(attempts.latest("tenant-a","FIRE","ALARM","alarm-a","contact-a","SMS","INITIAL").status()).isEqualTo("SENT");
        var second=attempts.begin("tenant-a","FIRE","ALARM","alarm-a","device-a","contact-a","SMS","INITIAL",2,null,null);
        attempts.finish(second.id(),"UNKNOWN","PROVIDER_EXCEPTION",null);
        assertThat(attempts.latest("tenant-a","FIRE","ALARM","alarm-a","contact-a","SMS","INITIAL").attemptNo()).isEqualTo(2);
        assertThat(attempts.list("tenant-a",1,20).total()).isEqualTo(2);
    }
    @Test void reserveDebitsNearestPlaceThenAncestorsThenUnitIndependentlyPerChannel() throws Exception {
        String device = hierarchicalDevice();
        String unit = recharge(null, "SMS", 1);
        String ancestor = recharge("n09-root", "SMS", 1);
        String parent = recharge("n09-mid", "VOICE", 1);
        String nearest = recharge("n09-leaf", "SMS", 1);

        assertThat(resources.reserve("tenant-a", device, "SMS").accountId()).isEqualTo(nearest);
        assertThat(resources.reserve("tenant-a", device, "VOICE").accountId()).isEqualTo(parent);
        assertThat(resources.reserve("tenant-a", device, "SMS").accountId()).isEqualTo(ancestor);
        assertThat(resources.reserve("tenant-a", device, "SMS").accountId()).isEqualTo(unit);
        assertThatThrownBy(() -> resources.reserve("tenant-a", device, "SMS"))
            .isInstanceOfSatisfying(ApiError.class, error -> assertThat(error.code).isEqualTo("NOTIFICATION_RESOURCE_UNAVAILABLE"));
        assertThatThrownBy(() -> resources.reserve("tenant-a", device, "VOICE"))
            .isInstanceOfSatisfying(ApiError.class, error -> assertThat(error.code).isEqualTo("NOTIFICATION_RESOURCE_UNAVAILABLE"));
        assertThat(jdbc.queryForList("SELECT account_id FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND channel='SMS' AND kind='CONSUME' AND quantity=1", String.class))
            .containsExactlyInAnyOrder(nearest, ancestor, unit);
        assertThat(jdbc.queryForList("SELECT account_id FROM notification_resource_ledger WHERE tenant_id='tenant-a' AND channel='VOICE' AND kind='CONSUME' AND quantity=1", String.class))
            .containsExactly(parent);
    }

    @Test void concurrentReservationsForTheLastPlaceDebitFallBackWithoutOverdrawing() throws Exception {
        String device = hierarchicalDevice();
        String ancestor = recharge("n09-root", "SMS", 1);
        String unit = recharge(null, "SMS", 1);
        var start = new CyclicBarrier(2);
        Callable<String> reserve = () -> {
            start.await(10, TimeUnit.SECONDS);
            return resources.reserve("tenant-a", device, "SMS").accountId();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(reserve);
            var second = pool.submit(reserve);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(ancestor, unit);
        }
        assertThat(jdbc.queryForList("SELECT sms_used FROM notification_resource_accounts WHERE tenant_id='tenant-a'", Long.class))
            .containsExactlyInAnyOrder(1L, 1L);
    }

    @Test void reserveSkipsAnArchivedAncestorWithAnExistingBalance() throws Exception {
        String device = hierarchicalDevice();
        String unit = recharge(null, "SMS", 1);
        String retired = recharge("n09-root", "SMS", 1);
        jdbc.update("UPDATE places SET archived_at=CURRENT_TIMESTAMP WHERE id='n09-root' AND tenant_id='tenant-a'");

        assertThat(resources.reserve("tenant-a", device, "SMS").accountId()).isEqualTo(unit);
        assertThat(jdbc.queryForObject("SELECT sms_used FROM notification_resource_accounts WHERE id=?", Long.class, retired))
            .isZero();
    }

    private String hierarchicalDevice() {
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id) VALUES ('n09-root','tenant-a','Root',NULL),('n09-mid','tenant-a','Mid','n09-root'),('n09-leaf','tenant-a','Leaf','n09-mid')");
        jdbc.update("UPDATE devices SET place_id='n09-leaf' WHERE id='device-a' AND tenant_id='tenant-a'");
        return "device-a";
    }

    private String recharge(String placeId, String channel, long quantity) throws Exception {
        var input = placeId == null ? org(channel, quantity)
            : Map.of("owner_type", "PLACE", "owner_id", placeId, "channel", channel, "quantity", quantity);
        return body(call(HttpMethod.POST, "/api/management/notification-resources/recharge", root, "tenant-a",
            UUID.randomUUID().toString(), input).andExpect(status().isOk())).get("id").asText();
    }
    private String customerA() throws Exception {
        String token=login("customer-a",INITIAL,"customer");
        call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        return login("customer-a",PASSWORD,"customer");
    }
    private void grantsMode() throws Exception {
        jdbc.update("DELETE FROM notification_recharge_grants WHERE tenant_id='tenant-a'");
        jdbc.update("DELETE FROM notification_recharge_policy WHERE tenant_id='tenant-a'");
        call(HttpMethod.PUT,"/api/management/notification-recharge/policy",root,"tenant-a",null,Map.of("mode","GRANTS","expected_revision",0)).andExpect(status().isOk());
    }
    private String grantPlace(String placeId) throws Exception {
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES (?,?,?)",placeId,"tenant-a","Notification grant fixture");
        String customerId=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        call(HttpMethod.POST,"/api/management/notification-recharge/grants",root,"tenant-a",null,Map.of("user_id",customerId,"owner_type","PLACE","owner_id",placeId)).andExpect(status().isOk());
        return customerId;
    }
    private String otherCustomer() throws Exception {
        String id="notify-customer-"+UUID.randomUUID(), username="notify-user-"+UUID.randomUUID();
        String source=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled) SELECT ?,?,?,user_type,'tenant-a',password_hash,must_change_password,enabled FROM app_users WHERE id=?",id,username,"Second fixture customer",source);
        jdbc.update("INSERT INTO user_permissions(user_id,permission) SELECT ?,permission FROM user_permissions WHERE user_id=?",id,source);
        return login(username,PASSWORD,"customer");
    }
    private void seedGrant(String placeId,String userId) throws Exception {
        jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES (?,?,?)",placeId,"tenant-a","Notification grant fixture");
        jdbc.update("INSERT INTO notification_recharge_grants(tenant_id,user_id,owner_type,owner_id,created_at,created_by) VALUES ('tenant-a',?,'PLACE',?,CURRENT_TIMESTAMP,'bootstrap-admin')",userId,placeId);
    }
    @Test void grantCreationUsesTheActualCustomerDisplayNameColumn() throws Exception {
        grantsMode(); String placeId="notify-grant-"+UUID.randomUUID(); String customerId=grantPlace(placeId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_recharge_grants WHERE tenant_id='tenant-a' AND user_id=? AND owner_id=?",Long.class,customerId,placeId)).isEqualTo(1);
    }
    @Test void customerGrantOptionsAndOrdersAreScopedToTheGrantOwner() throws Exception {
        grantsMode(); String placeId="notify-grant-"+UUID.randomUUID(); String customerId=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        seedGrant(placeId,customerId);
        var owner=customerA(); var other=otherCustomer();
        call(HttpMethod.GET,"/api/customer/notification-recharge/options",owner,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.destinations[0].owner_id").value(placeId));
        call(HttpMethod.GET,"/api/customer/notification-recharge/options",other,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.destinations").isEmpty());
        var current=resources.pricing();
        call(HttpMethod.PUT,"/api/management/notification-resources/pricing",root,"tenant-a",null,Map.of("sms_price_cents",10,"voice_price_cents",10,"expected_revision",current.revision())).andExpect(status().isOk());
        var orderInput=Map.of("owner_type","PLACE","owner_id",placeId,"channel","SMS","quantity",1);
        call(HttpMethod.POST,"/api/customer/notification-recharge/orders",other,null,UUID.randomUUID().toString(),orderInput).andExpect(status().isForbidden());
        call(HttpMethod.POST,"/api/customer/notification-recharge/orders",owner,null,UUID.randomUUID().toString(),orderInput).andExpect(status().isOk());
    }
    @Test void stalePlaceGrantIsNotAnOrderDestination() throws Exception {
        grantsMode(); String placeId="notify-gone-"+UUID.randomUUID(); String customerId=jdbc.queryForObject("SELECT id FROM app_users WHERE username='customer-a'",String.class);
        seedGrant(placeId,customerId); jdbc.update("DELETE FROM places WHERE id=?",placeId);
        var token=customerA();
        call(HttpMethod.GET,"/api/customer/notification-recharge/options",token,null,null,null).andExpect(status().isOk()).andExpect(jsonPath("$.destinations").isEmpty());
        call(HttpMethod.POST,"/api/customer/notification-recharge/orders",token,null,UUID.randomUUID().toString(),Map.of("owner_type","PLACE","owner_id",placeId,"channel","SMS","quantity",1)).andExpect(status().isForbidden());
    }
    @Test void providerSwitchesDisableEachSenderWithoutErasingTemplatesOrHistoricalReceiptCredentials() throws Exception {
        var input = providerBody();
        input.put("access_key_id", "isolated-notification-ak");
        input.put("access_key_secret", "isolated-notification-secret");
        input.put("sms_enabled", true);
        input.put("voice_enabled", true);
        call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,input).andExpect(status().isOk());
        assertThat(notificationClient.enabled()).isTrue();
        assertThat(notificationClient.voiceReady()).isTrue();

        input = providerBody();
        input.put("voice_enabled", true);
        call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,input).andExpect(status().isOk());
        assertThat(notificationClient.enabled()).isFalse();
        assertThat(notificationClient.voiceReady()).isTrue();

        call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,providerBody()).andExpect(status().isOk());
        assertThat(notificationClient.enabled()).isFalse();
        assertThat(notificationClient.voiceReady()).isFalse();
        assertThat(notificationClient.queryReady()).isTrue();
        assertThat(providers.get().smsTemplateCode()).isEqualTo("SMS_TEST");
        assertThat(providers.get().ttsCode()).isEqualTo("TTS_TEST");

        input = providerBody();
        input.put("sms_enabled", true);
        call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,input).andExpect(status().isOk());
        assertThat(notificationClient.enabled()).isTrue();
        assertThat(notificationClient.voiceReady()).isFalse();
    }

    @Test void malformedHttpsEndpointIsRejectedWithoutUpdatingProviderConfiguration() throws Exception {
        Long revision=jdbc.queryForObject("SELECT revision FROM notification_provider_config WHERE id=1",Long.class);
        var input=new java.util.HashMap<>(providerBody()); input.put("sms_endpoint","https://");
        call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,input).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT revision FROM notification_provider_config WHERE id=1",Long.class)).isEqualTo(revision);
    }
    @Test void validatesTemplateMappingsAndPersistsEmptyMapDistinctFromNotConfigured() throws Exception {
        var input=providerBody();
        input.put("sms_enabled",true);
        input.remove("sms_template_variables");
        call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,input).andExpect(status().isBadRequest());
        for (Object invalid : List.of(Map.of("device",17), Map.of("device","phone"), Map.of("bad-variable","device_name"), List.of("device_name"))) {
            input.put("sms_template_variables",invalid);
            call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,input).andExpect(status().isBadRequest());
        }
        input.put("sms_template_variables", Map.of("device","device_name"));
        call(HttpMethod.PUT,"/api/management/notification-provider",root,null,null,input).andExpect(status().isOk());
        var saved=body(call(HttpMethod.GET,"/api/management/notification-provider",root,null,null,null).andExpect(status().isOk()));
        assertThat(saved.path("sms_template_variables").path("device").asText()).isEqualTo("device_name");
        assertThat(saved.path("voice_template_variables").isObject()).isTrue();
        assertThat(saved.path("voice_template_variables").size()).isZero();
    }
    @Test void callbackRejectsMissingTransactionAndMismatchedPaidReplay() throws Exception {
        var current=resources.pricing();
        call(HttpMethod.PUT,"/api/management/notification-resources/pricing",root,"tenant-a",null,Map.of("sms_price_cents",10,"voice_price_cents",10,"expected_revision",current.revision())).andExpect(status().isOk());
        // Both orders are created while no merchant is configured, so no outbound payment call can occur.
        var created=body(call(HttpMethod.POST,"/api/management/notification-resources/orders",root,"tenant-a",UUID.randomUUID().toString(),order("SMS",2)).andExpect(status().isOk()));
        var payment=jdbc.queryForMap("SELECT out_trade_no,amount_cents FROM notification_resource_orders WHERE id=?",created.get("id").asText());
        String trade=(String)payment.get("out_trade_no"); long amount=((Number)payment.get("amount_cents")).longValue();
        var manual=body(call(HttpMethod.POST,"/api/management/notification-resources/orders",root,"tenant-a",UUID.randomUUID().toString(),order("SMS",1)).andExpect(status().isOk()));
        String manualTrade=jdbc.queryForObject("SELECT out_trade_no FROM notification_resource_orders WHERE id=?",String.class,manual.get("id").asText());
        enableVerifiedMerchant();
        try {
            assertThat(payments.acceptCallback(wechatNotify(trade,amount,"",TEST_KEY)).acknowledged()).isFalse();
            assertThat(payments.acceptCallback(wechatNotify(trade,amount,"transaction-a",TEST_KEY)).acknowledged()).isTrue();
            assertThat(payments.acceptCallback(wechatNotify(trade,amount+1,"transaction-a",TEST_KEY)).acknowledged()).isFalse();
            assertThat(payments.acceptCallback(wechatNotify(trade,amount,"transaction-b",TEST_KEY)).acknowledged()).isFalse();
            assertThat(payments.acceptCallback(wechatNotify(trade,amount,"transaction-a",TEST_KEY)).acknowledged()).isTrue();
            assertThat(payments.acceptCallback(wechatNotify(trade,amount,"transaction-a","f".repeat(32))).acknowledged()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE kind='RECHARGE' AND reference=?",Long.class,trade)).isEqualTo(1);
            call(HttpMethod.POST,"/api/management/notification-resources/orders/"+manual.get("id").asText()+"/confirm",root,"tenant-a",null,Map.of("reference","manual payment")).andExpect(status().isOk());
            Long smsBefore=jdbc.queryForObject("SELECT sms_total FROM notification_resource_accounts WHERE tenant_id='tenant-a' AND owner_type='ORGANIZATION'",Long.class);
            var linked=payments.acceptCallback(wechatNotify(manualTrade,10,"later-wechat-callback",TEST_KEY));
            assertThat(linked.acknowledged()).isTrue();
            assertThat(linked.code()).isEqualTo("LINKED_EXISTING_PAYMENT");
            assertThat(jdbc.queryForObject("SELECT sms_total FROM notification_resource_accounts WHERE tenant_id='tenant-a' AND owner_type='ORGANIZATION'",Long.class)).isEqualTo(smsBefore);
            assertThat(jdbc.queryForObject("SELECT transaction_id FROM notification_resource_orders WHERE id=?",String.class,manual.get("id").asText())).isEqualTo("later-wechat-callback");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_paid_transactions WHERE transaction_id='later-wechat-callback'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE reference=?",Long.class,manualTrade)).isZero();
        } finally {
            resetVerifiedMerchant();
        }
    }
    private void enableVerifiedMerchant() {
        var protectedKey=paymentSecrets.protect(TEST_KEY);
        jdbc.update("UPDATE payment_wechat_config SET configured=TRUE,enabled=TRUE,mch_id=?,app_id=?,api_v2_key_cipher=?,api_v2_key_nonce=?,api_v2_key_masked=?,notify_url=?,credentials_revision=1,revision=1,updated_at=CURRENT_TIMESTAMP WHERE id=1",
            TEST_MCH,TEST_APP,protectedKey.cipher(),protectedKey.nonce(),protectedKey.masked(),"https://example.com/api/payments/wechat/native/notify");
    }
    private void resetVerifiedMerchant() {
        jdbc.update("DELETE FROM payment_wechat_key_history");
        jdbc.update("UPDATE payment_wechat_config SET configured=FALSE,enabled=FALSE,mch_id=NULL,app_id=NULL,api_v2_key_cipher=NULL,api_v2_key_nonce=NULL,api_v2_key_masked=NULL,notify_url='',order_expiry_minutes=NULL,auto_lifecycle_enabled=FALSE,auto_reconcile_enabled=FALSE,credentials_revision=0,revision=0,updated_at=NULL WHERE id=1");
    }
    private static Map<String,String> wechatNotify(String outTradeNo,long amount,String transactionId,String signerKey) {
        var params=new java.util.TreeMap<String,String>();
        params.put("return_code","SUCCESS"); params.put("result_code","SUCCESS");
        params.put("appid",TEST_APP); params.put("mch_id",TEST_MCH);
        params.put("out_trade_no",outTradeNo); params.put("total_fee",String.valueOf(amount));
        if(transactionId!=null && !transactionId.isEmpty()) params.put("transaction_id",transactionId);
        params.put("sign",WechatPayV2Client.sign(params,signerKey));
        return params;
    }
}
