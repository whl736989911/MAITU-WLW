package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fireplatform.bootstrap.Bootstrap;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.roles=all","app.storage=h2-dev","spring.datasource.url=jdbc:h2:mem:placesubtreemovetests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class PlaceSubtreeMoveTest {
    static final String INITIAL="Place-Move-Initial", PASSWORD="Place-Move-Changed";
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r){r.add("APP_BOOTSTRAP_USERNAME",()->"admin");r.add("APP_BOOTSTRAP_PASSWORD",()->INITIAL);}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap; @Autowired PasswordEncoder encoder;
    String root,source,child,target,device;
    @BeforeEach void reset() throws Exception {
        for(String table:List.of("device_place_history","alarm_trace_entries","alarms","idempotency_records","audit_log","sessions","user_tenant_grants","user_device_scope_devices","user_device_scopes","devices","places","tenants")) jdbc.update("DELETE FROM "+table);
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE WHERE id='bootstrap-admin'",encoder.encode(INITIAL));
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('move-unit','Move unit'),('other-unit','Other unit')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','move-unit')");
        N04TestScopes.grantExisting(jdbc);
        root=login("admin",INITIAL,"management");call(HttpMethod.POST,"/api/auth/change-password",root,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());root=login("admin",PASSWORD,"management");
        source=place("Source",null);child=place("Child",source);target=place("Target",null);
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id,revision) VALUES ('foreign-place','other-unit','Foreign',NULL,0)");
        device="move-device";
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,place_id) VALUES (?,?,'Device',?,'model','address','FIRE',FALSE,?)",device,"move-unit",device,child);
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,revision,is_simulated) VALUES ('move-alarm','move-unit',?,'Device','address','FIRE',CURRENT_TIMESTAMP,'FIRE','UNHANDLED',0,FALSE)",device);
    }
    String place(String name,String parent)throws Exception {var b=new LinkedHashMap<String,Object>();b.put("name",name);if(parent!=null)b.put("parent_place_id",parent);return body(call(HttpMethod.POST,"/api/management/places",root,"move-unit",UUID.randomUUID().toString(),b).andExpect(status().isOk())).get("id").asText();}
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input)throws Exception {var b=request(method,path);if(token!=null)b.header("Authorization","Bearer "+token);if(tenant!=null)b.header("X-Tenant-Id",tenant);if(key!=null)b.header("Idempotency-Key",key);if(input!=null)b.contentType("application/json").content(json.writeValueAsString(input));return mvc.perform(b);}
    JsonNode body(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString());}
    String login(String username,String password,String audience)throws Exception{return body(call(HttpMethod.POST,"/api/auth/login",null,null,null,Map.of("username",username,"password",password,"audience",audience)).andExpect(status().isOk())).get("access_token").asText();}
    JsonNode preview(String parent)throws Exception {String path="/api/management/places/"+source+"/move-preview"+(parent==null?"":"?target_parent_id="+parent);return body(call(HttpMethod.GET,path,root,"move-unit",null,null).andExpect(status().isOk()));}
    Map<String,Object> move(String parent,long revision,String snapshot,String reason){var b=new LinkedHashMap<String,Object>();b.put("target_parent_id",parent);b.put("expected_revision",revision);b.put("snapshot",snapshot);b.put("reason",reason);return b;}

    @Test void previewCountsWholeSubtreeMovePreservesDeviceAndAlarmAndReplays()throws Exception {
        JsonNode plan=preview(target);assertThat(plan.get("places_count").asLong()).isEqualTo(2);assertThat(plan.get("devices_count").asLong()).isEqualTo(1);
        String key="place-move-once";var input=move(target,plan.at("/place/revision").asLong(),plan.get("snapshot").asText(),"Reorganize floors");
        JsonNode moved=body(call(HttpMethod.POST,"/api/management/places/"+source+"/move",root,"move-unit",key,input).andExpect(status().isOk()));
        assertThat(moved.get("changed").asBoolean()).isTrue();assertThat(moved.get("places_count").asLong()).isEqualTo(2);assertThat(moved.get("devices_count").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT place_id FROM devices WHERE id=?",String.class,device)).isEqualTo(child);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_place_history WHERE device_id=?",Long.class,device)).isZero();
        assertThat(jdbc.queryForObject("SELECT physical_state FROM alarms WHERE id='move-alarm'",String.class)).isEqualTo("FIRE");
        JsonNode replay=body(call(HttpMethod.POST,"/api/management/places/"+source+"/move",root,"move-unit",key,input).andExpect(status().isOk()));assertThat(replay).isEqualTo(moved);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idem_key=?",Long.class,key)).isEqualTo(1);
    }
    @Test void movesToRootAndSameParentIsNoOp()throws Exception {
        JsonNode plan=body(call(HttpMethod.GET,"/api/management/places/"+child+"/move-preview",root,"move-unit",null,null).andExpect(status().isOk()));
        var moved=body(call(HttpMethod.POST,"/api/management/places/"+child+"/move",root,"move-unit","move-root",move(null,0,plan.get("snapshot").asText(),"Move to root")).andExpect(status().isOk()));
        assertThat(moved.get("changed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id=?",String.class,child)).isNull();
        long revision=moved.at("/place/revision").asLong();
        JsonNode same=body(call(HttpMethod.GET,"/api/management/places/"+child+"/move-preview",root,"move-unit",null,null).andExpect(status().isOk()));
        var noOp=body(call(HttpMethod.POST,"/api/management/places/"+child+"/move",root,"move-unit","move-root-noop",move(null,revision,same.get("snapshot").asText(),"Keep at root")).andExpect(status().isOk()));
        assertThat(noOp.get("changed").asBoolean()).isFalse();assertThat(noOp.at("/place/revision").asLong()).isEqualTo(revision);
    }
    @Test void rejectsDescendantForeignParentStaleCountAndUnauthorizedTenant()throws Exception {
        call(HttpMethod.GET,"/api/management/places/"+source+"/move-preview?target_parent_id="+child,root,"move-unit",null,null).andExpect(status().isConflict());
        JsonNode foreign=preview(target);call(HttpMethod.POST,"/api/management/places/"+source+"/move",root,"move-unit","foreign",move("foreign-place",foreign.at("/place/revision").asLong(),foreign.get("snapshot").asText(),"Invalid")).andExpect(status().isBadRequest());
        JsonNode stale=preview(target);jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,place_id) VALUES ('new-device','move-unit','New','new-eui','model','address','UNKNOWN',FALSE,?)",child);
        call(HttpMethod.POST,"/api/management/places/"+source+"/move",root,"move-unit","stale",move(target,stale.at("/place/revision").asLong(),stale.get("snapshot").asText(),"Stale")).andExpect(status().isConflict());
        call(HttpMethod.GET,"/api/management/places/"+source+"/move-preview",root,"other-unit",null,null).andExpect(status().isForbidden());
    }

    @Test void restrictedPlaceCountsExcludeHiddenSameUnitDevices() throws Exception {
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,place_id) VALUES ('move-hidden','move-unit','Hidden device','move-hidden','model','address','UNKNOWN',FALSE,?)",target);
        JsonNode user=body(call(HttpMethod.POST,"/api/management/platform-users",root,null,"count-operator",
            Map.of("username","count-operator","name","Count operator","initial_password",INITIAL,
                "permissions",List.of("units:read","device:read"),"tenant_ids",List.of("move-unit"))).andExpect(status().isOk()));
        call(HttpMethod.PUT,"/api/management/platform-users/"+user.get("id").asText()+"/scope",root,"move-unit","count-scope",
            Map.of("mode","DEVICE_SET","device_ids",List.of(device),"place_ids",List.of(),"expected_revision",0,"reason","Restrict device count reader"))
            .andExpect(status().isOk());
        String token=login("count-operator",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",token,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        token=login("count-operator",PASSWORD,"management");
        call(HttpMethod.GET,"/api/management/devices/move-hidden",token,"move-unit",null,null).andExpect(status().isNotFound());

        JsonNode places=body(call(HttpMethod.GET,"/api/management/places",token,"move-unit",null,null).andExpect(status().isOk()));
        assertThat(findPlace(places,target).get("device_count").asLong()).isZero();
        JsonNode tree=body(call(HttpMethod.GET,"/api/management/scope-tree",token,null,null,null).andExpect(status().isOk()));
        assertThat(findPlace(tree.get(0).get("places"),target).get("device_count").asLong()).isZero();
        assertThat(findPlace(places,target).get("name").asText()).isEqualTo("Target");
    }

    private JsonNode findPlace(JsonNode places,String id) {
        for(JsonNode place:places) if(id.equals(place.path("id").asText())) return place;
        throw new AssertionError("place not found: "+id);
    }
    @Test void restrictedScopeCannotBypassWholeTreeAuthorityThroughMetadataEdit() throws Exception {
        JsonNode editor=body(call(HttpMethod.POST,"/api/management/platform-users",root,null,"limited-tree-user",Map.of(
            "username","limited-tree-editor","name","Limited tree editor","initial_password",INITIAL,
            "permissions",List.of("units:read","units:write","device:read"),"tenant_ids",List.of("move-unit"))).andExpect(status().isOk()));
        String limited=login("limited-tree-editor",INITIAL,"management");
        call(HttpMethod.POST,"/api/auth/change-password",limited,null,null,Map.of("current_password",INITIAL,"new_password",PASSWORD)).andExpect(status().isNoContent());
        limited=login("limited-tree-editor",PASSWORD,"management");
        // The editor's real scope is the dynamic subtree rooted at the source place, never the whole unit.
        call(HttpMethod.PUT,"/api/management/platform-users/"+editor.get("id").asText()+"/scope",root,"move-unit","limited-tree-scope",
            Map.of("mode","PLACE_SUBTREE","device_ids",List.of(),"place_ids",List.of(source),"expected_revision",0,"reason","限定子树")).andExpect(status().isOk());
        // Assigning a scope revokes that account's sessions.
        limited=login("limited-tree-editor",PASSWORD,"management");
        var rename=new LinkedHashMap<String,Object>();rename.put("name","Renamed");rename.put("parent_place_id",null);rename.put("expected_revision",0);
        call(HttpMethod.PUT,"/api/management/places/"+source,limited,"move-unit",null,rename).andExpect(status().isOk());
        // A parent outside the editor's subtree is one 404 before any whole-unit authority is consulted.
        call(HttpMethod.GET,"/api/management/places/"+source+"/move-preview?target_parent_id="+target,limited,"move-unit",null,null).andExpect(status().isNotFound());
        rename.put("parent_place_id",target);rename.put("expected_revision",1);
        call(HttpMethod.PUT,"/api/management/places/"+source,limited,"move-unit",null,rename).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id=?",String.class,source)).isNull();
        assertThat(jdbc.queryForObject("SELECT revision FROM places WHERE id=?",Long.class,source)).isEqualTo(1);
    }
}
