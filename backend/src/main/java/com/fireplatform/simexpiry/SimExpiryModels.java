package com.fireplatform.simexpiry;

import java.time.LocalDate;
import java.util.List;

public final class SimExpiryModels {
    private SimExpiryModels() { }
    public record Config(String tenant_id, boolean enabled, String timezone, Integer lead_days, Integer repeat_interval_days, Integer repeat_count, long revision, String updated_at) { }
    public record ConfigInput(boolean enabled,String timezone,Integer leadDays,Integer repeatIntervalDays,Integer repeatCount,long expectedRevision,String reason) { }
    public record Page<T>(List<T> items,long total,int page,int size,long pages) { }
    public record Recipient(String user_id, String added_at, boolean eligible) { }
    public record Round(String id, String tenant_id, String target_kind, String target_id, LocalDate expires_on, int generation, String state, long revision, long reminder_count, LocalDate last_reminded_on, String opened_at, String handled_at, String handled_by, String handle_reason, String reopened_from, String reopen_reason, boolean read_by_me, String read_at) { }
    public record Scan(LocalDate today,int created,int repeated,int superseded) { }
    public record ReadReceipt(String round_id,String user_id,String read_at) { }
    public record RecipientInput(String userId) { }
    public record HandleInput(String reason,long expectedRevision) { }
}
