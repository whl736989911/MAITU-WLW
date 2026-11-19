package com.fireplatform;

import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.bulkio.ConfigurationDataTransferDomain;
import com.fireplatform.configuration.*;
import com.fireplatform.identity.AuthService;
import com.fireplatform.product.ProductQueries;
import com.fireplatform.shared.ApiError;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.roles=api,ingest","app.storage=h2-dev","APP_GENERIC_INGEST_ENABLED=true","spring.datasource.url=jdbc:h2:mem:configurationtransfer;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class ConfigurationDataTransferDomainTest {
    private static final String INITIAL="Configuration-Transfer-Initial-Password";
    private static final String PASSWORD="Configuration-Transfer-Changed-Password";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) { r.add("APP_BOOTSTRAP_USERNAME",()->"admin"); r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL); }
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired AuthService auth;
    @Autowired ConfigurationDataTransferDomain transfer;
    @Autowired ProductQueries productQueries;
    @Autowired ParameterCommands parameterCommands;
    @Autowired ParameterQueries parameterQueries;
    private AuthService.Identity admin;

    @BeforeEach void initialize() {
        jdbc.update("DELETE FROM product_versions");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM identity_command_records");
        jdbc.update("DELETE FROM parameter_changes");
        jdbc.update("DELETE FROM parameter_overrides");
        N04TestScopes.clear(jdbc);
        jdbc.update("DELETE FROM user_tenant_grants WHERE tenant_id='transfer-unit'");
        jdbc.update("DELETE FROM tenants WHERE id='transfer-unit'");
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('transfer-unit','Transfer unit')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','transfer-unit')");
        N04TestScopes.grantExisting(jdbc);
        boolean initial=jdbc.queryForObject("SELECT must_change_password FROM app_users WHERE id='bootstrap-admin'",Boolean.class);
        var login=auth.login(new AuthService.LoginRequest("admin",initial?INITIAL:PASSWORD,"management"),"test");
        if(initial)auth.changePassword(auth.authenticate(login.accessToken()),new AuthService.ChangePasswordRequest(INITIAL,PASSWORD),"test");
        admin=auth.authenticate(auth.login(new AuthService.LoginRequest("admin",PASSWORD,"management"),"test").accessToken());
    }

    @Test void productImportCreatesVersionThatRemainsDraft() {
        String productId=transfer.apply("PRODUCT_DRAFTS",Map.of("operation","CREATE_PRODUCT","name","Transfer product","manufacturer","Test","model","draft-only","description","Only a configuration draft"),admin,null,"test","job-product",1);
        String versionId=transfer.apply("PRODUCT_DRAFTS",Map.of("operation","CREATE_VERSION","product_id",productId),admin,null,"test","job-product",2);
        assertThat(productQueries.version(versionId,false).status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_versions WHERE id=? AND status='PUBLISHED'",Integer.class,versionId)).isZero();
        assertThat(transfer.export("PRODUCT_DRAFTS",admin,null,Map.of(),null).rows()).hasSize(1);
        assertThat(transfer.export("PRODUCT_DRAFTS",admin,null,Map.of(),List.of()).rows()).isEmpty();
    }

    @Test void stalePolicyDraftCannotApplyAndLeavesCurrentRevisionUntouched() {
        parameterCommands.set(admin,"transfer-unit","TENANT","transfer-unit","offline_threshold_minutes","seed-policy",new ParameterInputs.SetValue(12L,0L,"seed override"),"test");
        var stale=Map.of("operation","SET","scope","TENANT","scope_id","transfer-unit","parameter_key","offline_threshold_minutes","value","20","expected_revision","0");
        assertThatThrownBy(()->transfer.apply("POLICY_DRAFTS",stale,admin,"transfer-unit","draft confirmed","job-policy",1)).isInstanceOf(ApiError.class);
        assertThat(parameterQueries.view(admin.user().id(),"transfer-unit","TENANT","transfer-unit","offline_threshold_minutes").revision()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parameter_changes WHERE tenant_id='transfer-unit'",Integer.class)).isEqualTo(1);
    }

    @Test void customerNotificationExportRejectsAnotherTenantBeforeReadingSettings() {
        var customer=new AuthService.Identity(new AuthService.UserView("customer-a","customer-a","Unit admin","CUSTOMER","transfer-unit",false),List.of("unit-notifications:read"),"customer","0".repeat(64));
        assertThatThrownBy(()->transfer.export("UNIT_CONTACTS",customer,"another-unit",Map.of(),null)).isInstanceOf(ApiError.class);
    }
}
