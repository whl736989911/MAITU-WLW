package com.fireplatform.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Called only under the caller's transaction and the identity row lock. No in-memory cache. */
@Component
public class IdempotencyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    public IdempotencyStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) { this.jdbc = jdbc; this.mapper = mapper; this.clock = clock; }
    public void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw ApiError.bad("Idempotency-Key必须为1至128位安全ASCII标识");
    }
    public String fingerprint(Object request) { return Digests.sha256(json(request)); }
    public <T> T replay(String userId, String operation, String key, String hash, Class<T> type) {
        var rows = jdbc.query("SELECT request_hash,response_json FROM idempotency_records WHERE user_id=? AND operation=? AND idem_key=?",
            (rs,n) -> new String[]{rs.getString(1),rs.getString(2)}, userId, operation, key);
        if (rows.isEmpty()) return null;
        if (!rows.getFirst()[0].equals(hash)) throw ApiError.conflict("IDEMPOTENCY_CONFLICT", "幂等键已用于不同的请求内容");
        return parse(rows.getFirst()[1], type);
    }
    public void save(String userId, String operation, String key, String hash, Object result) {
        jdbc.update("INSERT INTO idempotency_records(user_id,operation,idem_key,request_hash,response_json,created_at) VALUES (?,?,?,?,?,?)", userId, operation, key, hash, json(result), OffsetDateTime.now(clock));
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode internal response"); }
    }
    public <T> T parse(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored response is invalid"); }
    }
}
