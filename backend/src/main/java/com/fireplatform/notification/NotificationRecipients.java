package com.fireplatform.notification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolve audience independently of delivery policy and quota. Phone identity is already unique within a unit. */
final class NotificationRecipients {
    private static final int MAX_PLACE_DEPTH = 64;

    record Recipient(String contactId, String contactName, String phoneMasked, String phoneCipher, String phoneNonce,
                     String channel, int level, long delaySeconds, String scopeType, String scopeId,
                     String ruleId, long revision, int distance) { }
    private record Key(String channel, String contactId) { }
    private static final Comparator<Recipient> PRECEDENCE = Comparator.comparingLong(Recipient::delaySeconds)
        .thenComparingInt(Recipient::distance).thenComparingInt(Recipient::level).thenComparing(Recipient::ruleId);
    private static final Comparator<Recipient> SEND_ORDER = Comparator.comparing(Recipient::channel)
        .thenComparingLong(Recipient::delaySeconds).thenComparing(Recipient::contactId);

    private NotificationRecipients() { }

    /** Nearest active place first; stop at missing, archived, foreign or repeated nodes. */
    static List<String> places(JdbcTemplate jdbc, String tenant, String placeId) {
        var chain = new ArrayList<String>();
        var seen = new HashSet<String>();
        String current = placeId;
        while (current != null && chain.size() < MAX_PLACE_DEPTH && seen.add(current)) {
            var parents = jdbc.queryForList("SELECT parent_place_id FROM places WHERE tenant_id=? AND id=? AND archived_at IS NULL",
                String.class, tenant, current);
            if (parents.isEmpty()) break;
            chain.add(current);
            current = parents.getFirst();
        }
        return chain;
    }

    static List<Recipient> resolve(JdbcTemplate jdbc, String tenant, String device, String event) {
        var location = jdbc.queryForList("SELECT place_id FROM devices WHERE tenant_id=? AND id=? AND archived_at IS NULL",
            String.class, tenant, device);
        if (location.isEmpty()) return List.of();
        var chain = places(jdbc, tenant, location.getFirst());
        var params = new ArrayList<Object>();
        Collections.addAll(params, tenant, event, device);
        String placeMatch = chain.isEmpty() ? "" : " OR r.scope_place_id IN ("
            + String.join(",", Collections.nCopies(chain.size(), "?")) + ")";
        params.addAll(chain);
        var winners = new HashMap<Key, Recipient>();
        jdbc.query("""
            SELECT c.id AS contact_id,c.name AS contact_name,c.phone_masked,c.phone_cipher,c.phone_nonce,
                   rr.channel,rr.level,r.scope_type,COALESCE(r.scope_device_id,r.scope_place_id,r.tenant_id) AS scope_id,
                   r.id AS rule_id,r.revision,
                   (rr.level-1)*COALESCE(CASE rr.channel WHEN 'SMS' THEN r.sms_escalation_interval_seconds
                   WHEN 'VOICE' THEN r.voice_escalation_interval_seconds ELSE r.wechat_escalation_interval_seconds END,0) AS delay_seconds
            FROM notification_rules r
            JOIN notification_rule_receivers rr ON rr.tenant_id=r.tenant_id AND rr.rule_id=r.id
            JOIN notification_contacts c ON c.tenant_id=r.tenant_id AND c.id=rr.contact_id
            WHERE r.tenant_id=? AND r.event_scope=? AND r.legacy_mode IS NULL AND r.enabled=TRUE AND c.enabled=TRUE
              AND (r.scope_type='UNIT' OR r.scope_device_id=?""" + placeMatch + ")", rs -> {
                String type = rs.getString("scope_type"), id = rs.getString("scope_id");
                int distance = switch (type) {
                    case "DEVICE" -> 0;
                    case "PLACE" -> chain.indexOf(id) + 1;
                    default -> chain.size() + 1;
                };
                var recipient = new Recipient(rs.getString("contact_id"), rs.getString("contact_name"),
                    rs.getString("phone_masked"), rs.getString("phone_cipher"), rs.getString("phone_nonce"),
                    rs.getString("channel"), rs.getInt("level"), rs.getLong("delay_seconds"), type, id,
                    rs.getString("rule_id"), rs.getLong("revision"), distance);
                winners.merge(new Key(recipient.channel(), recipient.contactId()), recipient,
                    (left, right) -> PRECEDENCE.compare(left, right) <= 0 ? left : right);
            }, params.toArray());
        return winners.values().stream().sorted(SEND_ORDER).toList();
    }
}
