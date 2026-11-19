package com.fireplatform.legacyingest.codec;

import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LegacyCodecRegistry {
    public record Decoder(String legacyProductId, long revision, String source) { }
    private record Key(String productId, long revision) { }
    private final JdbcTemplate jdbc;
    private final int capacity;
    private final LinkedHashMap<Key, Decoder> cache = new LinkedHashMap<>(16, 0.75f, true);

    @org.springframework.beans.factory.annotation.Autowired
    public LegacyCodecRegistry(JdbcTemplate jdbc) { this(jdbc, 128); }
    public LegacyCodecRegistry(JdbcTemplate jdbc, int capacity) {
        this.jdbc = jdbc;
        this.capacity = Math.max(1, capacity);
    }

    public Optional<Decoder> findLatest(String legacyProductId) {
        if (legacyProductId == null || legacyProductId.isBlank()) return Optional.empty();
        Long revision = jdbc.query("SELECT revision FROM legacy_product_decoders WHERE legacy_product_id=? ORDER BY revision DESC LIMIT 1",
            rs -> rs.next() ? rs.getLong(1) : null, legacyProductId);
        if (revision == null) return Optional.empty();
        Key key = new Key(legacyProductId, revision);
        synchronized (cache) {
            Decoder hit = cache.get(key);
            if (hit != null) return Optional.of(hit);
        }
        Decoder loaded = jdbc.query("SELECT source FROM legacy_product_decoders WHERE legacy_product_id=? AND revision=?",
            rs -> rs.next() ? new Decoder(legacyProductId, revision, rs.getString(1)) : null, legacyProductId, revision);
        if (loaded == null) return Optional.empty();
        synchronized (cache) {
            Decoder existing = cache.get(key);
            if (existing != null) return Optional.of(existing);
            cache.put(key, loaded);
            while (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
            return Optional.of(loaded);
        }
    }
}
