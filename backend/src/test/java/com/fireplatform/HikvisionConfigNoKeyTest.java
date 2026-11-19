package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import com.fireplatform.hikvision.HikvisionConfigInputs;
import com.fireplatform.hikvision.HikvisionConfigService;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties={"app.roles=api","app.hikvision-config-key=","fire.hikvision.enabled=false","spring.datasource.url=jdbc:h2:mem:hikvisionnokey;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class HikvisionConfigNoKeyTest {
    @Autowired HikvisionConfigService configuration;
    @MockBean ManagementGuard guard;

    @BeforeEach void allowTestActor() { doNothing().when(guard).begin(any(),any(),any(),any()); }

    @Test void databaseCredentialWriteIsRejectedWithoutIndependentMasterKey() {
        var actor=new AuthService.Identity(new AuthService.UserView("fixture","fixture","Fixture","PLATFORM",null,false),List.of("integrations:write"),"management","fixture-token");
        var input=new HikvisionConfigInputs.Write(0L,"https://open.hikfirecloud.com","fixture-key","fixture-secret",false,30,"800235");
        assertThatThrownBy(()->configuration.update(actor,"hikvision-no-key",input,"request-no-key"))
            .isInstanceOfSatisfying(ApiError.class,error -> {
                assertThat(error.status).isEqualTo(503);
                assertThat(error.code).isEqualTo("HIKVISION_CONFIG_KEY_UNAVAILABLE");
            });
    }
}
