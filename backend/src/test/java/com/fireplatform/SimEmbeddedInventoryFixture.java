package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import com.fireplatform.shared.Digests;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Test-only fixture. No original fixture or production business service is modified. */
class SimEmbeddedInventoryFixture {
    static final String DEVICE="legacy-device",OTHER="legacy-device-other",FOREIGN="foreign-device",PROFILE="/api/management/embedded-sim-profiles",PROFILE_CHANGES="/api/management/embedded-sim-profile-changes",STOCK="/api/management/sim-card-inventory";
    final IngestionIntegrationTest api=new IngestionIntegrationTest();final JdbcTemplate jdbc;final ObjectMapper json;final DeviceGroupIntegrationTest.Observed observed;
    SimEmbeddedInventoryFixture(MockMvc mvc,ObjectMapper json,JdbcTemplate jdbc,Bootstrap bootstrap,JsonPointerParser parser,DeviceGroupIntegrationTest.Observed observed){this.jdbc=jdbc;this.json=json;this.observed=observed;api.mvc=mvc;api.json=json;api.jdbc=jdbc;api.bootstrap=bootstrap;api.parser=parser;}
    void reset()throws Exception{observed.stop();observed.beforePage=null;for(String table:List.of("sim_embedded_profile_changes","sim_embedded_profiles","sim_card_inventory_changes","sim_card_inventory","sim_card_association_changes","sim_card_associations","sim_card_changes","sim_cards","device_group_members","device_groups"))jdbc.update("DELETE FROM "+table);NotificationIntegrationTest.clear(jdbc);jdbc.update("DELETE FROM alarm_change_outbox");jdbc.update("DELETE FROM alarm_change_heads");api.reset();jdbc.update("UPDATE numeric_settings SET setting_value=20,revision=0 WHERE setting_key='default_page_size'");device(DEVICE,"tenant-a");device(OTHER,"tenant-a");device(FOREIGN,"tenant-b");}
    String root(){return api.root;}
    void device(String id,String tenant){jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES (?,?,?,?,'m','','NORMAL')",id,tenant,"NOT_A_HISTORICAL_NAME",UUID.randomUUID().toString());}
    ResultActions call(HttpMethod method,String path,String token,String tenant,String key,Object input)throws Exception{var builder=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method,java.net.URI.create(path));if(token!=null)builder.header("Authorization","Bearer "+token);if(tenant!=null)builder.header("X-Tenant-Id",tenant);if(key!=null)builder.header("Idempotency-Key",key);if(input!=null)builder.contentType("application/json").content(json.writeValueAsString(input));return api.mvc.perform(builder);}
    JsonNode body(ResultActions result)throws Exception{return api.body(result);}
    Map<String,Object> profileInput(String device,long revision){var m=new LinkedHashMap<String,Object>();m.put("device_id",device);m.put("iccid",null);m.put("carrier_name","");m.put("replaceability","UNKNOWN");m.put("management_party","UNKNOWN");m.put("fee_responsibility","UNKNOWN");m.put("reported_operator_status","UNKNOWN");m.put("expires_on",null);m.put("evidence_at",null);m.put("source_note","");m.put("expected_profile_revision",revision);m.put("reason","");return m;}
    Map<String,Object> stockInput(String operation,long revision){var m=new LinkedHashMap<String,Object>();m.put("expected_inventory_revision",revision);m.put("reason",operation.equals("correct")?"Correction evidence":"");if(operation.equals("issue"))m.put("issued_to","Ordinary recipient note");return m;}
    JsonNode recordProfile(String device,long revision,String key)throws Exception{return body(call(POST,PROFILE,root(),"tenant-a",key,profileInput(device,revision)).andExpect(status().isOk()));}
    JsonNode recordStock(String card,String operation,long revision,String key)throws Exception{return body(call(POST,STOCK+"/"+card+"/"+operation,root(),"tenant-a",key,stockInput(operation,revision)).andExpect(status().isOk()));}
    String profileUrl(String device){return PROFILE+"?device_id="+java.net.URLEncoder.encode(device,java.nio.charset.StandardCharsets.UTF_8);}
    String profileHistoryUrl(String device,String query){return PROFILE_CHANGES+"?device_id="+java.net.URLEncoder.encode(device,java.nio.charset.StandardCharsets.UTF_8)+query;}
    JsonNode profile(String device)throws Exception{return body(call(GET,profileUrl(device),root(),"tenant-a",null,null).andExpect(status().isOk()));}
    JsonNode profileHistory(String device,String query)throws Exception{return body(call(GET,profileHistoryUrl(device,query),root(),"tenant-a",null,null).andExpect(status().isOk()));}
    JsonNode stock(String card)throws Exception{return body(call(GET,STOCK+"/"+card,root(),"tenant-a",null,null).andExpect(status().isOk()));}
    JsonNode stockHistory(String card,String query)throws Exception{return body(call(GET,STOCK+"/"+card+"/changes"+query,root(),"tenant-a",null,null).andExpect(status().isOk()));}
    Map<String,Object> metadata(Long revision){var m=new LinkedHashMap<String,Object>();m.put(revision==null?"iccid":"expected_revision",revision==null?UUID.randomUUID().toString():revision);m.put("carrier_name","");m.put("replaceability","UNKNOWN");m.put("management_party","UNKNOWN");m.put("fee_responsibility","UNKNOWN");m.put("reported_operator_status","UNKNOWN");m.put("expires_on",null);m.put("evidence_at",null);m.put("source_note","");return m;}
    String card(String tenant)throws Exception{return body(call(POST,"/api/management/sim-cards",root(),tenant,UUID.randomUUID().toString(),metadata(null)).andExpect(status().isOk())).get("id").asText();}
    String card()throws Exception{return card("tenant-a");}
    String actor(String name,List<String> permissions)throws Exception{call(POST,"/api/management/platform-users",root(),null,UUID.randomUUID().toString(),Map.of("username",name,"name",name,"initial_password",IdentityManagementTest.TARGET,"permissions",permissions,"tenant_ids",List.of("tenant-a"))).andExpect(status().isOk());String token=api.login(name,IdentityManagementTest.TARGET,"management");call(POST,"/api/auth/change-password",token,null,null,Map.of("current_password",IdentityManagementTest.TARGET,"new_password",IdentityManagementTest.UPDATED)).andExpect(status().isNoContent());N04TestScopes.grantExisting(jdbc);return api.login(name,IdentityManagementTest.UPDATED,"management");}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    String facts(Set<String> skip)throws Exception{observed.stop();var all=new TreeMap<String,Object>();var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name",String.class);for(String table:tables)if(!skip.contains(table))all.put(table,jdbc.queryForList("SELECT * FROM "+table));return Digests.sha256(json.writeValueAsString(all));}
    void budget(int count){var calls=List.copyOf(observed.calls);observed.stop();var domain=calls.stream().filter(DeviceGroupIntegrationTest.Call::readOnly).toList();assertThat(domain).hasSize(count).allMatch(c->c.isolation()==java.sql.Connection.TRANSACTION_REPEATABLE_READ&&c.sql().startsWith("SELECT "));assertThat(domain).noneMatch(c->c.sql().contains("SELECT *")||c.sql().contains("FOR UPDATE")||c.sql().contains("clock_timestamp")||c.sql().contains("app_users")||c.sql().contains("device_eui")||c.sql().contains("display_name")||c.sql().contains("notification_"));}
    void concurrent(Runnable work){try(var pool=Executors.newSingleThreadExecutor()){pool.submit(work).get(15,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError("bounded manual record concurrency failed",e);}}
}
