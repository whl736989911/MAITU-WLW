package com.fireplatform;

import java.nio.charset.StandardCharsets;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class IngestionMigrationTest {
    @Test void v5AddsRootOnlyPermissionsPreservesDevicesAndUsesPortableLosslessBinaryStorage() {
        String url="jdbc:h2:mem:ingestion-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"; Flyway.configure().dataSource(url,"sa","").target("4").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa","")); jdbc.update("INSERT INTO tenants(id,name) VALUES ('unit','unit')");
        for(String id:new String[]{"bootstrap-admin","other-protected","ordinary"}) jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,is_protected) VALUES (?,?,?,'PLATFORM','hash',FALSE,TRUE,?)",id,id,id,!id.equals("ordinary"));
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,battery_percent) VALUES ('device','unit','original','original-eui','old','old-address','FIRE',0)"); var before=jdbc.queryForMap("SELECT * FROM devices WHERE id='device'");
        Flyway.configure().dataSource(url,"sa","").target("5").load().migrate(); assertThat(jdbc.queryForMap("SELECT * FROM devices WHERE id='device'")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='bootstrap-admin'",String.class)).contains("sources:read","sources:write","sources:bind","sources:credentials","messages:read","messages:raw","messages:preview");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id IN ('other-protected','ordinary')",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_bindings",Long.class)).isZero(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_messages",Long.class)).isZero();
        jdbc.update("INSERT INTO ingestion_sources(id,tenant_id,name,protocol,is_simulated,enabled,revision,token_hash,token_generation,created_at) VALUES ('source','unit','source','GENERIC_HTTP_JSON_V1',FALSE,TRUE,0,?,1,CURRENT_TIMESTAMP)","0".repeat(64));
        byte[] original=" \n 中文\u0000 ".getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO raw_messages(id,tenant_id,source_id,source_name,source_message_id,vendor_device_id,received_at,payload_utf8,payload_bytes,payload_sha256,binding_state) VALUES ('raw','unit','source','snapshot','id','v',CURRENT_TIMESTAMP,?,?,?,'UNBOUND')",original,original.length,com.fireplatform.ingestion.IngestionLimits.sha256(original));
        assertThat(jdbc.queryForObject("SELECT payload_utf8 FROM raw_messages",byte[].class)).isEqualTo(original);
        assertThatThrownBy(()->jdbc.update("INSERT INTO raw_messages(id,tenant_id,source_id,source_name,source_message_id,vendor_device_id,received_at,payload_utf8,payload_bytes,payload_sha256,binding_state) VALUES ('raw-two','unit','source','snapshot','id','v',TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00',?,?,?,'UNBOUND')",original,original.length,"0".repeat(64))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        jdbc.update("INSERT INTO ingestion_sources(id,tenant_id,name,protocol,is_simulated,enabled,revision,token_hash,token_generation,created_at) VALUES ('source-two','unit','source-two','GENERIC_HTTP_JSON_V1',FALSE,TRUE,0,?,1,CURRENT_TIMESTAMP)","1".repeat(64));
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES ('device-two','unit','second','second-eui','model','address','UNKNOWN')");
        jdbc.update("INSERT INTO source_bindings(id,tenant_id,source_id,device_id,vendor_device_id,created_at,bind_reason,bind_actor_id) VALUES ('b1','unit','source','device','v',CURRENT_TIMESTAMP,'test','bootstrap-admin'),('b2','unit','source-two','device','different',CURRENT_TIMESTAMP,'test','bootstrap-admin'),('b3','unit','source','device-two','v',CURRENT_TIMESTAMP,'test','bootstrap-admin')");
        jdbc.update("INSERT INTO active_source_bindings(binding_id,source_id,device_id,vendor_device_id) VALUES ('b1','source','device','v')");
        assertThatThrownBy(()->jdbc.update("INSERT INTO active_source_bindings(binding_id,source_id,device_id,vendor_device_id) VALUES ('b2','source-two','device','different')")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO active_source_bindings(binding_id,source_id,device_id,vendor_device_id) VALUES ('b3','source','device-two','v')")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        Flyway.configure().dataSource(url,"sa","").target("5").load().migrate(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_messages",Long.class)).isEqualTo(1);
    }
}
