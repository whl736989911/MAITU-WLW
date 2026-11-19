package com.fireplatform.notification.officialaccount;

import com.fireplatform.transport.WechatOfficialAccountClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bounded in-process access-token cache.
 *
 * <p>Entries are keyed by the frozen AppID plus credential revision and never by the current page
 * configuration alone. Rotating the page configuration therefore cannot make an in-flight attempt
 * reuse a token minted for a different account. The cache is deliberately memory-only: a restart
 * simply fetches a new token, and no token is written to the database or logs.</p>
 */
@Component
public class WechatOfficialAccountTokenCache {
    public record State(boolean cached, String accountFingerprint, Long credentialsRevision, OffsetDateTime expiresAt, boolean current) { }
    private record Entry(String token, Instant expiresAt, String fingerprint, long credentialsRevision) { }

    private static final int MAX_ENTRIES = 4;
    private static final long MAX_TTL_SECONDS = 7200;
    private static final long SAFETY_SECONDS = 300;
    private static final long MIN_TTL_SECONDS = 60;

    private final Map<String, Entry> entries = new LinkedHashMap<>(8, 0.75f, true);
    private final WechatOfficialAccountClient client;

    public WechatOfficialAccountTokenCache(WechatOfficialAccountClient client) {
        this.client = client;
    }

    public WechatOfficialAccountClient.TokenResult get(WechatOfficialAccountConfigService.Snapshot snapshot) {
        if (snapshot == null || !snapshot.tokenReady())
            return new WechatOfficialAccountClient.TokenResult(false, null, "NOT_CONFIGURED", "公众号 AccessToken 凭据未配置", 0);
        String key = key(snapshot);
        synchronized (entries) {
            Entry existing = entries.get(key);
            if (existing != null && Instant.now().isBefore(existing.expiresAt())) {
                return new WechatOfficialAccountClient.TokenResult(true, existing.token(), "0", "cached", 0);
            }
            if (existing != null) entries.remove(key);
        }
        WechatOfficialAccountClient.TokenResult acquired = client.accessToken(snapshot.appId(), snapshot.appSecret());
        if (!acquired.success()) return acquired;
        long reported = acquired.expiresIn() > 0 ? acquired.expiresIn() : 7200;
        long ttl = Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, reported) - SAFETY_SECONDS);
        synchronized (entries) {
            entries.put(key, new Entry(acquired.token(), Instant.now().plus(Duration.ofSeconds(ttl)), snapshot.accountFingerprint(), snapshot.credentialsRevision()));
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            while (entries.size() > MAX_ENTRIES && iterator.hasNext()) { iterator.next(); iterator.remove(); }
        }
        return acquired;
    }

    public void invalidate(WechatOfficialAccountConfigService.Snapshot snapshot) {
        if (snapshot == null) return;
        synchronized (entries) { entries.remove(key(snapshot)); }
    }

    public State state(WechatOfficialAccountConfigService.Snapshot snapshot) {
        if (snapshot == null) return new State(false, null, null, null, false);
        synchronized (entries) {
            Entry entry = entries.get(key(snapshot));
            boolean current = entry != null && entry.fingerprint().equals(snapshot.accountFingerprint())
                && entry.credentialsRevision() == snapshot.credentialsRevision();
            return new State(entry != null, entry == null ? null : entry.fingerprint(),
                entry == null ? null : entry.credentialsRevision(),
                entry == null ? null : OffsetDateTime.ofInstant(entry.expiresAt(), ZoneOffset.UTC), current);
        }
    }

    private static String key(WechatOfficialAccountConfigService.Snapshot snapshot) {
        return snapshot.appId() + "#" + snapshot.credentialsRevision();
    }
}
