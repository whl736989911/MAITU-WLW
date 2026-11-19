package com.fireplatform.legacyingest.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 旧平台产品解码器（pa_code）导入：不可变修订、可预览，不覆盖历史。 */
@Service
public class LegacyDecoderCommands {
    public static final int MAX_SOURCE_BYTES = 262_144;
    private static final int MAX_PREVIEW_EVENTS_BYTES = 32_768;
    public record Revision(String legacyProductId,long revision,String sourceSha256,int bytes,OffsetDateTime createdAt,boolean active) { }
    public record Preview(boolean ok,String code,String message,int events,String eventsJson,String sourceSha256) { }

    private final JdbcTemplate jdbc; private final LegacyCodecRunner runner; private final AuditService audit;
    private final ManagementGuard guard; private final IdempotencyStore idempotency; private final ObjectMapper mapper;
    public LegacyDecoderCommands(JdbcTemplate jdbc,LegacyCodecRunner runner,AuditService audit,ManagementGuard guard,
                                 IdempotencyStore idempotency,ObjectMapper mapper) {
        this.jdbc=jdbc; this.runner=runner; this.audit=audit; this.guard=guard; this.idempotency=idempotency; this.mapper=mapper;
    }

    static void productId(String value) {
        if(value==null || !value.matches("[A-Za-z0-9._:-]{1,64}")) throw ApiError.bad("旧产品标识格式未确认");
    }
    static byte[] source(String value) {
        if(value==null || value.isBlank()) throw ApiError.bad("解码器源码为空");
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_SOURCE_BYTES) throw ApiError.bad("解码器源码超过"+MAX_SOURCE_BYTES+"字节");
        if(!value.contains("rawDataToProtocol") && !value.contains("function decode")) throw ApiError.bad("解码器必须实现 rawDataToProtocol 或 decode");
        return bytes;
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }

    @Transactional public Revision importSource(AuthService.Identity actor,String legacyProductId,String source,String reason,
                                               String idemKey,String requestId) {
        productId(legacyProductId);
        byte[] bytes=source(source);
        if(reason==null || reason.trim().isEmpty() || reason.length()>1000) throw ApiError.bad("必须说明导入原因");
        idempotency.validateKey(idemKey);
        guard.begin(actor,"legacy-ingest:write",null,"management");
        String hash=idempotency.fingerprint(List.of(legacyProductId,sha(bytes),reason.trim()));
        var replay=idempotency.replay(actor.user().id(),"LEGACY_DECODER_IMPORT",idemKey,hash,Revision.class);
        if(replay!=null) return replay;
        Long next=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM legacy_product_decoders WHERE legacy_product_id=?",Long.class,legacyProductId);
        long revision=next==null?1:next; var now=OffsetDateTime.now();
        jdbc.update("INSERT INTO legacy_product_decoders(legacy_product_id,revision,source,source_sha256,created_at) VALUES (?,?,?,?,?)",legacyProductId,revision,source,sha(bytes),now);
        audit.recordReason(actor.user().id(),null,"LEGACY_DECODER_IMPORTED",legacyProductId+":"+revision,"SUCCESS",requestId,reason);
        var result=new Revision(legacyProductId,revision,sha(bytes),bytes.length,now,true);
        idempotency.save(actor.user().id(),"LEGACY_DECODER_IMPORT",idemKey,hash,result);
        return result;
    }

    public List<Revision> revisions(String legacyProductId) {
        productId(legacyProductId);
        List<Revision> rows=jdbc.query("SELECT legacy_product_id,revision,source_sha256,source,created_at FROM legacy_product_decoders WHERE legacy_product_id=? ORDER BY revision",
            (rs,n)->new Revision(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4).getBytes(StandardCharsets.UTF_8).length,rs.getObject(5,OffsetDateTime.class),false),
            legacyProductId);
        if(rows.isEmpty()) return rows;
        var result=new ArrayList<Revision>(rows.size());
        long latest=rows.getLast().revision();
        for(Revision row:rows) result.add(new Revision(row.legacyProductId(),row.revision(),row.sourceSha256(),row.bytes(),row.createdAt(),row.revision()==latest));
        return List.copyOf(result);
    }

    /** 预览不写库：用给定源码与十六进制报文跑一次沙箱。 */
    public Preview preview(String source,String payloadHex,Map<String,?> attrs) {
        byte[] bytes=source(source);
        return run(source,payloadHex,attrs,sha(bytes));
    }

    /** 预览已存储的某个修订，不返回源码本身。 */
    public Preview previewRevision(String legacyProductId,long revision,String payloadHex,Map<String,?> attrs) {
        productId(legacyProductId);
        if(revision<1) throw ApiError.bad("修订号无效");
        var rows=jdbc.query("SELECT source,source_sha256 FROM legacy_product_decoders WHERE legacy_product_id=? AND revision=?",
            (rs,n)->new String[]{rs.getString(1),rs.getString(2)},legacyProductId,revision);
        if(rows.isEmpty()) throw ApiError.missing();
        return run(rows.getFirst()[0],payloadHex,attrs,rows.getFirst()[1]);
    }

    private Preview run(String source,String payloadHex,Map<String,?> attrs,String sourceSha) {
        if(payloadHex==null || !payloadHex.matches("(?:[0-9A-Fa-f]{2})+")) throw ApiError.bad("payload_hex 必须为偶数长度十六进制");
        CodecResult result=runner.run(source,payloadHex.toUpperCase(Locale.ROOT),attrs==null?Map.of():attrs);
        if(!result.ok()) return new Preview(false,result.errorCode(),result.errorMessage(),0,null,sourceSha);
        String eventsJson;
        try { eventsJson=mapper.writeValueAsString(result.events()); }
        catch(Exception e) { return new Preview(false,"CODEC_INVALID_OUTPUT","解码器输出无法序列化",result.events().size(),null,sourceSha); }
        String message=null;
        if(eventsJson.getBytes(StandardCharsets.UTF_8).length>MAX_PREVIEW_EVENTS_BYTES) { eventsJson=null; message="事件输出超过预览显示上限，未展示明细"; }
        return new Preview(true,null,message,result.events().size(),eventsJson,sourceSha);
    }

    /** 供测试与诊断：当前生效的源码。 */
    public String activeSource(String legacyProductId) {
        var rows=jdbc.queryForList("SELECT source FROM legacy_product_decoders WHERE legacy_product_id=? ORDER BY revision DESC LIMIT 1",String.class,legacyProductId);
        if(rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    /** 事件类型映射：旧平台 method → 我们的 kind。 */
    public static String kind(String method) {
        if(method==null) return "OTHER";
        String m=method.toLowerCase(Locale.ROOT);
        if(m.startsWith("thing.event.heartbeat")) return "HEARTBEAT";
        if(m.startsWith("thing.event.alarm")||m.startsWith("thing.event.fire")) return "ALARM";
        if(m.startsWith("thing.event.fault")) return "FAULT";
        if(m.startsWith("thing.event.param")) return "PARAM";
        return "OTHER";
    }

    /** 事件码 → 火警/恢复语义；1001001 为旧平台火灾报警，未知码保持 UNKNOWN 不猜测。 */
    public static String fireSemantics(String deviceStateCode) {
        if(deviceStateCode==null) return "UNKNOWN";
        String code=deviceStateCode.trim();
        if(code.equals("1001001")) return "FIRE";
        return "UNKNOWN";
    }
}
