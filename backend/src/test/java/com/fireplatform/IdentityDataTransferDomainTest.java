package com.fireplatform;

import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.bulkio.IdentityDataTransferDomain;
import com.fireplatform.identity.AuthService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:identity-transfer-domain;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class IdentityDataTransferDomainTest {
    private static final String INITIAL="Initial-IdentityTransfer-2026!", PASSWORD="Changed-IdentityTransfer-2026!";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME",()->"admin"); registry.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthService auth;
    @Autowired IdentityDataTransferDomain domain;
    @Autowired PlatformTransactionManager transactions;
    private AuthService.Identity actor;

    @BeforeEach void reset() {
        N04TestScopes.clear(jdbc);
        for(String table:List.of("identity_command_records","idempotency_records","identity_user_metadata","access_user_roles","access_role_permissions","access_roles","user_device_scope_devices","user_device_scopes","sessions","user_permissions","user_tenant_grants","app_users","tenants","audit_log")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES('tenant-a','Transfer A'),('tenant-b','Transfer B')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin','tenant-a'),('bootstrap-admin','tenant-b')");
        N04TestScopes.grantExisting(jdbc);
        var login=auth.login(new AuthService.LoginRequest("admin",INITIAL,"management"),"transfer-test-login");
        auth.changePassword(auth.authenticate(login.accessToken()),new AuthService.ChangePasswordRequest(INITIAL,PASSWORD),"transfer-test-password");
        var current=auth.login(new AuthService.LoginRequest("admin",PASSWORD,"management"),"transfer-test-login-current");
        actor=auth.authenticate(current.accessToken());
    }

    @Test void createIsDisabledAndSecretFreeThenCasReplaysAndTenantProtectedTargetsAreRejected() {
        var template=domain.kinds().stream().filter(k->k.kind().equals("USERS")).findFirst().orElseThrow();
        assertThat(template.importable()).isTrue(); assertThat(template.exportable()).isTrue();
        Map<String,String> onboarding=Map.of("username","transfer-customer","name","Transfer customer","permissions","device:read","permission_effect","REPLACE");
        var tx=new TransactionTemplate(transactions);
        String id=tx.execute(s->domain.apply("USERS",onboarding,actor,"tenant-a","transfer onboarding","onboarding-job",1));
        assertThat(jdbc.queryForObject("SELECT enabled FROM app_users WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM app_users WHERE id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT response_json FROM identity_command_records WHERE operation='customer-create' AND actor_id='bootstrap-admin'",String.class)).doesNotContain("Onboard-");
        Map<String,String> update=Map.of("id",id,"name","CAS updated","expected_revision","1");
        String replay=tx.execute(s->domain.apply("USERS",update,actor,"tenant-a","transfer update","cas-job",1));
        String replayAgain=tx.execute(s->domain.apply("USERS",update,actor,"tenant-a","transfer update","cas-job",1));
        assertThat(replayAgain).isEqualTo(replay);
        assertThat(jdbc.queryForObject("SELECT display_name FROM app_users WHERE id=?",String.class,id)).isEqualTo("CAS updated");
        Map<String,String> roleCreate=Map.of("code","transfer-role","name","Transfer role","user_type","CUSTOMER","enabled","true","permissions","device:read","reason","Regression role");
        String roleId=tx.execute(s->domain.apply("ROLES",roleCreate,actor,null,"Regression role","role-job",1));
        Map<String,String> roleAssignment=Map.of("id",id,"expected_revision","2","roles",roleId,"role_effect","ADD","reason","Regression assignment");
        tx.execute(s->domain.apply("USERS",roleAssignment,actor,"tenant-a","Regression assignment","role-assignment-job",1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_user_roles WHERE user_id=? AND role_id=?",Long.class,id,roleId)).isEqualTo(1L);
        Map<String,String> scopeAssignment=Map.of("id",id,"expected_revision","3","scope_effect","REPLACE","scope_tenant","tenant-a","scope_mode","DEVICE_SET","device_ids","","place_ids","","scope_revision","0");
        tx.execute(s->domain.apply("USERS",scopeAssignment,actor,"tenant-a","Regression scope","scope-assignment-job",1));
        var exported=domain.export("USERS",actor,null,Map.of("keyword","transfer-customer"),List.of(id));
        assertThat(exported.rows()).hasSize(1);
        assertThat(exported.rows().getFirst().get(exported.columns().indexOf("roles"))).isEqualTo(roleId);
        assertThat(exported.rows().getFirst().get(exported.columns().indexOf("scope_records"))).contains("tenant-a","DEVICE_SET");
        assertThat(domain.export("USERS",actor,null,Map.of("keyword","transfer-customer"),List.of()).rows()).isEmpty();

        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,revision,is_protected) VALUES('foreign-customer','foreign-customer','Foreign','CUSTOMER','tenant-b',?,TRUE,TRUE,0,FALSE)",encoder.encode(INITIAL));
        Map<String,String> crossTenant=Map.of("id","foreign-customer","name","Must not update","expected_revision","0");
        assertThatThrownBy(()->tx.execute(s->domain.apply("USERS",crossTenant,actor,"tenant-a","cross tenant","cross-job",1))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT display_name FROM app_users WHERE id='foreign-customer'",String.class)).isEqualTo("Foreign");
        assertThatThrownBy(()->domain.export("USERS",actor,"tenant-a",Map.of(),List.of("foreign-customer"))).isInstanceOf(RuntimeException.class);

        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,revision,is_protected) VALUES('protected-platform','protected-platform','Protected','PLATFORM',?,FALSE,TRUE,0,TRUE)",encoder.encode(INITIAL));
        Map<String,String> protectedRow=Map.of("id","protected-platform","name","No bypass","expected_revision","0");
        assertThatThrownBy(()->tx.execute(s->domain.apply("PLATFORM_USERS",protectedRow,actor,null,"protected target","protected-job",1))).isInstanceOf(RuntimeException.class);
        long selfRevision=jdbc.queryForObject("SELECT revision FROM app_users WHERE id='bootstrap-admin'",Long.class);
        String selfDisplayName=jdbc.queryForObject("SELECT display_name FROM app_users WHERE id='bootstrap-admin'",String.class);
        Map<String,String> selfRow=Map.of("id","bootstrap-admin","name","No self-management","expected_revision",Long.toString(selfRevision));
        assertThatThrownBy(()->tx.execute(s->domain.apply("PLATFORM_USERS",selfRow,actor,null,"self target","self-job",1))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT display_name FROM app_users WHERE id='bootstrap-admin'",String.class)).isEqualTo(selfDisplayName);
    }

    @Test void delegatedPermissionEscalationIsRejectedByExistingAccountCommand() {
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='alarm:read'");
        actor=auth.authenticate(auth.login(new AuthService.LoginRequest("admin",PASSWORD,"management"),"transfer-login-reloaded").accessToken());
        Map<String,String> escalation=Map.of("username","bad-escalation","name","Bad escalation","permissions","alarm:read","permission_effect","REPLACE");
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(s->domain.apply("USERS",escalation,actor,"tenant-a","escalation","escalation-job",1))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username='bad-escalation'",Long.class)).isZero();
    }
}
