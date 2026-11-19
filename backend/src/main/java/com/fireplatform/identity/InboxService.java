package com.fireplatform.identity;

import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** N13：站内消息（按用户独立已读/已处理）与个人中心（资料、会话）。 */
@Service
public class InboxService {
    public record Message(String id,String kind,String title,String body,String status,OffsetDateTime createdAt,OffsetDateTime readAt,OffsetDateTime handledAt,long revision) { }
    public record Session(OffsetDateTime createdAt,OffsetDateTime expiresAt,boolean current) { }
    public record Profile(String id,String username,String name,String userType,String tenantId) { }

    private final JdbcTemplate jdbc; private final java.time.Clock clock; private final ExecutionAuthorization auth; private final TenantFence tenants;
    public InboxService(JdbcTemplate jdbc,java.time.Clock clock,ExecutionAuthorization auth,TenantFence tenants) { this.jdbc=jdbc; this.clock=clock; this.auth=auth; this.tenants=tenants; }

    public Profile profile(AuthService.Identity identity) {
        var u=identity.user();
        return new Profile(u.id(),u.username(),u.name(),u.userType(),u.tenantId());
    }

    @Transactional public Profile rename(AuthService.Identity identity,String name) {
        if(name==null || name.trim().isEmpty() || name.trim().length()>200) throw ApiError.bad("姓名必须为1..200字");
        jdbc.update("UPDATE app_users SET display_name=? WHERE id=?",name.trim(),identity.user().id());
        String stored=jdbc.queryForObject("SELECT display_name FROM app_users WHERE id=?",String.class,identity.user().id());
        var u=identity.user();
        return new Profile(u.id(),u.username(),stored,u.userType(),u.tenantId());
    }

    public List<Session> sessions(AuthService.Identity identity) {
        return jdbc.query("SELECT created_at,expires_at,token_hash FROM sessions WHERE user_id=? AND revoked=FALSE AND expires_at>? ORDER BY expires_at DESC",(rs,n)->new Session(rs.getObject(1,OffsetDateTime.class),rs.getObject(2,OffsetDateTime.class),rs.getString(3).equals(identity.tokenHash())),identity.user().id(),OffsetDateTime.now(clock));
    }

    @Transactional public int revokeOtherSessions(AuthService.Identity identity) {
        return jdbc.update("UPDATE sessions SET revoked=TRUE WHERE user_id=? AND revoked=FALSE AND token_hash<>?",identity.user().id(),identity.tokenHash());
    }

    public Page<Message> messages(AuthService.Identity identity,int page,Integer requestedSize,String status) {
        var pagination=Pagination.of(page,requestedSize,20);
        String normalized=status==null?"":status.trim();
        if(!normalized.isEmpty() && !java.util.Set.of("UNREAD","READ","HANDLED").contains(normalized)) throw ApiError.bad("消息状态无效");
        String where="user_id=?"; List<Object> args=new ArrayList<>(); args.add(identity.user().id());
        if(!normalized.isEmpty()) { where+=" AND status=?"; args.add(normalized); }
        var total=jdbc.queryForObject("SELECT COUNT(*) FROM user_messages WHERE "+where,Long.class,args.toArray());
        args.add(pagination.size()); args.add(pagination.offset());
        var items=jdbc.query("SELECT id,kind,title,body,status,created_at,read_at,handled_at,revision FROM user_messages WHERE "+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(rs,n)->new Message(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class),rs.getObject(8,OffsetDateTime.class),rs.getLong(9)),args.toArray());
        return new Page<>(items,total==null?0:total,pagination.page(),pagination.size());
    }

    @Transactional public Message read(AuthService.Identity identity,String id,long expectedRevision) {
        var now=OffsetDateTime.now(clock);
        int changed=jdbc.update("UPDATE user_messages SET status='READ',read_at=COALESCE(read_at,?),revision=revision+1 WHERE id=? AND user_id=? AND status='UNREAD' AND revision=?",now,id,identity.user().id(),expectedRevision);
        if(changed==0) {
            var current=message(identity,id);
            if(!"UNREAD".equals(current.status())) return current;
            throw ApiError.conflict("REVISION_CONFLICT","消息状态已变化，请刷新");
        }
        return message(identity,id);
    }

    @Transactional public Message handle(AuthService.Identity identity,String id,long expectedRevision) {
        var now=OffsetDateTime.now(clock);
        int changed=jdbc.update("UPDATE user_messages SET status='HANDLED',read_at=COALESCE(read_at,?),handled_at=COALESCE(handled_at,?),revision=revision+1 WHERE id=? AND user_id=? AND status IN ('UNREAD','READ') AND revision=?",now,now,id,identity.user().id(),expectedRevision);
        if(changed==0) {
            var current=message(identity,id);
            if("HANDLED".equals(current.status())) return current;
            throw ApiError.conflict("REVISION_CONFLICT","消息状态已变化，请刷新");
        }
        return message(identity,id);
    }

    public Message message(AuthService.Identity identity,String id) {
        var rows=jdbc.query("SELECT id,kind,title,body,status,created_at,read_at,handled_at,revision FROM user_messages WHERE id=? AND user_id=?",(rs,n)->new Message(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class),rs.getObject(8,OffsetDateTime.class),rs.getLong(9)),id,identity.user().id());
        if(rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    /** 平台向单位内启用的客户账号投递站内消息；每个用户各自一份独立状态。 */
    @Transactional public int publish(AuthService.Identity identity,String tenant,String kind,String title,String body,String reason) {
        auth.lockRequest(identity,tenant,List.of("inbox:publish")); tenants.lockEnabled(tenant);
        if(reason==null || reason.trim().isEmpty() || reason.length()>1000) throw ApiError.bad("必须说明投递原因");
        String actor=identity.user().id();
        if(!Set.of("SYSTEM","ALARM").contains(kind)) throw ApiError.bad("消息类型未确认");
        if(title==null || title.trim().isEmpty() || title.length()>200) throw ApiError.bad("标题必须为1..200字");
        if(body==null || body.trim().isEmpty() || body.length()>2000) throw ApiError.bad("正文必须为1..2000字");
        var users=jdbc.queryForList("SELECT id FROM app_users WHERE tenant_id=? AND user_type='CUSTOMER' AND enabled=TRUE",String.class,tenant);
        var now=OffsetDateTime.now(clock);
        for(String user:users) jdbc.update("INSERT INTO user_messages(id,tenant_id,user_id,kind,title,body,status,created_by,created_at) VALUES(?,?,?,?,?,?,'UNREAD',?,?)",UUID.randomUUID().toString(),tenant,user,kind,title.trim(),body.trim(),actor,now);
        return users.size();
    }
}
