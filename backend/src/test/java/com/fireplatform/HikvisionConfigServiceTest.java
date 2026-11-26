package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import com.fireplatform.hikvision.HikvisionConfigInputs;
import com.fireplatform.hikvision.HikvisionConfigSecrets;
import com.fireplatform.hikvision.HikvisionConfigService;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.hikvision.HikvisionClient;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties={"app.roles=api","app.hikvision-config-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","fire.hikvision.enabled=false","fire.hikvision.app-key=environment-key","fire.hikvision.app-secret=environment-secret","spring.datasource.url=jdbc:h2:mem:hikvisionconfig;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@TestMethodOrder(OrderAnnotation.class)
class HikvisionConfigServiceTest {
    @Autowired HikvisionConfigService configuration;
    @Autowired HikvisionConfigSecrets secrets;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AuditService audit;
    @Autowired IdempotencyStore idem;
    @Autowired Clock clock;
    @Autowired TransactionTemplate transactions;
    @MockitoBean ManagementGuard guard;
    private AuthService.Identity actor;

    @BeforeEach void setUp() {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) VALUES(?,?,?,'PLATFORM',?,FALSE,TRUE)",id,id,"Config test","unused-config-test");
        actor=new AuthService.Identity(new AuthService.UserView(id,id,"Config test","PLATFORM",null,false),List.of("integrations:write"),"management","fixture-token");
        doNothing().when(guard).begin(any(),any(),any(),any());
    }
    @Test @Order(1) void existingEnvironmentCredentialsCanBeMaskedAndMigratedWithoutReentry() {
        var environment=configuration.get();
        assertThat(environment.configurationSource()).isEqualTo("ENVIRONMENT");
        assertThat(environment.registrationUnitId()).isNull();
        assertThat(environment.secretConfigured()).isTrue();
        assertThat(environment.endpoint()).isNull();
        assertThat(environment.appKeyMasked()).isNull();
        var migrated=configuration.update(actor,"hikvision-env-migration",input(0,"",""),"request-env");
        assertThat(migrated.configurationSource()).isEqualTo("DATABASE");
        assertThat(migrated.appKeyMasked()).isEqualTo("****-key");
        assertThat(configuration.get().registrationUnitId()).isEqualTo("fixture-registration-unit");
        assertThat(configuration.refreshRuntime().appSecret()).isEqualTo("environment-secret");
    }

    @Test @Order(2) void savedCredentialIsMaskedRetainedAndIdempotentlyReplayedWhileStaleCasConflicts() {
        var first=configuration.update(actor,"hikvision-save-1",input(1,"fixture-key","fixture-secret"),"request-1");
        assertThat(first.appKeyMasked()).isEqualTo("****-key");
        var replay=configuration.update(actor,"hikvision-save-1",input(1,"fixture-key","fixture-secret"),"request-2");
        assertThat(replay).isEqualTo(first);
        var stored=jdbc.queryForMap("SELECT app_key,app_key_masked,secret_cipher,secret_nonce,revision FROM hikvision_config WHERE id=1");
        assertThat(stored.get("secret_cipher").toString()).doesNotContain("fixture-secret");
        assertThat(stored.get("revision")).isEqualTo(2L);
        assertThat(configuration.get().appKeyMasked()).isEqualTo("****-key");
        assertThatThrownBy(()->configuration.update(actor,"hikvision-save-stale",input(1,"fixture-key","fixture-secret"),"request-3"))
            .hasMessageContaining("配置已更新");
        var retained=configuration.update(actor,"hikvision-save-retain",input(2,"",""),"request-4");
        assertThat(retained.appKeyMasked()).isEqualTo("****-key");
        assertThat(configuration.refreshRuntime().appSecret()).isEqualTo("fixture-secret");
        assertThat(configuration.get().registrationUnitId()).isEqualTo("fixture-registration-unit");
    }

    @Test @Order(3) void refreshedInstanceLoadsCommittedDatabaseRevisionIntoItsRuntimeSnapshot() {
        var otherClient=new HikvisionClient(json,clock,"","");
        var otherInstance=new HikvisionConfigService(jdbc,otherClient,secrets,guard,audit,idem,clock,transactions,false,30000,"","");
        otherInstance.loadDatabaseConfiguration();
        var protectedSecret=secrets.protect("external-instance-secret");
        String fingerprint=secrets.fingerprint("https://open.hikfirecloud.com\nexternal-instance-key");
        assertThat(jdbc.update("UPDATE hikvision_config SET endpoint=?,app_key=?,app_key_masked=?,secret_cipher=?,secret_nonce=?,account_fingerprint=?,enabled=?,poll_interval_seconds=?,model_scope=?,registration_unit_id=?,revision=9,updated_at=?,updated_by=? WHERE id=1",
            "https://open.hikfirecloud.com","external-instance-key","****-key",protectedSecret.cipher(),protectedSecret.nonce(),fingerprint,true,47,"800235","second-unit",OffsetDateTime.now(),actor.user().id())).isEqualTo(1);
        var snapshot=otherInstance.refreshRuntime();
        assertThat(snapshot.revision()).isEqualTo(9);
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.pollIntervalSeconds()).isEqualTo(47);
        assertThat(snapshot.appSecret()).isEqualTo("external-instance-secret");
        assertThat(snapshot.session().configured()).isTrue();
        assertThat(snapshot.registrationUnitId()).isEqualTo("second-unit");
    }

    private HikvisionConfigInputs.Write input(long revision,String appKey,String appSecret) {
        return new HikvisionConfigInputs.Write(revision,"https://open.hikfirecloud.com",appKey,appSecret,false,30,"800235"," fixture-registration-unit ");
    }
}
