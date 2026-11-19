package com.fireplatform.hikvision;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HikvisionConfigService {
    public record View(boolean configured, boolean enabled, String endpoint, String appKeyMasked,
        boolean secretConfigured, int pollIntervalSeconds, String modelScope, long revision,
        String configurationSource, boolean secretStorageReady) { }
    public record Runtime(String endpoint, String appKey, String appSecret, boolean enabled,
        int pollIntervalSeconds, String modelScope, boolean databaseConfigured, long revision, HikvisionClient.Session session) { }
    private record Row(String endpoint,String appKey,String appKeyMasked,String secretCipher,String secretNonce,
        String accountFingerprint,boolean enabled,int interval,String modelScope,long revision) { }
    private final JdbcTemplate jdbc; private final HikvisionClient client; private final HikvisionConfigSecrets secrets;
    private final ManagementGuard guard; private final AuditService audit; private final IdempotencyStore idem; private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final boolean envEnabled; private final int envInterval; private volatile Runtime runtime;
    public HikvisionConfigService(JdbcTemplate jdbc,HikvisionClient client,HikvisionConfigSecrets secrets,ManagementGuard guard,
        AuditService audit,IdempotencyStore idem,Clock clock,org.springframework.transaction.support.TransactionTemplate transactions,
        @Value("${fire.hikvision.enabled:${HIKVISION_ENABLED:false}}") boolean enabled,
        @Value("${fire.hikvision.poll-ms:${HIKVISION_POLL_MS:30000}}") int interval,
        @Value("${fire.hikvision.app-key:${HIKVISION_APP_KEY:}}") String envKey,
        @Value("${fire.hikvision.app-secret:${HIKVISION_APP_SECRET:}}") String envSecret) {
        this.jdbc=jdbc;this.client=client;this.secrets=secrets;this.guard=guard;this.audit=audit;this.idem=idem;this.clock=clock;this.transactions=transactions;
        if(interval<10000||interval>3600000)throw new IllegalStateException("Invalid Hikvision polling interval");
        envEnabled=enabled;envInterval=interval/1000;
        runtime=new Runtime("https://open.hikfirecloud.com",envKey,envSecret,enabled,interval/1000,"800235",false,0,client.session());
    }
    @jakarta.annotation.PostConstruct
    public void loadDatabaseConfiguration() {
        List<Row> rows=jdbc.query("SELECT endpoint,app_key,app_key_masked,secret_cipher,secret_nonce,account_fingerprint,enabled,poll_interval_seconds,model_scope,revision FROM hikvision_config WHERE id=1",(r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBoolean(7),r.getInt(8),r.getString(9),r.getLong(10)));
        if(!rows.isEmpty())apply(rows.getFirst());
    }
    private synchronized void apply(Row row) {
        String secret=secrets.reveal(row.secretCipher(),row.secretNonce());
        client.configure(row.endpoint(),row.appKey(),secret);
        runtime=new Runtime(row.endpoint(),row.appKey(),secret,row.enabled(),row.interval(),row.modelScope(),true,row.revision(),client.session());
    }
    public synchronized Runtime refreshRuntime(){
        List<Row> rows=readRows();
        if(rows.isEmpty())return runtime;
        Row row=rows.getFirst();
        if(!runtime.databaseConfigured()||runtime.revision()!=row.revision())apply(row);
        return runtime;
    }
    public Runtime runtime(){return refreshRuntime();}
    public View get() {
        Runtime current=refreshRuntime();
        if(!current.databaseConfigured())return new View(current.session().configured(),current.enabled(),null,null,
            !current.appKey().isBlank()&&!current.appSecret().isBlank(),current.pollIntervalSeconds(),current.modelScope(),0,"ENVIRONMENT",secrets.ready());
        return new View(current.session().configured(),current.enabled(),current.endpoint(),HikvisionConfigSecrets.mask(current.appKey()),true,
            current.pollIntervalSeconds(),current.modelScope(),current.revision(),"DATABASE",secrets.ready());
    }
    private List<Row> readRows(){return jdbc.query("SELECT endpoint,app_key,app_key_masked,secret_cipher,secret_nonce,account_fingerprint,enabled,poll_interval_seconds,model_scope,revision FROM hikvision_config WHERE id=1",(r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBoolean(7),r.getInt(8),r.getString(9),r.getLong(10)));}
    @Transactional
    public View update(AuthService.Identity actor,String key,HikvisionConfigInputs.Write input,String requestId) {
        guard.begin(actor,"integrations:write",null,"management");
        idem.validateKey(key);
        List<Row> rows=jdbc.query("SELECT endpoint,app_key,app_key_masked,secret_cipher,secret_nonce,account_fingerprint,enabled,poll_interval_seconds,model_scope,revision FROM hikvision_config WHERE id=1 FOR UPDATE",(r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBoolean(7),r.getInt(8),r.getString(9),r.getLong(10)));
        Row old=rows.isEmpty()?null:rows.getFirst();long revision=old==null?0:old.revision();
        Runtime current=refreshRuntime();
        String endpoint=endpoint(input.endpoint());String model=scope(input.modelScope());
        String suppliedKey=input.appKey()==null?"":input.appKey().trim();
        String appKey=suppliedKey.isEmpty()?(old!=null?old.appKey():current.appKey()):suppliedKey;
        if(appKey==null||!appKey.matches("[A-Za-z0-9._-]{1,200}"))throw ApiError.bad("App Key 格式无效");
        String suppliedSecret=input.appSecret()==null?"":input.appSecret().trim();
        if(suppliedSecret.length()>500)throw ApiError.bad("App Secret 超过允许长度");
        String identity=secrets.ready()?secrets.fingerprint(endpoint+"\n"+appKey):null;
        String secret=suppliedSecret;
        if(secret.isEmpty()) {
            if(old!=null) {
                if(!old.accountFingerprint().equals(identity))throw ApiError.conflict("HIKVISION_SECRET_REQUIRED","切换账号身份必须重新提供 Secret");
                secret=secrets.reveal(old.secretCipher(),old.secretNonce());
            } else if(current.session().configured()) {
                String currentIdentity=secrets.ready()?secrets.fingerprint(current.endpoint()+"\n"+current.appKey()):null;
                if(!java.util.Objects.equals(currentIdentity,identity))throw ApiError.conflict("HIKVISION_SECRET_REQUIRED","切换账号身份必须重新提供 Secret");
                secret=current.appSecret();
            } else throw ApiError.bad("首次保存必须填写海康 App Secret");
        }
        if(!secrets.ready())throw new ApiError(503,"HIKVISION_CONFIG_KEY_UNAVAILABLE","海康独立主密钥未配置，不能保存凭证");
        long facts=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM hikvision_discovered_devices)+(SELECT COUNT(*) FROM hikvision_raw_records)",Long.class);
        if(facts>0) {
            String baseline=old==null?(current.session().configured()?secrets.fingerprint(current.endpoint()+"\n"+current.appKey()):null):old.accountFingerprint();
            if(baseline==null||!baseline.equals(identity))throw ApiError.conflict("HIKVISION_ACCOUNT_BOUND","已有海康来源事实，不能原位切换账号；需先完成受控切换");
        }
        var fingerprint=idem.fingerprint(new Object[]{input.expectedRevision(),endpoint,appKey,secret,input.enabled(),input.pollIntervalSeconds(),model});
        var replay=idem.replay(actor.user().id(),"hikvision-config",key,fingerprint,View.class);if(replay!=null)return replay;
        if(input.expectedRevision()!=revision)throw ApiError.conflict("REVISION_CONFLICT","海康配置已更新，请刷新后重试");
        String cipher,nonce,masked;
        if(secret.isEmpty()){cipher=old.secretCipher();nonce=old.secretNonce();masked=HikvisionConfigSecrets.mask(appKey);}
        else {var protectedSecret=secrets.protect(secret);cipher=protectedSecret.cipher();nonce=protectedSecret.nonce();masked=HikvisionConfigSecrets.mask(appKey);}
        if(old==null)jdbc.update("INSERT INTO hikvision_config(id,endpoint,app_key,app_key_masked,secret_cipher,secret_nonce,account_fingerprint,enabled,poll_interval_seconds,model_scope,revision,updated_at,updated_by) VALUES(1,?,?,?,?,?,?,?,?,?,1,?,?)",endpoint,appKey,masked,cipher,nonce,identity,input.enabled(),input.pollIntervalSeconds(),model,OffsetDateTime.now(clock),actor.user().id());
        else jdbc.update("UPDATE hikvision_config SET endpoint=?,app_key=?,app_key_masked=?,secret_cipher=?,secret_nonce=?,account_fingerprint=?,enabled=?,poll_interval_seconds=?,model_scope=?,revision=revision+1,updated_at=?,updated_by=? WHERE id=1",endpoint,appKey,masked,cipher,nonce,identity,input.enabled(),input.pollIntervalSeconds(),model,OffsetDateTime.now(clock),actor.user().id());
        Row saved=readRows().getFirst();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCommit() { apply(saved); }
        });
        View view=new View(true,input.enabled(),endpoint,masked,true,input.pollIntervalSeconds(),model,revision+1,"DATABASE",secrets.ready());
        audit.record(actor.user().id(),null,"HIKVISION_CONFIG_UPDATE","hikvision-config","SUCCESS",requestId);
        idem.save(actor.user().id(),"hikvision-config",key,fingerprint,view);return view;
    }
    public String validateReadOnly(AuthService.Identity actor,String requestId) {
        transactions.executeWithoutResult(status -> guard.begin(actor,"integrations:write",null,"management"));
        Runtime current=refreshRuntime();
        if(!current.session().configured())throw new ApiError(503,"HIKVISION_NOT_CONFIGURED","海康凭证未配置");
        try {
            current.session().read(HikvisionClient.Stream.UNIT,1,"",java.util.Map.of("companyID","","unitID",""));
            audit.record(actor.user().id(),null,"HIKVISION_READ_ONLY_VALIDATE","hikvision-config","SUCCESS",requestId);
            return "READ_ONLY_OK";
        } catch(RuntimeException failure) {
            audit.record(actor.user().id(),null,"HIKVISION_READ_ONLY_VALIDATE","hikvision-config","FAILED",requestId);
            throw failure;
        }
    }
    private static String endpoint(String raw) {
        try {URI uri=URI.create(raw.trim());String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||!host.equalsIgnoreCase("open.hikfirecloud.com")||uri.getPort()!=-1&&uri.getPort()!=443||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPath()!=null&&!uri.getPath().isEmpty()&&!"/".equals(uri.getPath()))throw new IllegalArgumentException();
            return "https://open.hikfirecloud.com";
        } catch(RuntimeException e){throw ApiError.bad("海康 endpoint 必须为官方 HTTPS 接入地址");}
    }
    private static String scope(String raw){if(!"800235".equals(raw.trim()))throw ApiError.bad("当前首批接入仅允许型号范围 800235");return "800235";}
}
