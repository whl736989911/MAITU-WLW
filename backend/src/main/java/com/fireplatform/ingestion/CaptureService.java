package com.fireplatform.ingestion;

import com.fireplatform.device.DeviceBindingCatalog;
import com.fireplatform.product.PublishedMessageNormalizer;
import com.fireplatform.product.PublishedVersionCatalog;
import com.fireplatform.shared.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaptureService {
    private final JdbcTemplate jdbc; private final SourceAuthentication authentication; private final DeviceBindingCatalog devices; private final PublishedVersionCatalog versions; private final PublishedMessageNormalizer normalizer; private final MessageQueries messages; private final AuditService audit; private final Clock clock;
    public CaptureService(JdbcTemplate jdbc,SourceAuthentication authentication,DeviceBindingCatalog devices,PublishedVersionCatalog versions,PublishedMessageNormalizer normalizer,MessageQueries messages,AuditService audit,Clock clock) { this.jdbc=jdbc; this.authentication=authentication; this.devices=devices; this.versions=versions; this.normalizer=normalizer; this.messages=messages; this.audit=audit; this.clock=clock; }
    private IngestionModels.Receipt receipt(IngestionModels.RawSummary raw,boolean duplicate) { return new IngestionModels.Receipt(raw.id(),raw.sourceId(),raw.sourceMessageId(),raw.bindingState(),"NOT_PREVIEWED","NOT_APPLIED",duplicate,raw.receivedAt()); }
    @Transactional public IngestionModels.Receipt capture(String sourceId,String authorization,IngestionInputs.Envelope in,String request) {
        var source=authentication.lock(sourceId,authorization);
        if(source.isSimulated()) throw ApiError.conflict("SOURCE_RETIRED","模拟接入来源已停用");
        if(in.payload()==null || in.reportedAt()!=null && in.reportedAt().length()>IngestionLimits.REPORTED_AT_LENGTH) throw ApiError.bad("接收envelope无效");
        byte[] payload=IngestionLimits.utf8(in.payload()), reported=IngestionLimits.utf8(in.reportedAt());
        if(payload.length>IngestionLimits.PAYLOAD_BYTES) throw ApiError.bad("原始payload超过资源限制"); normalizer.validatePayload(in.payload());
        OffsetDateTime occurred=null;
        if(in.occurredAt()!=null) { try { occurred=OffsetDateTime.parse(in.occurredAt()); } catch(DateTimeParseException ignored) { occurred=null; } }
        var existing=jdbc.queryForList("SELECT id FROM raw_messages WHERE source_id=? AND source_message_id=?",String.class,sourceId,in.sourceMessageId());
        if(!existing.isEmpty()) {
            var old=messages.rawSystem(source.tenantId(),existing.getFirst(),true);
            var oldSeq=jdbc.queryForObject("SELECT sequence_no FROM raw_messages WHERE id=?",Long.class,old.summary().id());
            var oldOccurred=jdbc.queryForObject("SELECT occurred_at FROM raw_messages WHERE id=?",OffsetDateTime.class,old.summary().id());
            var oldOccurredText=jdbc.queryForObject("SELECT occurred_at_raw FROM raw_messages WHERE id=?",String.class,old.summary().id());
            if(!old.summary().vendorDeviceId().equals(in.vendorDeviceId()) || !Objects.equals(old.summary().reportedAt(),in.reportedAt()) || !Arrays.equals(old.payload(),payload) || !Objects.equals(oldSeq,in.sequence()) || !Objects.equals(oldOccurredText,in.occurredAt()) || (occurred!=null && (oldOccurred==null || oldOccurred.toInstant().compareTo(occurred.toInstant())!=0)) || (occurred==null && oldOccurred!=null)) throw ApiError.conflict("MESSAGE_ID_CONFLICT","消息身份已存在且内容不同");
            jdbc.update("UPDATE raw_messages SET duplicate_count=duplicate_count+1 WHERE id=?",old.summary().id());
            jdbc.update("UPDATE ingestion_sources SET duplicate_count=duplicate_count+1,last_received_at=? WHERE id=?",OffsetDateTime.now(clock),sourceId);
            audit.record(null,source.tenantId(),"CAPTURE_DUPLICATE",old.summary().id(),"SUCCESS",request); return receipt(old.summary(),true);
        }
        var bindings=jdbc.queryForList("SELECT binding_id,device_id FROM active_source_bindings WHERE source_id=? AND vendor_device_id=?",sourceId,in.vendorDeviceId());
        String binding=null,device=null,name=null,address=null,version=null,state="UNBOUND";
        if(!bindings.isEmpty()) {
            var active=bindings.getFirst(); binding=(String)active.get("binding_id"); var locked=devices.lockSystem(source.tenantId(),(String)active.get("device_id"));
            if(locked.productVersionId()==null) throw ApiError.conflict("DEVICE_VERSION_REQUIRED","绑定设备尚无发布版本"); versions.requirePublished(locked.productVersionId());
            device=locked.id(); name=locked.name(); address=locked.address(); version=locked.productVersionId(); state="BOUND";
        }
        String id=UUID.randomUUID().toString(); var now=OffsetDateTime.now(clock);
        jdbc.update("INSERT INTO raw_messages(id,tenant_id,source_id,source_name,source_message_id,vendor_device_id,device_id,device_name,device_address,binding_id,product_version_id,received_at,reported_at_utf8,payload_utf8,payload_bytes,payload_sha256,binding_state,sequence_no,occurred_at,capture_provenance,occurred_at_raw) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'AUTHENTICATED_SOURCE',?)",id,source.tenantId(),sourceId,source.name(),in.sourceMessageId(),in.vendorDeviceId(),device,name,address,binding,version,now,reported,payload,payload.length,IngestionLimits.sha256(payload),state,in.sequence(),occurred,in.occurredAt());
        jdbc.update("UPDATE ingestion_sources SET message_count=message_count+1,last_received_at=? WHERE id=?",now,sourceId);
        audit.record(null,source.tenantId(),"CAPTURE",id,"SUCCESS",request);
        // Read the stored timestamptz precision, so PG's first ACK exactly equals every retry ACK.
        return receipt(messages.rawSystem(source.tenantId(),id,false).summary(),false);
    }
}
