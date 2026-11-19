package com.fireplatform.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Digests;
import com.fireplatform.shared.IdempotencyStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Only non-secret business content enters requestHash. Target secrets use separate salted slow hashes. */
@Service
public class SecretCommandStore {
    private final JdbcTemplate jdbc; private final PasswordEncoder encoder; private final ObjectMapper mapper; private final Clock clock; private final IdempotencyStore keys;
    public SecretCommandStore(JdbcTemplate jdbc,PasswordEncoder encoder,ObjectMapper mapper,Clock clock,IdempotencyStore keys) { this.jdbc=jdbc; this.encoder=encoder; this.mapper=mapper; this.clock=clock; this.keys=keys; }
    public String hashBusiness(String key,Object nonSecretContent) { keys.validateKey(key); return Digests.sha256(json(nonSecretContent)); }
    public <T> T replay(String actor,String operation,String key,String businessHash,String targetSecret,Class<T> type) {
        var rows=jdbc.query("SELECT request_hash,secret_hash,response_json FROM identity_command_records WHERE actor_id=? AND operation=? AND idem_key=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},actor,operation,key);
        if(rows.isEmpty()) return null;
        var row=rows.getFirst(); boolean secretMatches=row[1]==null ? targetSecret==null : targetSecret!=null && encoder.matches(targetSecret,row[1]);
        if(!row[0].equals(businessHash) || !secretMatches) throw ApiError.conflict("IDEMPOTENCY_CONFLICT","幂等键已用于不同业务内容或目标凭据");
        try { return mapper.readValue(row[2],type); } catch(JsonProcessingException e) { throw new IllegalStateException("Stored command response invalid"); }
    }
    public void save(String actor,String operation,String key,String hash,String targetSecret,Object safeResponse) {
        jdbc.update("INSERT INTO identity_command_records(actor_id,operation,idem_key,request_hash,secret_hash,response_json,created_at) VALUES (?,?,?,?,?,?,?)",actor,operation,key,hash,targetSecret==null ? null : encoder.encode(targetSecret),json(safeResponse),OffsetDateTime.now(clock));
    }
    private String json(Object object) { try { return mapper.writeValueAsString(object); } catch(JsonProcessingException e) { throw new IllegalStateException("Internal serialization failed"); } }
}
