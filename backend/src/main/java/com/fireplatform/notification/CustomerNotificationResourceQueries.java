package com.fireplatform.notification;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.Pagination;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer-facing read projection for notification resource balances and ledger.
 * Every read re-derives the caller's current recharge destinations (policy plus grants)
 * so a revoked grant or a policy switch is rejected on the next query. The projection
 * never exposes operators, payment identities, contact data or vendor references.
 */
@Service
public class CustomerNotificationResourceQueries {
    public record Balance(String accountId, String ownerType, String ownerId, String ownerName,
                          long smsTotal, long smsUsed, long smsRemaining,
                          long voiceTotal, long voiceUsed, long voiceRemaining,
                          boolean enabled, OffsetDateTime updatedAt) { }
    public record Entry(String id, String accountId, String channel, String kind,
                        long quantity, long balanceAfter, OffsetDateTime createdAt) { }

    private final JdbcTemplate jdbc;
    private final NotificationResourceService resources;

    public CustomerNotificationResourceQueries(JdbcTemplate jdbc, NotificationResourceService resources) {
        this.jdbc = jdbc;
        this.resources = resources;
    }

    /** Existing accounts for owners the caller may recharge right now; other owners are omitted. */
    @Transactional(readOnly=true)
    public List<Balance> balances(String tenant, String userId) {
        var allowed = resources.customerOptions(tenant, userId).destinations();
        if (allowed.isEmpty()) return List.of();
        var keys = new HashSet<String>();
        for (var destination : allowed) keys.add(destination.ownerType() + ":" + destination.ownerId());
        return jdbc.query("SELECT * FROM notification_resource_accounts WHERE tenant_id=? ORDER BY owner_type,owner_name,id",
                (rs, n) -> new Balance(rs.getString("id"), rs.getString("owner_type"), rs.getString("owner_id"),
                        rs.getString("owner_name"), rs.getLong("sms_total"), rs.getLong("sms_used"),
                        rs.getLong("sms_total") - rs.getLong("sms_used"), rs.getLong("voice_total"),
                        rs.getLong("voice_used"), rs.getLong("voice_total") - rs.getLong("voice_used"),
                        rs.getBoolean("enabled"), rs.getObject("updated_at", OffsetDateTime.class)), tenant)
                .stream().filter(balance -> keys.contains(balance.ownerType() + ":" + balance.ownerId())).toList();
    }

    /** Paginated ledger for one account the caller may still recharge; revoked access is rejected. */
    @Transactional(readOnly=true)
    public Page<Entry> ledger(String tenant, String userId, String accountId, int page, Integer size) {
        var owners = jdbc.query("SELECT owner_type,owner_id FROM notification_resource_accounts WHERE tenant_id=? AND id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, tenant, accountId);
        if (owners.isEmpty()) throw ApiError.missing();
        var owner = owners.getFirst();
        boolean allowed = resources.customerOptions(tenant, userId).destinations().stream()
                .anyMatch(destination -> destination.ownerType().equals(owner[0]) && destination.ownerId().equals(owner[1]));
        if (!allowed) throw ApiError.forbidden("没有该充值账户的权限");
        var p = Pagination.of(page, size, 20);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id=? AND account_id=?", Long.class, tenant, accountId);
        var items = jdbc.query("SELECT id,account_id,channel,kind,quantity,balance_after,created_at FROM notification_resource_ledger WHERE tenant_id=? AND account_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new Entry(rs.getString("id"), rs.getString("account_id"), rs.getString("channel"), rs.getString("kind"),
                        rs.getLong("quantity"), rs.getLong("balance_after"), rs.getObject("created_at", OffsetDateTime.class)),
                tenant, accountId, p.size(), p.offset());
        return new Page<>(items, total, p.page(), p.size());
    }
}
