package com.fireplatform.identity;

import com.fireplatform.shared.Limits;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Startup-only provisioning boundary. Never resets a credential during restart. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class IdentityProvisioner {
    private final JdbcTemplate jdbc; private final PasswordEncoder encoder;
    public IdentityProvisioner(JdbcTemplate jdbc,PasswordEncoder encoder) { this.jdbc=jdbc; this.encoder=encoder; }
    public void tenant(String id,String name) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id=?",Long.class,id)==0) jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)",id,name);
    }
    public void grant(String userId,String tenant) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?",Long.class,userId,tenant)==0) jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES (?,?)",userId,tenant);
        // Controlled bootstrap grants the creator an explicit ALL_TENANT scope; an existing configured scope is never clobbered.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scopes WHERE user_id=? AND tenant_id=?",Long.class,userId,tenant)==0)
            jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES (?,?,'ALL_TENANT',1)",userId,tenant);
    }
    public String account(String id,String username,String name,String type,String tenant,String password,List<String> permissions) {
        if(!username.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{2,99}")) throw new IllegalStateException("初始账号用户名不符合要求");
        var existing=jdbc.query("SELECT id,user_type FROM app_users WHERE username=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},username);
        if(!existing.isEmpty()) { if(!existing.getFirst()[1].equals(type)) throw new IllegalStateException("Bootstrap username is already assigned to another account type"); return existing.getFirst()[0]; }
        if(!Limits.validPassword(password)) throw new IllegalStateException("初始账号：" + Limits.PASSWORD_REQUIREMENTS);
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected) VALUES (?,?,?,?,?,?,TRUE,TRUE,?)",id,username,name,type,tenant,encoder.encode(password),id.equals("bootstrap-admin") && type.equals("PLATFORM"));
        for(String permission:permissions) jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,?)",id,permission); return id;
    }
}
