package com.fireplatform.ingestion;

import com.fireplatform.identity.AuthService;
import com.fireplatform.product.PublishedMessageNormalizer;
import com.fireplatform.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreviewCommands {
    private final JdbcTemplate jdbc; private final IngestionGuard guard; private final MessageQueries messages; private final PublishedMessageNormalizer normalizer; private final IdempotencyStore idem; private final AuditService audit; private final Clock clock;
    public PreviewCommands(JdbcTemplate jdbc,IngestionGuard guard,MessageQueries messages,PublishedMessageNormalizer normalizer,IdempotencyStore idem,AuditService audit,Clock clock) { this.jdbc=jdbc; this.guard=guard; this.messages=messages; this.normalizer=normalizer; this.idem=idem; this.audit=audit; this.clock=clock; }
    @Transactional public IngestionModels.PreviewResult preview(AuthService.Identity actor,String tenant,String id,String key,IngestionInputs.Preview in,String request) {
        guard.begin(actor,tenant,"messages:preview",key,false); var raw=messages.raw(actor.user().id(),tenant,id,true); IngestionLimits.text(in.reason(),1000);
        String hash=idem.fingerprint(List.of(tenant,id,in.expectedRevision(),in.reason()));
        var replay=idem.replay(actor.user().id(),"message-preview",key,hash,IngestionModels.PreviewReference.class);
        if(replay!=null) return new IngestionModels.PreviewResult(replay.message(),messages.preview(actor.user().id(),tenant,id,replay.previewId(),guard.raw(actor)));
        if(raw.summary().revision()!=in.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT","预览投影已更新，请刷新");
        if(raw.summary().productVersionId()==null || !raw.summary().bindingState().equals("BOUND")) throw ApiError.conflict("CAPTURED_VERSION_REQUIRED","捕获时未绑定版本的报文暂不能预览");
        PublishedMessageNormalizer.Normalized normalized;
        try { normalized=normalizer.normalize(raw.summary().productVersionId(),IngestionLimits.decode(raw.payload())); }
        catch(ApiError e) { if(e.status==404 || e.status==409) throw ApiError.conflict("CAPTURED_VERSION_UNAVAILABLE","捕获版本不可用于历史预览"); throw e; }
        var reported=IngestionLimits.reported(raw.summary().reportedAt()); String preview=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO message_previews(id,raw_message_id,tenant_id,product_version_id,event_type,battery_percent,readings_json,diagnostics_json,reported_at_iso,time_quality,raw_event_json,created_at,actor_id,reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",preview,id,tenant,raw.summary().productVersionId(),normalized.eventType(),normalized.batteryPercent(),idem.json(normalized.readings()),idem.json(normalized.diagnostics()),reported.value()==null ? null : reported.value().toString(),reported.quality(),idem.json(normalized.rawEvent()),OffsetDateTime.now(clock),actor.user().id(),in.reason());
        jdbc.update("UPDATE raw_messages SET revision=revision+1,latest_preview_id=?,parse_status='PREVIEWED' WHERE id=?",preview,id);
        var summary=messages.raw(actor.user().id(),tenant,id,false).summary();
        // Persistent replay contains only a safe summary and an immutable attempt ID, never raw_event/payload.
        idem.save(actor.user().id(),"message-preview",key,hash,new IngestionModels.PreviewReference(summary,preview));
        audit.record(actor.user().id(),tenant,"MESSAGE_PREVIEW",preview,"SUCCESS",request);
        return new IngestionModels.PreviewResult(summary,messages.preview(actor.user().id(),tenant,id,preview,guard.raw(actor)));
    }
}
