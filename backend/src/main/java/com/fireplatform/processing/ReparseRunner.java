package com.fireplatform.processing;

import com.fireplatform.ingestion.CapturedMessageCatalog;
import com.fireplatform.product.PublishedMessageNormalizer;
import com.fireplatform.shared.ApiError;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** N07 runner：claim → 锁外解析 → 有界结果。解析结果只落任务表，不写任何业务事实。 */
@Service
public class ReparseRunner {
    private final ReparseQueue queue; private final ReparseCommands commands; private final JdbcTemplate jdbc; private final PublishedMessageNormalizer normalizer;
    public ReparseRunner(ReparseQueue queue,ReparseCommands commands,JdbcTemplate jdbc,PublishedMessageNormalizer normalizer) { this.queue=queue; this.commands=commands; this.jdbc=jdbc; this.normalizer=normalizer; }

    public boolean runOne(String owner) {
        if(Thread.currentThread().isInterrupted()) return false;
        var lease=queue.claim(owner); if(lease==null) return false;
        try {
            var row=jdbc.queryForList("SELECT tenant_id,raw_message_id,product_version_id FROM reparse_jobs WHERE id=?",lease.jobId()).getFirst();
            String tenant=(String)row.get("tenant_id"), raw=(String)row.get("raw_message_id"), version=(String)row.get("product_version_id");
            CapturedMessageCatalog.Fact fact=commands.fact(tenant,raw);
            var normalized=normalizer.normalize(version,fact.payload());
            queue.succeed(lease,commands.json(new ReparseModels.Parsed(normalized.eventType(),normalized.batteryPercent()==null?null:normalized.batteryPercent().toPlainString(),normalized.diagnostics().isEmpty())));
        } catch(ApiError e) { queue.retry(lease,"NORMALIZATION_REJECTED"); }
        catch(RuntimeException e) { queue.retry(lease,"TECHNICAL_FAILURE"); }
        return true;
    }
}
