package com.fireplatform.legacyingest.ingest;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.legacyingest.*;
import com.fireplatform.shared.ApiError;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegacyIngestService {
    public record Result(String id,String state,boolean duplicate,int events,String failureCode) { }
    private record Device(String tenant,String id,String legacyProductId,String eui,OffsetDateTime verifiedFrom) { }
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final LegacyEventSink sink;private final com.fireplatform.legacyingest.codec.LegacyDecodeService decoder;private final Map<String,LegacyIngressNormalizer> normalizers;private final com.fireplatform.identity.CoordinationFence coordination;
    public LegacyIngestService(JdbcTemplate jdbc,ObjectMapper mapper,LegacyEventSink sink,com.fireplatform.legacyingest.codec.LegacyDecodeService decoder,List<LegacyIngressNormalizer> normalizers,com.fireplatform.identity.CoordinationFence coordination){this.jdbc=jdbc;this.mapper=mapper;this.sink=sink;this.decoder=decoder;this.normalizers=new HashMap<>();for(var normalizer:normalizers){String key=normalizer instanceof LorawanTurboNormalizer?"lorawan_turbo":normalizer instanceof AepTurboNormalizer?"aep_turbo":normalizer instanceof TyyTurboNormalizer?"tyy_turbo":normalizer instanceof TurmassNormalizer?"turmass":normalizer instanceof MaituMqttNormalizer?"maitu_mqtt":"formttl";this.normalizers.put(key,normalizer);if(normalizer instanceof TyyTurboNormalizer)this.normalizers.put("oceanconnect_turbo",normalizer);}this.coordination=coordination;}

    public boolean supportsPlatform(String platform) {
        return normalizers.containsKey(platform);
    }

    @Transactional public Result ingest(String ingress,String platform,String messageType,String rawBody){
        String effective=platform==null||platform.isBlank()?"formttl":platform;
        final JsonNode body;
        try { body=parseBody(ingress,effective,rawBody); }
        catch(Exception e) { throw ApiError.bad("旧平台报文格式无效"); }
        if(body==null||!body.isObject())throw ApiError.bad("旧平台报文格式无效");
        var normalizer=normalizers.get(effective);
        if(normalizer==null)throw ApiError.bad("旧平台类型无效");
        var parsed=normalizer.parse(body,effective,messageType);
        if(parsed.vendorDeviceId()==null||parsed.vendorDeviceId().isBlank())throw ApiError.bad("报文缺少设备标识");
        byte[] raw=rawBody.getBytes(StandardCharsets.UTF_8);
        String sha=sha256(raw),external=external(parsed,sha);
        // Ownership resolution and applying its facts share the same cutover fence.
        coordination.lock();
        var duplicate=jdbc.query("SELECT id,state,failure_code FROM legacy_ingest_messages WHERE ingress=? AND vendor_platform=? AND external_key=?",
            (rs,n)->new Result(rs.getString(1),rs.getString(2),true,0,rs.getString(3)),ingress,effective,external);
        if(!duplicate.isEmpty()){var prior=duplicate.getFirst();jdbc.update("UPDATE legacy_ingest_messages SET duplicate_count=duplicate_count+1 WHERE id=?",prior.id());return prior;}
        Device device=findDevice(effective,parsed.vendorDeviceId());
        String tenant=device==null?null:device.tenant();
        if(device!=null&&!tenantExists(tenant))throw ApiError.conflict("LEGACY_TENANT_MISSING","设备归属单位不存在");
        String id=UUID.randomUUID().toString(),failure=device==null?"UNKNOWN_DEVICE":null;
        String state=device==null?"FAILED":parsed.pendingDecoder()?"PENDING_DECODER":"NORMALIZED";
        OffsetDateTime received=OffsetDateTime.now(ZoneOffset.UTC);
        LegacyNormalizedEvent normalized=new LegacyNormalizedEvent(tenant,device==null?null:device.id(),parsed.vendorDeviceId(),effective,parsed.kind(),parsed.observedAt(),parsed.battery(),parsed.pendingDecoder(),parsed.events());
        String imei="aep_turbo".equals(effective)?reportedAepImei(body):null;
        String type=body.path("messageType").textValue();
        String aepEvidence="HTTP_NOTIFY".equals(ingress) && body.path("deviceId").asText().equals(parsed.vendorDeviceId())
            && ("dataReport".equals(type) || "eventReport".equals(type)) ? imei : null;
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,vendor_device_id,device_id,payload_raw,payload_sha256,payload_bytes,received_at,observed_at,state,failure_code,duplicate_count,aep_reported_imei) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)",
            id,tenant,ingress,effective,external,parsed.vendorDeviceId(),device==null?null:device.id(),rawBody,sha,raw.length,received,parsed.observedAt(),state,failure,aepEvidence);
        String aliasRejection=device==null?null:checkVerifiedAlias(device,imei,normalized);
        if(device!=null&&aliasRejection==null&&parsed.pendingDecoder()&&device.legacyProductId()!=null) {
            String hex=payloadHex(effective,body);
            if(hex!=null) {
                LegacyNormalizedEvent decoded=null;
                try{decoded=decoder.decode(new com.fireplatform.legacyingest.codec.LegacyDecodeService.DeviceBinding(tenant,device.id(),effective,device.legacyProductId(),Map.of()),parsed.vendorDeviceId(),hex);}catch(RuntimeException ignored){}
                if(decoded!=null&&!decoded.pendingDecoder()&&!decoded.events().isEmpty()) {
                    var decodedEvents=decoded.events().stream().map(event->event.observedAt()==null&&parsed.observedAt()!=null
                        ?new LegacyNormalizedEvent.LegacyEvent(event.kind(),event.method(),event.paramsJson(),parsed.observedAt()):event).toList();
                    normalized=new LegacyNormalizedEvent(tenant,device.id(),parsed.vendorDeviceId(),effective,decoded.kind(),parsed.observedAt(),decoded.battery()==null?parsed.battery():decoded.battery(),false,decodedEvents);
                    state="NORMALIZED";
                    jdbc.update("UPDATE legacy_ingest_messages SET state=? WHERE id=?",state,id);
                }
            }
        }
        if(device!=null&&aliasRejection==null&&device.verifiedFrom()!=null)aliasRejection=checkVerifiedAlias(device,imei,normalized);
        var rejection=device==null||aliasRejection!=null?null:LegacyOwnershipBoundary.check(jdbc,device.id(),normalized.observedAt(),normalized.events());
        if(device==null||aliasRejection!=null||rejection!=null) {
            failure=device==null?"UNKNOWN_DEVICE":aliasRejection!=null?aliasRejection:rejection.reason();
            // No invented customer, and no old/undated payload visible to the new owner.
            jdbc.update("UPDATE legacy_ingest_messages SET tenant_id=NULL,device_id=NULL,state='FAILED',failure_code=? WHERE id=?",failure,id);
            jdbc.update("INSERT INTO legacy_ingest_quarantines(message_id,candidate_device_id,transfer_id,reason,recorded_at) VALUES (?,?,?,?,?)",
                id,device==null?null:device.id(),rejection==null?null:rejection.transferId(),failure,received);
            return new Result(id,"FAILED",false,0,failure);
        }
        sink.accept(id,normalized);
        return new Result(id,state,false,normalized.events().size(),null);
    }

    private JsonNode parseBody(String ingress,String platform,String rawBody) throws Exception {
        try {
            JsonNode json=mapper.readTree(rawBody);
            if(json!=null&&json.isObject())return json;
        } catch(Exception ignored) { }
        return formMessage(ingress,platform,rawBody);
    }

    private JsonNode formMessage(String ingress,String platform,String rawBody) throws Exception {
        if(!"HTTP_FORM_TTL".equals(ingress)||!"formttl".equals(platform)||rawBody==null)
            throw new IllegalArgumentException("form envelope not allowed");
        String msg=null;
        for(String field:rawBody.split("&",-1)) {
            int separator=field.indexOf('=');
            String key=java.net.URLDecoder.decode(separator<0?field:field.substring(0,separator),StandardCharsets.UTF_8);
            if(!"msg".equals(key))continue;
            if(msg!=null)throw new IllegalArgumentException("duplicate msg");
            msg=java.net.URLDecoder.decode(separator<0?"":field.substring(separator+1),StandardCharsets.UTF_8);
        }
        JsonNode parsed=msg==null?null:mapper.readTree(msg);
        if(parsed==null||!parsed.isObject())throw new IllegalArgumentException("invalid msg");
        return parsed;
    }
    private String external(LegacyIngressNormalizer.Parsed p,String sha){
        String identity=sha256((p.vendorDeviceId()+"\u0000"+Objects.toString(p.observedAt(),"")+"\u0000"+sha).getBytes(StandardCharsets.UTF_8));
        String serial=p.serial();boolean usable=false;
        if(serial!=null&&!serial.isBlank()){try{usable=Long.parseLong(serial.trim())>0;}catch(NumberFormatException ignored){usable=true;}}
        if(usable)return boundedKey("SER:"+identity+":"+serial);
        String fcnt=p.fcnt();
        if(fcnt!=null&&!fcnt.isBlank())return boundedKey("FCNT:"+identity+":"+fcnt);
        return "HASH:"+identity;
    }
    private String boundedKey(String key){return key.length()<=160?key:"HASH:"+sha256(key.getBytes(StandardCharsets.UTF_8));}
    private Device findDevice(String platform,String vendor) {
        var aliases=jdbc.query("SELECT DISTINCT d.tenant_id,d.id,pv.legacy_product_id,d.device_eui,CASE WHEN a.verified_platform IS NOT NULL THEN a.created_at END FROM device_aliases a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id LEFT JOIN product_versions pv ON pv.id=d.product_version_id WHERE a.alias_value=? AND (a.verified_platform IS NULL OR a.verified_platform=?) AND d.archived_at IS NULL",
            this::readDevice,vendor,platform);
        if(aliases.size()==1)return aliases.getFirst();
        if(aliases.size()>1)return null;
        var devices=jdbc.query("SELECT d.tenant_id,d.id,pv.legacy_product_id,d.device_eui,CAST(NULL AS TIMESTAMP WITH TIME ZONE) FROM devices d LEFT JOIN product_versions pv ON pv.id=d.product_version_id WHERE d.device_eui=? AND d.archived_at IS NULL",
            this::readDevice,vendor);
        if(!devices.isEmpty())return devices.size()==1?devices.getFirst():null;
        // A repaired device keeps its physical identifier, even if its old vendor alias was not re-entered.
        // Only a unique current legacy owner with an explicit lifecycle boundary may receive these facts.
        var replacements=jdbc.query("SELECT DISTINCT d.tenant_id,d.id,pv.legacy_product_id,d.device_eui,CASE WHEN a.verified_platform IS NOT NULL THEN a.created_at END FROM device_aliases a JOIN devices old ON old.id=a.device_id AND old.tenant_id=a.tenant_id JOIN devices d ON d.device_eui=old.device_eui JOIN product_versions pv ON pv.id=d.product_version_id WHERE a.alias_value=? AND (a.verified_platform IS NULL OR a.verified_platform=?) AND old.archived_at IS NOT NULL AND d.archived_at IS NULL AND d.ownership_from IS NOT NULL AND pv.schema_format='LEGACY_ATTR_JS_V1'",
            this::readDevice,vendor,platform);
        return replacements.size()==1?replacements.getFirst():null;
    }
    private Device readDevice(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Device(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getObject(5,OffsetDateTime.class));
    }

    // p500 may carry IMEI at the top level, p323 in payload; disagreements cannot prove identity.
    private static String reportedAepImei(JsonNode body) {
        String top=body.path("IMEI").asText(null), nested=body.path("payload").path("IMEI").asText(null);
        if(top==null && nested==null)return null;
        if(top!=null && nested!=null && !top.equals(nested))return "";
        String imei=top==null?nested:top;
        return imei.matches("[0-9]{15}")?imei:"";
    }

    private String checkVerifiedAlias(Device device,String imei,LegacyNormalizedEvent event) {
        if(device.verifiedFrom()==null)return null;
        if(imei!=null&&!device.eui().equals(imei))return "AEP_IMEI_MISMATCH";
        if(event.observedAt()==null)return "AEP_BINDING_TIME_UNKNOWN";
        if(event.observedAt().isBefore(device.verifiedFrom())
            || event.events().stream().anyMatch(e->e.observedAt()!=null&&e.observedAt().isBefore(device.verifiedFrom())))return "PRE_AEP_BINDING";
        return null;
    }
    private String payloadHex(String platform,JsonNode body){JsonNode value=null;if(platform.equals("lorawan_turbo")||platform.equals("turmass")||platform.equals("maitu_mqtt"))value=body.get("data");else if(platform.equals("tyy_turbo")||platform.equals("oceanconnect_turbo")){JsonNode payload=body.path("service").path("data").path("payload");if(payload.isTextual()&&NormalizerSupport.hex(payload.textValue()))value=payload;}
        else if(platform.equals("aep_turbo")){JsonNode payload=body.path("payload");String type=NormalizerSupport.text(body,"messageType");if("lwm2m".equalsIgnoreCase(NormalizerSupport.text(body,"protocol"))||"dataReport".equals(type)||"eventReport".equals(type)){try{value=com.fasterxml.jackson.databind.node.TextNode.valueOf(java.util.HexFormat.of().formatHex(mapper.writeValueAsBytes(body)));}catch(Exception ignored){}}if(value==null)for(String key:List.of("data","payload","hex","rawData","raw_data","dataHex")){JsonNode candidate=payload.get(key);if(candidate!=null&&candidate.isTextual()&&NormalizerSupport.hex(candidate.textValue())){value=candidate;break;}}}return value!=null&&value.isTextual()&&NormalizerSupport.hex(value.textValue())?value.textValue():null;}
    private boolean tenantExists(String tenant){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tenants WHERE id=?)",Boolean.class,tenant));}
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}

