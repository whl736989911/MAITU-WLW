package com.fireplatform.notification;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationResourceService {
    public record Account(String id,String ownerType,String ownerId,String ownerName,long smsTotal,long smsUsed,long smsRemaining,long voiceTotal,long voiceUsed,long voiceRemaining,boolean enabled,long revision,OffsetDateTime updatedAt) { }
    public record Ledger(String id,String accountId,String channel,String kind,long quantity,long balanceAfter,String source,String reference,String operator,OffsetDateTime createdAt) { }
    public record Order(String id,String token,String outTradeNo,String tenantId,String accountId,String channel,long quantity,long amountCents,String status,String paymentState,String codeUrl,String createdBy,OffsetDateTime createdAt,OffsetDateTime paidAt,OffsetDateTime expiresAt,String closeState,String queryState,int queryAttempts,int closeAttempts,String transactionIdMasked) { }
    public record Reservation(String accountId,String channel,String tenantId) { }
    public record Policy(String mode,long revision,OffsetDateTime updatedAt) { }
    public record PolicyOverview(String tenantId,String tenantName,String mode,long revision,OffsetDateTime updatedAt) { }
    public record GrantOverview(String tenantId,String tenantName,String userId,String userName,String username,String ownerType,String ownerId,String ownerName,OffsetDateTime createdAt) { }
    public record Grant(String userId,String userName,String username,String ownerType,String ownerId,String ownerName,OffsetDateTime createdAt) { }
    public record Destination(String ownerType,String ownerId,String ownerName) { }
    public record CustomerOptions(Policy policy,List<Destination> destinations,NotificationResourcePricing pricing) { }
    public record CustomerOrder(String id,String channel,long quantity,long amountCents,String status,String paymentState,String codeUrl,OffsetDateTime createdAt,OffsetDateTime paidAt,OffsetDateTime expiresAt) { }
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final PaymentConfigService paymentConfig;
    private final PaymentOrderService payments;
    private final ScopeFilter tree;
    private final DatabaseTime time;
    private final TransactionTemplate transactions;
    public NotificationResourceService(JdbcTemplate jdbc,ManagementGuard guard,IdempotencyStore idem,AuditService audit,PaymentConfigService paymentConfig,PaymentOrderService payments,ScopeFilter tree,DatabaseTime time,PlatformTransactionManager transactionManager) {
        this.jdbc=jdbc; this.guard=guard; this.idem=idem; this.audit=audit; this.paymentConfig=paymentConfig; this.payments=payments; this.tree=tree; this.time=time;
        this.transactions=new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly=true)
    public List<Account> accounts(String tenant) {
        return jdbc.query("SELECT * FROM notification_resource_accounts WHERE tenant_id=? ORDER BY owner_type,owner_name,id",(rs,n)->account(rs),tenant);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> accountOverview(String actor,String tenant,ScopeFilter.Selection selection) {
        var f=tree.units(actor,"a","tenant_id",tenant,selection);
        var args=new ArrayList<Object>(f.bindings());
        return jdbc.queryForList("SELECT a.id,a.tenant_id,t.name AS tenant_name,a.owner_type,a.owner_id,a.owner_name,a.sms_total,a.sms_used,a.sms_total-a.sms_used AS sms_remaining,a.voice_total,a.voice_used,a.voice_total-a.voice_used AS voice_remaining,a.enabled,a.revision,a.updated_at FROM notification_resource_accounts a JOIN tenants t ON t.id=a.tenant_id WHERE 1=1"+f.and()+" ORDER BY t.name,a.owner_type,a.owner_name,a.id",args.toArray());
    }

    @Transactional(readOnly=true)
    public Page<Ledger> ledger(String tenant,String accountId,int page,Integer size) {
        var p=Pagination.of(page,size,20);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_ledger WHERE tenant_id=? AND account_id=?",Long.class,tenant,accountId);
        var items=jdbc.query("SELECT * FROM notification_resource_ledger WHERE tenant_id=? AND account_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->new Ledger(rs.getString("id"),rs.getString("account_id"),rs.getString("channel"),rs.getString("kind"),rs.getLong("quantity"),rs.getLong("balance_after"),rs.getString("source"),rs.getString("reference"),rs.getString("operator"),rs.getObject("created_at",OffsetDateTime.class)),tenant,accountId,p.size(),p.offset());
        return new Page<>(items,total,p.page(),p.size());
    }

    @Transactional(readOnly=true)
    public NotificationResourcePricing pricing() {
        return jdbc.queryForObject("SELECT sms_price_cents,voice_price_cents,revision,updated_at FROM notification_resource_pricing WHERE id=1",(rs,n)->new NotificationResourcePricing(rs.getObject("sms_price_cents",Long.class),rs.getObject("voice_price_cents",Long.class),rs.getLong("revision"),rs.getObject("updated_at",OffsetDateTime.class)));
    }

    @Transactional
    public NotificationResourcePricing updatePricing(AuthService.Identity actor,String tenant,NotificationResourceInputs.Pricing input,String request) {
        guard.begin(actor,"notification-resources:write",tenant,"management");
        if(input.expectedRevision()==null) throw ApiError.bad("缺少单价版本");
        long sms=price(input.smsPriceCents(),"短信单价"),voice=price(input.voicePriceCents(),"语音单价");
        int changed=jdbc.update("UPDATE notification_resource_pricing SET sms_price_cents=?,voice_price_cents=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE id=1 AND revision=?",sms,voice,actor.user().id(),input.expectedRevision());
        if(changed!=1) throw ApiError.conflict("REVISION_CONFLICT","单价已更新，请刷新");
        audit.record(actor.user().id(),tenant,"NOTIFICATION_RESOURCE_PRICING", "1","SUCCESS",request);
        return pricing();
    }

    @Transactional
    public Account recharge(AuthService.Identity actor,String tenant,String key,NotificationResourceInputs.Recharge input,String request) {
        guard.begin(actor,"notification-resources:write",tenant,"management");
        idem.validateKey(key);
        String ownerType=NotificationPolicy.choice(input.ownerType(),List.of("ORGANIZATION","PLACE"));
        String channel=NotificationPolicy.choice(input.channel(),List.of("SMS","VOICE"));
        long quantity=NotificationPolicy.number(input.quantity(),1,1_000_000);
        String ownerId=input.ownerId()==null?"":input.ownerId().trim();
        String ownerName=ownerName(tenant,ownerType,ownerId);
        String reference=input.reference()==null?null:NotificationPolicy.text(input.reference(),200);
        String hash=idem.fingerprint(Arrays.asList(tenant,ownerType,ownerId,channel,quantity,reference));
        var replay=idem.replay(actor.user().id(),"notification-resource-recharge",key,hash,Account.class);
        if(replay!=null) return replay;
        var rows=jdbc.query("SELECT * FROM notification_resource_accounts WHERE tenant_id=? AND owner_type=? AND owner_id=? FOR UPDATE",(rs,n)->account(rs),tenant,ownerType,ownerId);
        String accountId;
        if(rows.isEmpty()) {
            accountId=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO notification_resource_accounts(id,tenant_id,owner_type,owner_id,owner_name,sms_total,sms_used,voice_total,voice_used,enabled,revision,updated_at) VALUES (?,?,?,?,?,?,0,?,0,TRUE,1,CURRENT_TIMESTAMP)",accountId,tenant,ownerType,ownerId,ownerName,channel.equals("SMS")?quantity:0,channel.equals("VOICE")?quantity:0);
        } else {
            accountId=rows.getFirst().id();
            jdbc.update("UPDATE notification_resource_accounts SET "+(channel.equals("SMS")?"sms_total=sms_total+?":"voice_total=voice_total+?")+",revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?",quantity,accountId,tenant);
        }
        var updated=jdbc.queryForObject("SELECT * FROM notification_resource_accounts WHERE id=? AND tenant_id=?",(rs,n)->account(rs),accountId,tenant);
        long balance=channel.equals("SMS")?updated.smsTotal()-updated.smsUsed():updated.voiceTotal()-updated.voiceUsed();
        jdbc.update("INSERT INTO notification_resource_ledger(id,account_id,tenant_id,channel,kind,quantity,balance_after,source,reference,operator,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),accountId,tenant,channel,"RECHARGE",quantity,balance,"ADMIN",reference,actor.user().id(),OffsetDateTime.now());
        audit.record(actor.user().id(),tenant,"NOTIFICATION_RESOURCE_RECHARGE",accountId,"SUCCESS",request);
        idem.save(actor.user().id(),"notification-resource-recharge",key,hash,updated);
        return updated;
    }

    public Order createOrder(AuthService.Identity actor,String tenant,String key,NotificationResourceInputs.OrderCreate input,String clientIp,String request) {
        record Phase(String orderId,String description,String hash,Order replay) { }
        Phase phase=transactions.execute(status -> {
            guard.begin(actor,"notification-resources:write",tenant,"management");
            idem.validateKey(key);
            String ownerType=NotificationPolicy.choice(input.ownerType(),List.of("ORGANIZATION","PLACE"));
            String channel=NotificationPolicy.choice(input.channel(),List.of("SMS","VOICE"));
            long quantity=NotificationPolicy.number(input.quantity(),1,1_000_000);
            String ownerId=input.ownerId()==null?"":input.ownerId().trim();
            String ownerName=ownerName(tenant,ownerType,ownerId);
            var price=pricing();
            Long unit=channel.equals("SMS")?price.smsPriceCents():price.voicePriceCents();
            if(unit==null) throw ApiError.conflict("PRICE_NOT_CONFIGURED","资源单价未配置，不能生成充值订单");
            long amount;
            try { amount=Math.multiplyExact(unit,quantity); } catch(ArithmeticException e) { throw ApiError.bad("订单金额超出范围"); }
            String accountId=ensureAccount(tenant,ownerType,ownerId,ownerName);
            String hash=idem.fingerprint(List.of(tenant,ownerType,ownerId,channel,quantity,amount));
            var replay=idem.replay(actor.user().id(),"notification-resource-order",key,hash,Order.class);
            if(replay!=null) return new Phase(null,null,null,replay);
            String id=UUID.randomUUID().toString(),token=UUID.randomUUID().toString().replace("-","");
            String outTradeNo="NEW"+UUID.randomUUID().toString().replace("-","").substring(0,29);
            var merchant=paymentConfig.newOrderMerchant();
            Integer expiryMinutes=paymentConfig.orderExpiryMinutes();
            OffsetDateTime createdAt=time.now();
            OffsetDateTime expiresAt=expiryMinutes==null?null:createdAt.plusMinutes(expiryMinutes);
            jdbc.update("INSERT INTO notification_resource_orders(id,token,out_trade_no,tenant_id,account_id,channel,quantity,amount_cents,status,created_by,created_at,payment_provider,payment_state,expires_at,merchant_config_revision,merchant_mch_id,merchant_app_id) VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?,'WECHAT_NATIVE','CREATED',?,?,?,?)",
                id,token,outTradeNo,tenant,accountId,channel,quantity,amount,actor.user().id(),createdAt,expiresAt,
                merchant==null?null:merchant.configRevision(),merchant==null?null:merchant.credentials().mchId(),merchant==null?null:merchant.credentials().appId());
            var created=order(tenant,id);
            audit.record(actor.user().id(),tenant,"NOTIFICATION_RESOURCE_ORDER_CREATE",id,"PENDING",request);
            idem.save(actor.user().id(),"notification-resource-order",key,hash,created);
            return new Phase(id,(channel.equals("SMS")?"短信":"语音")+"资源充值",hash,null);
        });
        if(phase.replay()!=null) return phase.replay();
        payments.initiate(tenant,phase.orderId(),clientIp,phase.description());
        transactions.executeWithoutResult(status -> {
            var latest=order(tenant,phase.orderId());
            jdbc.update("UPDATE idempotency_records SET response_json=? WHERE user_id=? AND operation=? AND idem_key=? AND request_hash=?",
                idem.json(latest),actor.user().id(),"notification-resource-order",key,phase.hash());
        });
        return order(tenant,phase.orderId());
    }

    @Transactional(readOnly=true)
    public Page<Order> orders(String tenant,int page,Integer size) {
        var p=Pagination.of(page,size,20);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_orders WHERE tenant_id=?",Long.class,tenant);
        var items=jdbc.query("SELECT * FROM notification_resource_orders WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->order(rs),tenant,p.size(),p.offset());
        return new Page<>(items,total,p.page(),p.size());
    }
    @Transactional(readOnly=true)
    public Page<Order> orders(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size) {
        var p=Pagination.of(page,size,20);
        var filter=tree.units(actor,"o","tenant_id",tenant,selection);
        String from=" FROM notification_resource_orders o WHERE 1=1"+filter.and();
        var args=new ArrayList<Object>(filter.bindings());
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        var queryArgs=new ArrayList<>(args);queryArgs.add(p.size());queryArgs.add(p.offset());
        var items=jdbc.query("SELECT o.*"+from+" ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?",(rs,n)->order(rs),queryArgs.toArray());
        return new Page<>(items,total,p.page(),p.size());
    }

    @Transactional
    public Order confirmOrder(AuthService.Identity actor,String tenant,String id,String reference,String request) {
        guard.begin(actor,"notification-resources:write",tenant,"management");
        var rows=jdbc.query("SELECT * FROM notification_resource_orders WHERE id=? AND tenant_id=? FOR UPDATE",(rs,n)->order(rs),id,tenant);
        if(rows.isEmpty()) throw ApiError.missing();
        var order=rows.getFirst();
        if(order.status().equals("PAID")) return order;
        if(!order.status().equals("PENDING")) throw ApiError.conflict("ORDER_NOT_PENDING","订单不是待支付状态");
        var accountRows=jdbc.query("SELECT * FROM notification_resource_accounts WHERE id=? AND tenant_id=? FOR UPDATE",(rs,n)->account(rs),order.accountId(),tenant);
        if(accountRows.isEmpty()) throw ApiError.conflict("ACCOUNT_MISSING","资源账户不存在");
        jdbc.update("UPDATE notification_resource_accounts SET "+(order.channel().equals("SMS")?"sms_total=sms_total+?":"voice_total=voice_total+?")+",revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?",order.quantity(),order.accountId(),tenant);
        var updated=jdbc.queryForObject("SELECT * FROM notification_resource_accounts WHERE id=? AND tenant_id=?",(rs,n)->account(rs),order.accountId(),tenant);
        long balance=order.channel().equals("SMS")?updated.smsTotal()-updated.smsUsed():updated.voiceTotal()-updated.voiceUsed();
        jdbc.update("INSERT INTO notification_resource_ledger(id,account_id,tenant_id,channel,kind,quantity,balance_after,source,reference,operator,created_at) VALUES (?,?,?,?,'RECHARGE',?,?,'QR',?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),order.accountId(),tenant,order.channel(),order.quantity(),balance,reference,actor.user().id());
        jdbc.update("UPDATE notification_resource_orders SET status='PAID',payment_state='PAID',paid_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?",id,tenant);
        audit.record(actor.user().id(),tenant,"NOTIFICATION_RESOURCE_ORDER_PAID",id,"PAID",request);
        return order(tenant,id);
    }

    /** Commit the per-submission debit before external I/O, independent of a reminder batch.
     *  Debited from the nearest funded account: the device's place, then each ancestor, then the unit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(String tenant,String deviceId,String channel) {
        List<String> chain=placeChain(tenant,deviceId);
        var args=new ArrayList<Object>(); args.add(tenant);
        StringBuilder where=new StringBuilder("tenant_id=? AND enabled=TRUE AND (owner_type='ORGANIZATION'");
        if(!chain.isEmpty()) {
            where.append(" OR (owner_type='PLACE' AND owner_id IN (");
            for(int i=0;i<chain.size();i++){ if(i>0)where.append(','); where.append('?'); args.add(chain.get(i)); }
            where.append("))");
        }
        where.append(')');
        StringBuilder order=new StringBuilder("ORDER BY ");
        if(chain.isEmpty()) { order.append("id"); }
        else {
            order.append("CASE ");
            for(int i=0;i<chain.size();i++){ order.append("WHEN owner_type='PLACE' AND owner_id=? THEN ").append(i).append(' '); args.add(chain.get(i)); }
            order.append("ELSE ").append(chain.size()).append(" END,id");
        }
        var rows=jdbc.query("SELECT * FROM notification_resource_accounts WHERE "+where+" "+order+" FOR UPDATE",(rs,n)->account(rs),args.toArray());
        Account account=null; long remaining=0;
        for(var candidate:rows) { long available=channel.equals("SMS")?candidate.smsRemaining():candidate.voiceRemaining(); if(available>0) { account=candidate; remaining=available; break; } }
        if(account==null) throw ApiError.conflict("NOTIFICATION_RESOURCE_UNAVAILABLE",rows.isEmpty()?"没有可用的通知资源账户":"通知资源不足");
        jdbc.update("UPDATE notification_resource_accounts SET "+(channel.equals("SMS")?"sms_used=sms_used+1":"voice_used=voice_used+1")+",revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?",account.id(),tenant);
        long balance=remaining-1;
        jdbc.update("INSERT INTO notification_resource_ledger(id,account_id,tenant_id,channel,kind,quantity,balance_after,source,reference,operator,created_at) VALUES (?,?,?,?,'CONSUME',1,?,'SYSTEM',?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),account.id(),tenant,channel,balance,deviceId,"provider");
        return new Reservation(account.id(),channel,tenant);
    }

    private List<String> placeChain(String tenant,String deviceId) {
        var place=jdbc.queryForList("SELECT place_id FROM devices WHERE id=? AND tenant_id=? AND archived_at IS NULL",
            String.class,deviceId,tenant);
        if(place.isEmpty())throw ApiError.conflict("NOTIFICATION_RESOURCE_UNAVAILABLE","通知设备不属于当前有效单位范围");
        return NotificationRecipients.places(jdbc,tenant,place.getFirst());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Reservation reservation,String reference) {
        var rows=jdbc.query("SELECT * FROM notification_resource_accounts WHERE id=? AND tenant_id=? FOR UPDATE",(rs,n)->account(rs),reservation.accountId(),reservation.tenantId());
        if(rows.isEmpty()) return;
        var account=rows.getFirst();
        long used=reservation.channel().equals("SMS")?account.smsUsed():account.voiceUsed();
        if(used<1) return;
        jdbc.update("UPDATE notification_resource_accounts SET "+(reservation.channel().equals("SMS")?"sms_used=sms_used-1":"voice_used=voice_used-1")+",revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?",reservation.accountId(),reservation.tenantId());
        long balance=(reservation.channel().equals("SMS")?account.smsTotal():account.voiceTotal())-used+1;
        jdbc.update("INSERT INTO notification_resource_ledger(id,account_id,tenant_id,channel,kind,quantity,balance_after,source,reference,operator,created_at) VALUES (?,?,?,?,'REFUND',1,?,'SYSTEM',?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),reservation.accountId(),reservation.tenantId(),reservation.channel(),balance,reference,"provider");
    }


    @Transactional(readOnly=true)
    public Policy policy(String tenant) {
        var rows=jdbc.query("SELECT mode,revision,updated_at FROM notification_recharge_policy WHERE tenant_id=?",(rs,n)->new Policy(rs.getString(1),rs.getLong(2),rs.getObject(3,OffsetDateTime.class)),tenant);
        return rows.isEmpty()?new Policy("ORGANIZATION",0,null):rows.getFirst();
    }
    @Transactional(readOnly=true)
    public Page<PolicyOverview> policyOverview(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size) {
        var p=Pagination.of(page,size,20);var filter=tree.units(actor,"t","id",tenant,selection);
        String from=" FROM tenants t LEFT JOIN notification_recharge_policy r ON r.tenant_id=t.id WHERE t.enabled=TRUE"+filter.and();
        var args=new ArrayList<Object>(filter.bindings());long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        var queryArgs=new ArrayList<>(args);queryArgs.add(p.size());queryArgs.add(p.offset());
        var items=jdbc.query("SELECT t.id AS tenant_id,t.name AS tenant_name,COALESCE(r.mode,'ORGANIZATION') AS mode,COALESCE(r.revision,0) AS revision,r.updated_at"+from+" ORDER BY t.name,t.id LIMIT ? OFFSET ?",(rs,n)->new PolicyOverview(rs.getString("tenant_id"),rs.getString("tenant_name"),rs.getString("mode"),rs.getLong("revision"),rs.getObject("updated_at",OffsetDateTime.class)),queryArgs.toArray());
        return new Page<>(items,total,p.page(),p.size());
    }

    @Transactional
    public Policy updatePolicy(AuthService.Identity actor,String tenant,NotificationRechargeInputs.Policy input,String request) {
        guard.begin(actor,"notification-resources:write",tenant,"management");
        if(input.expectedRevision()==null) throw ApiError.bad("缺少充值策略版本");
        String mode=NotificationPolicy.choice(input.mode(),List.of("ORGANIZATION","GRANTS"));
        var current=policy(tenant);
        if(input.expectedRevision()!=current.revision()) throw ApiError.conflict("REVISION_CONFLICT","充值策略已更新，请刷新");
        if(current.revision()==0) jdbc.update("INSERT INTO notification_recharge_policy(tenant_id,mode,revision,updated_at,updated_by) VALUES (?,?,1,CURRENT_TIMESTAMP,?)",tenant,mode,actor.user().id());
        else if(jdbc.update("UPDATE notification_recharge_policy SET mode=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE tenant_id=? AND revision=?",mode,actor.user().id(),tenant,input.expectedRevision())!=1) throw ApiError.conflict("REVISION_CONFLICT","充值策略已更新，请刷新");
        audit.record(actor.user().id(),tenant,"NOTIFICATION_RECHARGE_POLICY",tenant,"SUCCESS",request);
        return policy(tenant);
    }

    @Transactional(readOnly=true)
    public List<Grant> grants(String tenant) {
        return jdbc.query("SELECT g.user_id,g.owner_type,g.owner_id,g.created_at,u.display_name AS user_name,u.username,COALESCE(t.name,p.name) AS owner_name FROM notification_recharge_grants g JOIN app_users u ON u.id=g.user_id AND u.tenant_id=g.tenant_id LEFT JOIN tenants t ON g.owner_type='ORGANIZATION' AND t.id=g.tenant_id LEFT JOIN places p ON g.owner_type='PLACE' AND p.id=g.owner_id AND p.tenant_id=g.tenant_id WHERE g.tenant_id=? ORDER BY u.display_name,g.owner_type,g.owner_id",(rs,n)->new Grant(rs.getString("user_id"),rs.getString("user_name"),rs.getString("username"),rs.getString("owner_type"),rs.getString("owner_id"),rs.getString("owner_name"),rs.getObject("created_at",OffsetDateTime.class)),tenant);
    }
    @Transactional(readOnly=true)
    public Page<GrantOverview> grantOverview(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size) {
        var p=Pagination.of(page,size,20);var filter=tree.units(actor,"g","tenant_id",tenant,selection);
        String from=" FROM notification_recharge_grants g JOIN app_users u ON u.id=g.user_id AND u.tenant_id=g.tenant_id JOIN tenants t ON t.id=g.tenant_id LEFT JOIN places pl ON g.owner_type='PLACE' AND pl.id=g.owner_id AND pl.tenant_id=g.tenant_id WHERE 1=1"+filter.and();
        var args=new ArrayList<Object>(filter.bindings());long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        var queryArgs=new ArrayList<>(args);queryArgs.add(p.size());queryArgs.add(p.offset());
        var items=jdbc.query("SELECT g.tenant_id,t.name AS tenant_name,g.user_id,u.display_name AS user_name,u.username,g.owner_type,g.owner_id,CASE WHEN g.owner_type='ORGANIZATION' THEN t.name ELSE pl.name END AS owner_name,g.created_at"+from+" ORDER BY t.name,u.display_name,g.owner_type,g.owner_id LIMIT ? OFFSET ?",(rs,n)->new GrantOverview(rs.getString("tenant_id"),rs.getString("tenant_name"),rs.getString("user_id"),rs.getString("user_name"),rs.getString("username"),rs.getString("owner_type"),rs.getString("owner_id"),rs.getString("owner_name"),rs.getObject("created_at",OffsetDateTime.class)),queryArgs.toArray());
        return new Page<>(items,total,p.page(),p.size());
    }

    @Transactional
    public Grant addGrant(AuthService.Identity actor,String tenant,NotificationRechargeInputs.Grant input,String request) {
        guard.begin(actor,"notification-resources:write",tenant,"management");
        String ownerType=NotificationPolicy.choice(input.ownerType(),List.of("ORGANIZATION","PLACE"));
        String userId=input.userId()==null?"":input.userId().trim();
        var users=jdbc.queryForList("SELECT display_name FROM app_users WHERE id=? AND tenant_id=? AND user_type='CUSTOMER'",String.class,userId,tenant);
        if(users.isEmpty()) throw ApiError.bad("用户不存在或不是本单位客户");
        String ownerId=input.ownerId()==null?"":input.ownerId().trim();
        String ownerName;
        if(ownerType.equals("ORGANIZATION")) { if(!tenant.equals(ownerId)) throw ApiError.bad("组织账户必须指向当前单位"); ownerName=jdbc.queryForObject("SELECT name FROM tenants WHERE id=?",String.class,tenant); }
        else { var names=jdbc.queryForList("SELECT name FROM places WHERE id=? AND tenant_id=?",String.class,ownerId,tenant); if(names.isEmpty()) throw ApiError.bad("位置不存在或不属于当前单位"); ownerName=names.getFirst(); }
        try { jdbc.update("INSERT INTO notification_recharge_grants(tenant_id,user_id,owner_type,owner_id,created_at,created_by) VALUES (?,?,?,?,CURRENT_TIMESTAMP,?)",tenant,userId,ownerType,ownerId,actor.user().id()); }
        catch(org.springframework.dao.DuplicateKeyException duplicate) { throw ApiError.conflict("GRANT_EXISTS","该用户已拥有此充值账户"); }
        audit.record(actor.user().id(),tenant,"NOTIFICATION_RECHARGE_GRANT",userId+"/"+ownerType+"/"+ownerId,"SUCCESS",request);
        return grants(tenant).stream().filter(g -> g.userId().equals(userId) && g.ownerType().equals(ownerType) && g.ownerId().equals(ownerId)).findFirst().orElseThrow();
    }

    @Transactional
    public void revokeGrant(AuthService.Identity actor,String tenant,String userId,String ownerType,String ownerId,String request) {
        guard.begin(actor,"notification-resources:write",tenant,"management");
        int changed=jdbc.update("DELETE FROM notification_recharge_grants WHERE tenant_id=? AND user_id=? AND owner_type=? AND owner_id=?",tenant,userId,ownerType,ownerId);
        if(changed!=1) throw ApiError.missing();
        audit.record(actor.user().id(),tenant,"NOTIFICATION_RECHARGE_GRANT_REVOKE",userId+"/"+ownerType+"/"+ownerId,"SUCCESS",request);
    }

    @Transactional(readOnly=true)
    public CustomerOptions customerOptions(String tenant,String userId) {
        var policy=policy(tenant);
        List<Destination> destinations;
        if(policy.mode().equals("ORGANIZATION")) {
            String name=jdbc.queryForObject("SELECT name FROM tenants WHERE id=?",String.class,tenant);
            destinations=List.of(new Destination("ORGANIZATION",tenant,name));
        } else {
            destinations=jdbc.query("SELECT g.owner_type,g.owner_id,COALESCE(t.name,p.name) AS owner_name FROM notification_recharge_grants g LEFT JOIN tenants t ON g.owner_type='ORGANIZATION' AND t.id=g.tenant_id LEFT JOIN places p ON g.owner_type='PLACE' AND p.id=g.owner_id AND p.tenant_id=g.tenant_id WHERE g.tenant_id=? AND g.user_id=? AND (g.owner_type<>'PLACE' OR p.id IS NOT NULL) ORDER BY g.owner_type,g.owner_id",(rs,n)->new Destination(rs.getString(1),rs.getString(2),rs.getString(3)),tenant,userId);
        }
        return new CustomerOptions(policy,destinations,pricing());
    }

    public CustomerOrder createCustomerOrder(AuthService.Identity actor,String tenant,String key,NotificationRechargeInputs.CustomerOrder input,String clientIp,String request) {
        record Phase(String orderId,String description,String hash,CustomerOrder replay) { }
        Phase phase=transactions.execute(status -> {
            if(actor.user().userType().equals("CUSTOMER") && !tenant.equals(actor.user().tenantId())) throw ApiError.forbidden("客户租户不匹配");
            idem.validateKey(key);
            String ownerType=NotificationPolicy.choice(input.ownerType(),List.of("ORGANIZATION","PLACE"));
            String ownerId=input.ownerId()==null?"":input.ownerId().trim();
            String channel=NotificationPolicy.choice(input.channel(),List.of("SMS","VOICE"));
            long quantity=NotificationPolicy.number(input.quantity(),1,1_000_000);
            var options=customerOptions(tenant,actor.user().id());
            var allowed=options.destinations().stream().anyMatch(d -> d.ownerType().equals(ownerType) && d.ownerId().equals(ownerId));
            if(!allowed) throw ApiError.forbidden("没有该充值账户的权限");
            var price=options.pricing();
            Long unit=channel.equals("SMS")?price.smsPriceCents():price.voicePriceCents();
            if(unit==null) throw ApiError.conflict("PRICE_NOT_CONFIGURED","资源单价未配置，不能生成充值订单");
            long amount; try { amount=Math.multiplyExact(unit,quantity); } catch(ArithmeticException e) { throw ApiError.bad("订单金额超出范围"); }
            String ownerName=options.destinations().stream().filter(d -> d.ownerType().equals(ownerType) && d.ownerId().equals(ownerId)).findFirst().orElseThrow().ownerName();
            String accountId=ensureAccount(tenant,ownerType,ownerId,ownerName);
            String hash=idem.fingerprint(List.of(tenant,ownerType,ownerId,channel,quantity,amount,"CUSTOMER"));
            var replay=idem.replay(actor.user().id(),"notification-recharge-order",key,hash,CustomerOrder.class);
            if(replay!=null) return new Phase(null,null,null,replay);
            String id=UUID.randomUUID().toString(),token=UUID.randomUUID().toString().replace("-","");
            String outTradeNo="NEW"+UUID.randomUUID().toString().replace("-","").substring(0,29);
            var merchant=paymentConfig.newOrderMerchant();
            Integer expiryMinutes=paymentConfig.orderExpiryMinutes();
            OffsetDateTime createdAt=time.now();
            OffsetDateTime expiresAt=expiryMinutes==null?null:createdAt.plusMinutes(expiryMinutes);
            jdbc.update("INSERT INTO notification_resource_orders(id,token,out_trade_no,tenant_id,account_id,channel,quantity,amount_cents,status,created_by,created_at,payment_provider,payment_state,expires_at,merchant_config_revision,merchant_mch_id,merchant_app_id) VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?,'WECHAT_NATIVE','CREATED',?,?,?,?)",
                id,token,outTradeNo,tenant,accountId,channel,quantity,amount,actor.user().id(),createdAt,expiresAt,
                merchant==null?null:merchant.configRevision(),merchant==null?null:merchant.credentials().mchId(),merchant==null?null:merchant.credentials().appId());
            var created=customerOrder(tenant,id,actor.user().id());
            audit.record(actor.user().id(),tenant,"NOTIFICATION_RECHARGE_ORDER_CREATE",id,"PENDING",request);
            idem.save(actor.user().id(),"notification-recharge-order",key,hash,created);
            return new Phase(id,(channel.equals("SMS")?"短信":"语音")+"资源充值",hash,null);
        });
        if(phase.replay()!=null) return phase.replay();
        payments.initiate(tenant,phase.orderId(),clientIp,phase.description());
        transactions.executeWithoutResult(status -> {
            var latest=customerOrder(tenant,phase.orderId(),actor.user().id());
            jdbc.update("UPDATE idempotency_records SET response_json=? WHERE user_id=? AND operation=? AND idem_key=? AND request_hash=?",
                idem.json(latest),actor.user().id(),"notification-recharge-order",key,phase.hash());
        });
        return customerOrder(tenant,phase.orderId(),actor.user().id());
    }

    @Transactional(readOnly=true)
    public Page<CustomerOrder> customerOrders(String tenant,String userId,int page,Integer size) {
        var p=Pagination.of(page,size,20);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_orders WHERE tenant_id=? AND created_by=?",Long.class,tenant,userId);
        var items=jdbc.query("SELECT * FROM notification_resource_orders WHERE tenant_id=? AND created_by=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->customerOrder(rs),tenant,userId,p.size(),p.offset());
        return new Page<>(items,total,p.page(),p.size());
    }

    @Transactional(readOnly=true)
    public CustomerOrder customerOrder(String tenant,String id,String userId) {
        var rows=jdbc.query("SELECT * FROM notification_resource_orders WHERE id=? AND tenant_id=? AND created_by=?",(rs,n)->customerOrder(rs),id,tenant,userId);
        if(rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }


    private static CustomerOrder customerOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CustomerOrder(rs.getString("id"),rs.getString("channel"),rs.getLong("quantity"),rs.getLong("amount_cents"),rs.getString("status"),paymentState(rs),rs.getString("code_url"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("paid_at",OffsetDateTime.class),rs.getObject("expires_at",OffsetDateTime.class));
    }

    private String ensureAccount(String tenant,String ownerType,String ownerId,String ownerName) {
        var rows=jdbc.queryForList("SELECT id FROM notification_resource_accounts WHERE tenant_id=? AND owner_type=? AND owner_id=? FOR UPDATE",String.class,tenant,ownerType,ownerId);
        if(!rows.isEmpty()) return rows.getFirst();
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO notification_resource_accounts(id,tenant_id,owner_type,owner_id,owner_name,sms_total,sms_used,voice_total,voice_used,enabled,revision,updated_at) VALUES (?,?,?,?,?,0,0,0,0,TRUE,0,CURRENT_TIMESTAMP)",id,tenant,ownerType,ownerId,ownerName);
        return id;
    }

    private String ownerName(String tenant,String ownerType,String ownerId) {
        if(ownerType.equals("ORGANIZATION")) {
            if(!tenant.equals(ownerId)) throw ApiError.bad("组织资源账户必须指向当前单位");
            return jdbc.queryForObject("SELECT name FROM tenants WHERE id=?",String.class,tenant);
        }
        var names=jdbc.queryForList("SELECT name FROM places WHERE id=? AND tenant_id=?",String.class,ownerId,tenant);
        if(names.isEmpty()) throw ApiError.bad("位置不存在或不属于当前单位");
        return names.getFirst();
    }

    private static long price(Long value,String label) {
        if(value==null || value<0 || value>1_000_000_00L) throw ApiError.bad(label+"未配置或超出范围");
        return value;
    }

    @Transactional(readOnly=true)
    public Order order(String tenant,String id) {
        var rows=jdbc.query("SELECT * FROM notification_resource_orders WHERE id=? AND tenant_id=?",(rs,n)->order(rs),id,tenant);
        if(rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private static Order order(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Order(rs.getString("id"),rs.getString("token"),rs.getString("out_trade_no"),rs.getString("tenant_id"),rs.getString("account_id"),rs.getString("channel"),rs.getLong("quantity"),rs.getLong("amount_cents"),rs.getString("status"),paymentState(rs),rs.getString("code_url"),rs.getString("created_by"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("paid_at",OffsetDateTime.class),rs.getObject("expires_at",OffsetDateTime.class),rs.getString("close_state"),rs.getString("query_state"),rs.getInt("query_attempts"),rs.getInt("close_attempts"),maskTransaction(rs.getString("transaction_id")));
    }

    /** Truthful payment state: an absent column is never upgraded to "ready" or "paid". */
    private static String paymentState(java.sql.ResultSet rs) throws java.sql.SQLException {
        String stored=rs.getString("payment_state");
        if(stored!=null && !stored.isBlank()) return stored;
        if("PAID".equals(rs.getString("status"))) return "PAID";
        return rs.getString("code_url")==null?"CREATED":"ACCEPTED";
    }

    private static String maskTransaction(String transactionId) {
        if(transactionId==null || transactionId.isBlank()) return null;
        return transactionId.length()<=4?"****":"****"+transactionId.substring(transactionId.length()-4);
    }

    private static Account account(java.sql.ResultSet rs) throws java.sql.SQLException {
        long smsTotal=rs.getLong("sms_total"),smsUsed=rs.getLong("sms_used"),voiceTotal=rs.getLong("voice_total"),voiceUsed=rs.getLong("voice_used");
        return new Account(rs.getString("id"),rs.getString("owner_type"),rs.getString("owner_id"),rs.getString("owner_name"),smsTotal,smsUsed,smsTotal-smsUsed,voiceTotal,voiceUsed,voiceTotal-voiceUsed,rs.getBoolean("enabled"),rs.getLong("revision"),rs.getObject("updated_at",OffsetDateTime.class));
    }
}
