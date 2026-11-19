package com.fireplatform.hikvision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.shared.*;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class HikvisionCallbackService {
    public static final String RECEIVER_PATH="/api/hooks/hikvision/{credential}";
    public record View(boolean enabled,String public_origin,boolean credential_configured,boolean secret_storage_ready,long revision,
        String receiver_path,boolean worker_enabled,long pending_count,long processed_count,long attention_count,OffsetDateTime last_received_at,
        List<String> supported_message_types,List<String> enabled_message_types) { }
    public record Write(Long expected_revision,Boolean enabled,String public_origin,String credential,List<String> enabled_message_types) { }
    public record Reparse(Long expected_revision) { }
    public record Subscription(String message_type,String destination_origin,boolean matches_receiver,OffsetDateTime updated_at) { }
    public record SubscriptionResult(OffsetDateTime checked_at,List<Subscription> items) { }
    public record Receipt(String id,OffsetDateTime received_at,OffsetDateTime last_received_at,long duplicate_count,String state,
        List<String> message_types,int submitted_records,int unsupported_messages,String error_code,long revision,long reparse_count,OffsetDateTime last_reparse_at) { }
    private record Config(boolean enabled,String origin,String cipher,String nonce,String account,long revision,List<String> enabledTypes) { }
    private record ReparseTarget(String account,String model,Receipt receipt) { }
    private static final String RECEIPT_COLUMNS="id,received_at,last_received_at,duplicate_count,state,message_types_json,submitted_records,unsupported_messages,error_code,revision,reparse_count,last_reparse_at";
    private record SubscriptionSnapshot(HikvisionClient.Session session,Config callback,String endpoint,String appKey) { }
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final HikvisionConfigSecrets secrets;private final HikvisionConfigService hikvision;
    private final ManagementGuard guard;private final IdempotencyStore idem;private final AuditService audit;private final Clock clock;private final RuntimeConfig runtime;private final com.fireplatform.identity.CoordinationFence coordination;private final org.springframework.transaction.support.TransactionTemplate transactions;private final ApplicationEventPublisher events;
    public HikvisionCallbackService(JdbcTemplate jdbc,ObjectMapper json,HikvisionConfigSecrets secrets,HikvisionConfigService hikvision,
        ManagementGuard guard,IdempotencyStore idem,AuditService audit,Clock clock,RuntimeConfig runtime,com.fireplatform.identity.CoordinationFence coordination,org.springframework.transaction.support.TransactionTemplate transactions,ApplicationEventPublisher events){
        this.jdbc=jdbc;this.json=json;this.secrets=secrets;this.hikvision=hikvision;this.guard=guard;this.idem=idem;this.audit=audit;this.clock=clock;this.runtime=runtime;this.coordination=coordination;this.transactions=transactions;this.events=events;
    }
    private Config config(boolean lock){
        var rows=jdbc.query("SELECT enabled,public_origin,credential_cipher,credential_nonce,account_fingerprint,revision,enabled_message_types_json FROM hikvision_callback_config WHERE id=1"+(lock?" FOR UPDATE":""),
            (r,n)->new Config(r.getBoolean(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),storedTypes(r.getString(7))));
        return rows.isEmpty()?new Config(false,null,null,null,null,0,HikvisionPushTypes.INITIAL):rows.getFirst();
    }
    private List<String> storedTypes(String raw){
        try{return checkedTypes(json.readValue(raw,json.getTypeFactory().constructCollectionType(List.class,String.class)));}
        catch(Exception failure){throw new ApiError(503,"HIKVISION_CALLBACK_CONFIGURATION_INVALID","接收类型配置无效，接收端暂停");}
    }
    private static List<String> checkedTypes(List<String> input){
        if(input==null||input.size()>HikvisionPushTypes.SUPPORTED.size())throw ApiError.bad("接收类型无效");
        var selected=new HashSet<String>();
        for(String code:input)if(code==null||!HikvisionPushTypes.SUPPORTED.contains(code)||!selected.add(code))throw ApiError.bad("接收类型包含未支持项或重复项");
        var ordered=new ArrayList<String>(selected.size());
        for(String code:HikvisionPushTypes.SUPPORTED)if(selected.contains(code))ordered.add(code);
        return List.copyOf(ordered);
    }
    private String account(){try{var r=hikvision.refreshRuntime();if(!r.session().configured()||!secrets.ready())return null;return secrets.fingerprint(r.endpoint()+"\n"+r.appKey());}catch(RuntimeException unavailable){return null;}}
    /** Immutable receiver snapshot for subscription intents; never exposes the credential to callers. */
    public record ReceiverConfig(String origin, String credential, String accountFingerprint, long revision, boolean enabled) { }
    public ReceiverConfig receiverForSubscription() {
        Config c = config(false);
        String credential = null;
        if (c.cipher() != null && c.nonce() != null && secrets.ready()) {
            try { credential = secrets.reveal(c.cipher(), c.nonce()); }
            catch (RuntimeException unavailable) { credential = null; }
        }
        return new ReceiverConfig(c.origin(), credential, c.account(), c.revision(), c.enabled());
    }
    boolean processingEnabled(String fingerprint){return processingEnabled(config(false),fingerprint);}
    List<String> enabledTypesForWork(String fingerprint){
        Config c=config(true);
        return processingEnabled(c,fingerprint)?c.enabledTypes():List.of();
    }
    private boolean processingEnabled(Config c,String fingerprint){
        if(!c.enabled()||c.enabledTypes().isEmpty()||!Objects.equals(c.account(),fingerprint)||c.cipher()==null||c.nonce()==null||!secrets.ready())return false;
        try{
            var h=hikvision.refreshRuntime();
            if(!h.session().configured()||!Objects.equals(secrets.fingerprint(h.endpoint()+"\n"+h.appKey()),fingerprint))return false;
            secrets.reveal(c.cipher(),c.nonce());return true;
        }catch(RuntimeException unavailable){return false;}
    }
    private boolean workerEnabled(Config c){return (runtime.roles().contains("all")||runtime.roles().contains("ingest"))&&c.account()!=null&&processingEnabled(c,c.account());}
    public View get(){return view(config(false));}
    private View view(Config c){
        return new View(c.enabled(),c.origin(),c.cipher()!=null,secrets.ready(),c.revision(),RECEIVER_PATH,workerEnabled(c),
            jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_callback_receipts WHERE state='PENDING'",Long.class),
            jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_callback_receipts WHERE state='PROCESSED'",Long.class),
            jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_callback_receipts WHERE state IN ('PARTIAL','REJECTED')",Long.class),
            jdbc.queryForObject("SELECT MAX(last_received_at) FROM hikvision_callback_receipts",OffsetDateTime.class),
            HikvisionPushTypes.SUPPORTED,c.enabledTypes());
    }
    @Transactional public View update(AuthService.Identity actor,String key,Write in,String requestId){
        guard.begin(actor,"integrations:write",null,"management");idem.validateKey(key);
        Config old=config(true);long revision=old.revision();if(in.expected_revision()==null||in.enabled()==null)throw ApiError.bad("回调配置字段无效");
        String origin=in.public_origin()==null||in.public_origin().isBlank()?null:origin(in.public_origin());String supplied=in.credential()==null?"":in.credential().trim();
        if(!supplied.isEmpty()&&!supplied.matches("[0-9a-f]{64}"))throw ApiError.bad("回调凭证格式无效");
        List<String> enabledTypes=in.enabled_message_types()==null?old.enabledTypes():checkedTypes(in.enabled_message_types());
        if(in.enabled()&&enabledTypes.isEmpty())throw ApiError.bad("启用接收端至少需要选择一种接收类型");
        var hash=idem.fingerprint(new Object[]{in.expected_revision(),in.enabled(),origin,supplied,enabledTypes});var replay=idem.replay(actor.user().id(),"hikvision-callback-config",key,hash,View.class);if(replay!=null)return replay;
        if(in.expected_revision()!=revision)throw ApiError.conflict("REVISION_CONFLICT","回调配置已更新，请刷新后重试");
        HikvisionConfigService.Runtime h=in.enabled()?hikvision.refreshRuntime():null;String currentAccount=in.enabled()?account():null;
        if(in.enabled()&&(origin==null||currentAccount==null||!h.session().configured()||!secrets.ready()))throw ApiError.conflict("HIKVISION_CALLBACK_NOT_READY","启用回调需要有效的海康凭证和独立密钥");
        if(in.enabled()&&old.account()!=null&&!Objects.equals(old.account(),currentAccount)&&supplied.isEmpty())throw ApiError.conflict("HIKVISION_CALLBACK_ACCOUNT_BOUND","海康账号已变化；必须提供新的回调凭证以显式重新绑定");
        String cipher=old.cipher(),nonce=old.nonce();if(!supplied.isEmpty()){if(!secrets.ready())throw new ApiError(503,"HIKVISION_CONFIG_KEY_UNAVAILABLE","海康独立主密钥未配置，不能保存回调凭证");var p=secrets.protect(supplied);cipher=p.cipher();nonce=p.nonce();}
        String boundAccount=old.account();if(in.enabled())boundAccount=currentAccount;else if(!supplied.isEmpty()&&account()!=null)boundAccount=account();else if(boundAccount==null&&account()!=null&&cipher!=null)boundAccount=account();
        if(in.enabled()&&(cipher==null||!Objects.equals(boundAccount,currentAccount)))throw ApiError.conflict("HIKVISION_CALLBACK_CREDENTIAL_REQUIRED","启用回调需要已保存凭证且账号绑定一致");
        OffsetDateTime now=OffsetDateTime.now(clock);long next=revision+1;
        String typesJson=idem.json(enabledTypes);
        if(revision==0)jdbc.update("INSERT INTO hikvision_callback_config(id,enabled,public_origin,credential_cipher,credential_nonce,account_fingerprint,revision,updated_at,updated_by,enabled_message_types_json) VALUES(1,?,?,?,?,?,?,?,?,?)",in.enabled(),origin,cipher,nonce,boundAccount,next,now,actor.user().id(),typesJson);
        else jdbc.update("UPDATE hikvision_callback_config SET enabled=?,public_origin=?,credential_cipher=?,credential_nonce=?,account_fingerprint=?,revision=?,updated_at=?,updated_by=?,enabled_message_types_json=? WHERE id=1",in.enabled(),origin,cipher,nonce,boundAccount,next,now,actor.user().id(),typesJson);
        audit.record(actor.user().id(),null,"hikvision-callback-config-update","hikvision-callback-config","SUCCESS",requestId);
        View view=view(new Config(in.enabled(),origin,cipher,nonce,boundAccount,next,enabledTypes));
        idem.save(actor.user().id(),"hikvision-callback-config",key,hash,view);return view;
    }
    @Transactional public void receive(String pathCredential,byte[] raw){
        coordination.lock();Config c=config(true);if(!c.enabled()||!secrets.ready()||c.account()==null||c.cipher()==null||c.nonce()==null)throw ApiError.missing();
        HikvisionConfigService.Runtime active;String account;try{active=hikvision.refreshRuntime();if(!active.session().configured())throw ApiError.missing();account=secrets.fingerprint(active.endpoint()+"\n"+active.appKey());}catch(RuntimeException e){throw ApiError.missing();}
        if(!Objects.equals(account,c.account()))throw ApiError.missing();
        String expected;try{expected=secrets.reveal(c.cipher(),c.nonce());}catch(RuntimeException e){throw ApiError.missing();}
        if(pathCredential==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),pathCredential.getBytes(StandardCharsets.US_ASCII)))throw ApiError.missing();
        if(raw==null||raw.length==0||raw.length>Limits.MAX_JSON_BODY_BYTES)throw ApiError.bad("回调JSON超过资源上界");
        String body;try{body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}catch(Exception e){throw ApiError.bad("回调JSON编码无效");}
        JsonNode root;try(var parser=json.getFactory().createParser(body)){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);root=json.readTree(parser);if(parser.nextToken()!=null)throw new IllegalArgumentException();}catch(Exception e){throw ApiError.bad("回调JSON无效");}
        JsonNode list=root==null?null:root.path("fps").path("msgList");if(root==null||!root.isObject()||!root.path("fps").isObject()||!list.isArray()||list.isEmpty())throw ApiError.bad("回调消息结构无效");
        for(JsonNode msg:list)if(!msg.isObject()||!msg.path("msgType").isTextual()||msg.path("msgType").asText().isBlank()||!msg.path("body").isObject())throw ApiError.bad("回调消息结构无效");
        String sha=sha(raw);OffsetDateTime now=OffsetDateTime.now(clock);String messages;
        try{messages=json.writeValueAsString(uniqueMessageTypes(list));}catch(Exception e){throw new IllegalStateException("callback metadata encode failed");}
        var prior=jdbc.query("SELECT id FROM hikvision_callback_receipts WHERE account_fingerprint=? AND payload_sha256=? FOR UPDATE",(r,n)->r.getString(1),account,sha);
        if(!prior.isEmpty()){jdbc.update("UPDATE hikvision_callback_receipts SET duplicate_count=duplicate_count+1,last_received_at=? WHERE id=?",now,prior.getFirst());return;}
        String receiptId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO hikvision_callback_receipts(id,account_fingerprint,model_scope,payload_sha256,payload_bytes,payload_json,received_at,last_received_at,state,message_types_json,submitted_records,unsupported_messages) VALUES(?,?,?,?,?,?,?,?,'PENDING',?,0,?)",
            receiptId,account,active.modelScope(),sha,raw.length,body,now,now,messages,unsupportedCount(list,c.enabledTypes()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){events.publishEvent(new HikvisionCallbackReceivedEvent(receiptId));}
        });
    }
    private static List<String> uniqueMessageTypes(JsonNode list){var s=new TreeSet<String>();for(JsonNode m:list)s.add(m.path("msgType").asText());return List.copyOf(s);}
    private static int unsupportedCount(JsonNode list,List<String> enabledTypes){
        int count=0;
        for(JsonNode message:list){
            String type=message.path("msgType").asText();
            if(HikvisionPushTypes.stream(type,message.path("body").path("dataType").asText())==null||!enabledTypes.contains(type))count++;
        }
        return count;
    }
    private Receipt receiptRow(java.sql.ResultSet r,int row)throws java.sql.SQLException{
        List<String> types;
        try{types=json.readValue(r.getString("message_types_json"),json.getTypeFactory().constructCollectionType(List.class,String.class));}
        catch(Exception failure){throw new IllegalStateException("Stored receipt metadata is invalid");}
        return new Receipt(r.getString("id"),r.getObject("received_at",OffsetDateTime.class),r.getObject("last_received_at",OffsetDateTime.class),
            r.getLong("duplicate_count"),r.getString("state"),List.copyOf(types),r.getInt("submitted_records"),r.getInt("unsupported_messages"),
            r.getString("error_code"),r.getLong("revision"),r.getLong("reparse_count"),r.getObject("last_reparse_at",OffsetDateTime.class));
    }
    @Transactional(readOnly=true) public Page<Receipt> receipts(int page,Integer size){
        var p=Pagination.of(page,size,20);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_callback_receipts",Long.class);
        var rows=jdbc.query("SELECT "+RECEIPT_COLUMNS+" FROM hikvision_callback_receipts ORDER BY received_at DESC,id LIMIT ? OFFSET ?",this::receiptRow,p.size(),p.offset());
        return new Page<>(rows,total,p.page(),p.size());
    }
    @Transactional public Receipt reparse(AuthService.Identity actor,String id,String key,Reparse input,String requestId){
        coordination.lock();
        guard.begin(actor,"integrations:write",null,"management");idem.validateKey(key);
        if(input.expected_revision()==null||input.expected_revision()<0)throw ApiError.bad("收据版本无效");
        String fingerprint=idem.fingerprint(new Object[]{id,input.expected_revision()});
        Receipt replay=idem.replay(actor.user().id(),"hikvision-callback-reparse",key,fingerprint,Receipt.class);
        if(replay!=null)return replay;
        Config c=config(true);
        if(c.account()==null||!processingEnabled(c,c.account()))throw ApiError.conflict("HIKVISION_CALLBACK_NOT_READY","请先核对账号、密钥并启用接收端");
        var targets=jdbc.query("SELECT account_fingerprint,model_scope,"+RECEIPT_COLUMNS+" FROM hikvision_callback_receipts WHERE id=? FOR UPDATE",
            (r,n)->new ReparseTarget(r.getString("account_fingerprint"),r.getString("model_scope"),receiptRow(r,n)),id);
        if(targets.isEmpty())throw ApiError.missing();
        ReparseTarget target=targets.getFirst();Receipt receipt=target.receipt();
        if(!Objects.equals(c.account(),target.account())||!Objects.equals(hikvision.refreshRuntime().modelScope(),target.model()))
            throw ApiError.conflict("HIKVISION_CALLBACK_ACCOUNT_BOUND","收据属于不同来源账号或型号范围，不能按当前接收端重解析");
        if(receipt.revision()!=input.expected_revision())throw ApiError.conflict("REVISION_CONFLICT","收据处理状态已变化，请刷新后重试");
        if(!Set.of("PARTIAL","REJECTED").contains(receipt.state()))throw ApiError.conflict("HIKVISION_CALLBACK_NOT_REPARSEABLE","仅待关注或拒绝处理收据允许重新解析");
        jdbc.update("UPDATE hikvision_callback_receipts SET state='PENDING',submitted_records=0,unsupported_messages=0,error_code=NULL,processed_at=NULL,revision=revision+1,reparse_count=reparse_count+1,last_reparse_at=? WHERE id=?",
            OffsetDateTime.now(clock),id);
        Receipt queued=jdbc.queryForObject("SELECT "+RECEIPT_COLUMNS+" FROM hikvision_callback_receipts WHERE id=?",this::receiptRow,id);
        audit.record(actor.user().id(),null,"hikvision-callback-reparse","hikvision-callback-receipt","SUCCESS",requestId);
        idem.save(actor.user().id(),"hikvision-callback-reparse",key,fingerprint,queued);
        return queued;
    }
    public SubscriptionResult subscriptions(AuthService.Identity actor,String requestId){
        SubscriptionSnapshot snapshot=transactions.execute(status->{
            guard.begin(actor,"integrations:write",null,"management");
            var h=hikvision.refreshRuntime();if(!h.session().configured())throw new ApiError(503,"HIKVISION_NOT_CONFIGURED","海康凭证未配置");
            return new SubscriptionSnapshot(h.session(),config(false),h.endpoint(),h.appKey());
        });
        List<Subscription> items;
        try{
            String credential=credentialForCompare(snapshot.callback());var observed=new ArrayList<Subscription>();
            for(JsonNode item:snapshot.session().readSubscriptions()){
                String type=item.path("msgType").asText("");String post=item.path("postUrl").asText("");
                observed.add(new Subscription(type,safeDestination(post),snapshot.callback().origin()!=null&&snapshot.callback().cipher()!=null&&post.equals(snapshot.callback().origin()+RECEIVER_PATH.replace("{credential}",credential)),parseTime(item.path("updateTime").asText(null))));
            }
            items=List.copyOf(observed);
        }catch(RuntimeException failure){
            try{transactions.executeWithoutResult(status->{guard.requireCurrent(actor,"integrations:write",null,"management");audit.record(actor.user().id(),null,"hikvision-callback-subscriptions-read","hikvision-callback-config","FAILED",requestId);});}catch(RuntimeException ignored){}
            throw failure;
        }
        return transactions.execute(status->{
            guard.requireCurrent(actor,"integrations:write",null,"management");
            Config current=config(false);var h=hikvision.refreshRuntime();
            if(current.revision()!=snapshot.callback().revision()||!h.session().configured()||!Objects.equals(h.endpoint(),snapshot.endpoint())||!Objects.equals(h.appKey(),snapshot.appKey()))
                throw ApiError.conflict("HIKVISION_CALLBACK_CONFIGURATION_CHANGED","海康配置在订阅查询期间变化，请重新查询");
            audit.record(actor.user().id(),null,"hikvision-callback-subscriptions-read","hikvision-callback-config","SUCCESS",requestId);
            return new SubscriptionResult(OffsetDateTime.now(clock),items);
        });
    }
    private String credentialForCompare(Config c){if(!secrets.ready()||c.cipher()==null)return "";try{return secrets.reveal(c.cipher(),c.nonce());}catch(RuntimeException e){return "";}}
    private static OffsetDateTime parseTime(String s){try{return s==null?null:OffsetDateTime.parse(s);}catch(RuntimeException e){return null;}}
    private static String safeDestination(String raw){try{URI u=URI.create(raw);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)return null;return "https://"+u.getHost().toLowerCase(Locale.ROOT)+(u.getPort()<0||u.getPort()==443?"":":"+u.getPort());}catch(RuntimeException e){return null;}}
    private static String origin(String raw){try{URI u=URI.create(raw.trim());String host=u.getHost();if(!"https".equalsIgnoreCase(u.getScheme())||host==null||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||u.getRawPath()!=null&&!u.getRawPath().isEmpty()&&!u.getRawPath().equals("/")||u.getPort()!=-1&&(u.getPort()<1||u.getPort()>65535))throw new IllegalArgumentException();String normalized=host.toLowerCase(Locale.ROOT);if(normalized.equals("localhost")||!normalized.contains(".")||normalized.endsWith(".local"))throw new IllegalArgumentException();if(isIp(normalized)){InetAddress ip=InetAddress.getByName(normalized.replace("[","").replace("]",""));byte[] bytes=ip.getAddress();boolean ula=bytes.length==16&&((bytes[0]&0xfe)==0xfc);if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()||ula)throw new IllegalArgumentException();}return "https://"+normalized+(u.getPort()<0||u.getPort()==443?"":":"+u.getPort());}catch(Exception e){throw ApiError.bad("回调公开地址必须是公网HTTPS origin");}}
    private static boolean isIp(String h){return h.indexOf(':')>=0||h.matches("[0-9.]+");}
    private static String sha(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable");}}
}
