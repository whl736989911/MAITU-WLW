package com.fireplatform.bootstrap;

import com.fireplatform.identity.IdentityProvisioner;
import com.fireplatform.shared.Limits;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Composition adapter only: domain writes are performed through their public provisioning services. */
@Component
public class Bootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc; private final Environment env; private final TransactionTemplate tx; private final IdentityProvisioner identities;
    public Bootstrap(JdbcTemplate jdbc,Environment env,TransactionTemplate tx,IdentityProvisioner identities) { this.jdbc=jdbc; this.env=env; this.tx=tx; this.identities=identities; }
    @Override public void run(ApplicationArguments args) {
        tx.executeWithoutResult(ignored->{
            jdbc.queryForObject("SELECT id FROM bootstrap_lock WHERE id=1 FOR UPDATE",Integer.class);
            String password=env.getProperty("APP_BOOTSTRAP_PASSWORD");
            if(password!=null && !password.isBlank()) {
                String username=env.getProperty("APP_BOOTSTRAP_USERNAME","admin");
                identities.account("bootstrap-admin",username,"平台管理员","PLATFORM",null,password,Limits.PLATFORM_PERMISSIONS);
            }
        });
    }
}
