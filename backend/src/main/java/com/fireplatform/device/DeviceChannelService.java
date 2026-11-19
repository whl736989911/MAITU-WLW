package com.fireplatform.device;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceChannelService {
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes; private final DeviceAccessScope deviceScopes; private final ScopeFilter scopeFilter; private final ManagementGuard guard; private final TenantFence tenants; private final IdempotencyStore idem; private final AuditService audit; private final DatabaseTime time;
    public DeviceChannelService(JdbcTemplate jdbc,PageSizePolicy sizes,DeviceAccessScope deviceScopes,ScopeFilter scopeFilter,ManagementGuard guard,TenantFence tenants,IdempotencyStore idem,AuditService audit,DatabaseTime time){this.jdbc=jdbc;this.sizes=sizes;this.deviceScopes=deviceScopes;this.scopeFilter=scopeFilter;this.guard=guard;this.tenants=tenants;this.idem=idem;this.audit=audit;this.time=time;}
    private static DeviceChannelModels.Item row(java.sql.ResultSet r)throws java.sql.SQLException{return new DeviceChannelModels.Item(r.getString("id"),r.getString("tenant_id"),r.getString("device_id"),r.getString("channel_key"),r.getString("name"),r.getString("channel_type"),r.getBoolean("enabled"),r.getLong("revision"),r.getObject("created_at",OffsetDateTime.class),r.getObject("updated_at",OffsetDateTime.class),r.getString("device_name"),r.getString("tenant_name"));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DeviceChannelModels.Page list(String actor,String tenant,int page,Integer size,String deviceId,String keyword,ScopeFilter.Selection selection){
        long configured=sizes.defaultPageSize();var p=Pagination.of(page,size,configured);if(selection.empty())return new DeviceChannelModels.Page(List.of(),0,p.page(),p.size());
        var access=deviceScopes.predicate(actor,"d.tenant_id","d.id");var tree=scopeFilter.devices(actor,"d",tenant,selection);var unit=scopeFilter.units(actor,"c","tenant_id",tenant,selection);
        StringBuilder from=new StringBuilder(" FROM device_channels c JOIN devices d ON d.id=c.device_id AND d.tenant_id=c.tenant_id JOIN tenants t ON t.id=c.tenant_id WHERE t.enabled=TRUE AND d.archived_at IS NULL");var args=new ArrayList<Object>();from.append(access.and());args.addAll(access.bindings());from.append(tree.and());args.addAll(tree.bindings());from.append(unit.and());args.addAll(unit.bindings());
        if(deviceId!=null){from.append(" AND c.device_id=?");args.add(deviceId);}if(keyword!=null&&!keyword.isEmpty()){String like=LiteralLike.contains(keyword);from.append(" AND (LOWER(c.name) LIKE LOWER(?) ESCAPE '!' OR LOWER(c.channel_key) LIKE LOWER(?) ESCAPE '!' OR LOWER(c.channel_type) LIKE LOWER(?) ESCAPE '!')");args.add(like);args.add(like);args.add(like);}
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());args.add(p.size());args.add(p.offset());var items=jdbc.query("SELECT c.*,d.is_simulated AS device_simulated,d.name AS device_name,t.name AS tenant_name"+from+" ORDER BY c.tenant_id,c.device_id,c.channel_key,c.id LIMIT ? OFFSET ?",(r,n)->row(r),args.toArray());return new DeviceChannelModels.Page(items,total,p.page(),p.size());
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public DeviceChannelModels.Item create(AuthService.Identity actor,String tenant,String key,DeviceChannelModels.Create in,String request){begin(actor,tenant,key);text(in.deviceId(),64,"设备标识");text(in.channelKey(),64,"通道标识");text(in.name(),120,"通道名称");text(in.channelType(),64,"通道类型");reason(in.reason());lockDevice(actor,tenant,in.deviceId());String channelKey=in.channelKey().trim();String hash=idem.fingerprint(Arrays.asList(tenant,in.deviceId(),channelKey,in.name(),in.channelType(),in.enabled(),in.reason()));var replay=idem.replay(actor.user().id(),"device-channel-create",key,hash,DeviceChannelModels.Item.class);if(replay!=null)return replay;if(jdbc.queryForObject("SELECT COUNT(*) FROM device_channels WHERE tenant_id=? AND device_id=? AND channel_key=?",Long.class,tenant,in.deviceId(),channelKey)!=0)throw ApiError.conflict("DEVICE_CHANNEL_KEY_CONFLICT","设备已存在该通道标识");
        String id=UUID.randomUUID().toString();OffsetDateTime now=time.now();boolean enabled=in.enabled();jdbc.update("INSERT INTO device_channels(id,tenant_id,device_id,channel_key,name,channel_type,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,?,?)",id,tenant,in.deviceId(),channelKey,in.name().trim(),in.channelType().trim(),enabled,now,now);var item=read(tenant,id);audit.record(actor.user().id(),tenant,"DEVICE_CHANNEL_CREATED",id,"SUCCESS",request);idem.save(actor.user().id(),"device-channel-create",key,hash,item);return item;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public DeviceChannelModels.Item update(AuthService.Identity actor,String tenant,String id,String key,DeviceChannelModels.Update in,String request){
        begin(actor,tenant,key);text(id,64,"通道标识");text(in.name(),120,"通道名称");text(in.channelType(),64,"通道类型");reason(in.reason());if(in.expectedRevision()==null||in.expectedRevision()<0)throw ApiError.bad("通道revision无效");
        var located=jdbc.queryForList("SELECT device_id FROM device_channels WHERE tenant_id=? AND id=?",String.class,tenant,id);if(located.isEmpty())throw ApiError.missing();lockDevice(actor,tenant,located.getFirst());
        var old=jdbc.queryForList("SELECT revision FROM device_channels WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,tenant,id);if(old.isEmpty())throw ApiError.missing();
        String hash=idem.fingerprint(Arrays.asList(tenant,id,in.name(),in.channelType(),in.enabled(),in.expectedRevision(),in.reason()));var replay=idem.replay(actor.user().id(),"device-channel-update",key,hash,DeviceChannelModels.Item.class);if(replay!=null)return replay;
        long revision=old.getFirst();if(revision!=in.expectedRevision())throw ApiError.conflict("REVISION_CONFLICT","通道已更新，请刷新");if(revision==Long.MAX_VALUE)throw ApiError.conflict("REVISION_EXHAUSTED","通道revision已达上限");
        int changed=jdbc.update("UPDATE device_channels SET name=?,channel_type=?,enabled=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND id=? AND revision=?",in.name().trim(),in.channelType().trim(),in.enabled(),time.now(),tenant,id,revision);if(changed!=1)throw ApiError.conflict("REVISION_CONFLICT","通道已更新，请刷新");var item=read(tenant,id);audit.record(actor.user().id(),tenant,"DEVICE_CHANNEL_UPDATED",id,"SUCCESS",request);idem.save(actor.user().id(),"device-channel-update",key,hash,item);return item;
    }
    private void begin(AuthService.Identity actor,String tenant,String key){guard.begin(actor,"device:write",tenant,"management");tenants.lockEnabled(tenant);idem.validateKey(key);}
    private void lockDevice(AuthService.Identity actor,String tenant,String device){var access=deviceScopes.predicate(actor.user().id(),"devices.tenant_id","devices.id");var args=new ArrayList<Object>();args.add(tenant);args.add(device);args.addAll(access.bindings());var rows=jdbc.query("SELECT id FROM devices WHERE tenant_id=? AND id=? AND archived_at IS NULL"+access.and()+" FOR UPDATE",(r,n)->r.getString(1),args.toArray());if(rows.isEmpty())throw ApiError.missing();}
    private DeviceChannelModels.Item read(String tenant,String id){var rows=jdbc.query("SELECT c.*,d.is_simulated AS device_simulated,d.name AS device_name,t.name AS tenant_name FROM device_channels c JOIN devices d ON d.id=c.device_id AND d.tenant_id=c.tenant_id JOIN tenants t ON t.id=c.tenant_id WHERE c.tenant_id=? AND c.id=? AND d.archived_at IS NULL",(r,n)->row(r),tenant,id);if(rows.isEmpty())throw ApiError.missing();return rows.getFirst();}
    private static void text(String value,int max,String label){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw ApiError.bad(label+"无效");}
    private static void reason(String value){if(value==null||value.isBlank()||value.length()>1000)throw ApiError.bad("必须说明变更原因");}
}
