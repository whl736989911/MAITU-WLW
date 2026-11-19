package com.fireplatform.place;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-owned place tree mutations. ManagementGuard serializes all management writers. */
@Service
public class PlaceCommands {
    public record MovePreview(PlaceQueries.PlaceNode place, PlaceQueries.PlaceNode targetParent, long placesCount, long devicesCount, String snapshot) { }
    public record MoveResult(PlaceQueries.PlaceNode place, long placesCount, long devicesCount, boolean changed) { }
    private record PlaceState(String id, String parent, long revision) { }
    private record DeviceState(String id, String place, long revision) { }
    private record TreeState(Set<String> subtree, Map<String, PlaceState> places, List<DeviceState> devices, String snapshot) { }
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final PlaceQueries queries;
    private final AuditService audit;
    private final IdempotencyStore idem;
    private final DeviceAccessScope deviceScopes;
    public PlaceCommands(JdbcTemplate jdbc, ManagementGuard guard, PlaceQueries queries, AuditService audit, IdempotencyStore idem, DeviceAccessScope deviceScopes) {
        this.jdbc = jdbc; this.guard = guard; this.queries = queries; this.audit = audit; this.idem = idem; this.deviceScopes = deviceScopes;
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public MovePreview movePreview(AuthService.Identity actor, String tenant, String id, String targetParentId, String audience) {
        guard.requireCurrent(actor, "units:read", tenant, audience);
        guard.requireCurrent(actor, "units:write", tenant, audience);
        deviceScopes.requirePlace(actor.user().id(), tenant, id);
        targetParentId = targetParentId == null || targetParentId.isBlank() ? null : targetParentId;
        targetParentId = parent(targetParentId,tenant,id);
        requirePlaceTarget(actor,tenant,targetParentId);
        TreeState state = tree(tenant, id, targetParentId);
        return new MovePreview(require(actor.user().id(), id, tenant), targetParentId == null ? null : require(actor.user().id(), targetParentId, tenant),
            state.subtree().size(), state.devices().stream().filter(d -> state.subtree().contains(d.place())).count(), state.snapshot());
    }

    @Transactional
    public MoveResult move(AuthService.Identity actor, String tenant, String id, PlaceInputs.Move input, String key, String requestId, String audience) {
        guard.begin(actor, "units:write", tenant, audience);
        deviceScopes.requirePlace(actor.user().id(), tenant, id);
        guard.requireCurrent(actor, "units:read", tenant, audience);
        idem.validateKey(key);
        String requestedParent=input.targetParentId()==null||input.targetParentId().isBlank()?null:input.targetParentId();
        String fingerprint = idem.fingerprint(List.of(tenant,id,Objects.toString(requestedParent,""),input.expectedRevision(),input.snapshot(),input.reason().trim()));
        MoveResult replay = idem.replay(actor.user().id(), "place-subtree-move", key, fingerprint, MoveResult.class);
        if (replay != null) return replay;
        var current = jdbc.query("SELECT revision,parent_place_id FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL FOR UPDATE",
            (rs,n)->new Object[]{rs.getLong(1),rs.getString(2)},id,tenant);
        if(current.isEmpty()) throw ApiError.missing();
        if(((Number)current.getFirst()[0]).longValue()!=input.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT","位置已更新，请刷新");
        String parent = parent(requestedParent,tenant,id);
        requirePlaceTarget(actor,tenant,parent);
        TreeState state = tree(tenant, id, parent);
        if(!state.snapshot().equals(input.snapshot())) throw ApiError.conflict("PLACE_MOVE_STALE","位置树或设备归属已变化，请重新预览");
        boolean changed = !Objects.equals(current.getFirst()[1],parent);
        if(changed) {
            int updated=jdbc.update("UPDATE places SET parent_place_id=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?",parent,id,tenant,input.expectedRevision());
            if(updated!=1) throw ApiError.conflict("REVISION_CONFLICT","位置已更新，请刷新");
        }
        PlaceQueries.PlaceNode node=require(actor.user().id(), id, tenant);
        MoveResult result=new MoveResult(node,state.subtree().size(),state.devices().stream().filter(d->state.subtree().contains(d.place())).count(),changed);
        audit.record(actor.user().id(),tenant,"MOVE_PLACE_SUBTREE",id,"SUCCESS",requestId);
        idem.save(actor.user().id(),"place-subtree-move",key,fingerprint,result);
        return result;
    }

    @Transactional
    public PlaceQueries.PlaceNode create(AuthService.Identity actor, String tenant, PlaceInputs.Create input, String requestId, String audience) {
        guard.begin(actor, "units:write", tenant, audience);
        String parent = parent(input.parentPlaceId(), tenant, null);
        requirePlaceTarget(actor,tenant,parent);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id,revision) VALUES (?,?,?,?,0)", id, tenant, input.name().trim(), parent);
        audit.record(actor.user().id(), tenant, "CREATE_PLACE", id, "SUCCESS", requestId);
        return require(actor.user().id(), id, tenant);
    }

    @Transactional
    public PlaceQueries.PlaceNode update(AuthService.Identity actor, String tenant, String id, PlaceInputs.Update input, String requestId, String audience) {
        guard.begin(actor, "units:write", tenant, audience);
        var rows = jdbc.query("SELECT id,parent_place_id,revision FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL FOR UPDATE",
            (rs, n) -> new PlaceState(rs.getString(1), rs.getString(2), rs.getLong(3)), id, tenant);
        if (rows.isEmpty()) throw ApiError.missing();
        deviceScopes.requirePlace(actor.user().id(), tenant, id);
        if (rows.getFirst().revision() != input.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT", "位置已更新，请刷新");
        String parent = parent(input.parentPlaceId(), tenant, id);
        if (!Objects.equals(rows.getFirst().parent(), parent)) requirePlaceTarget(actor,tenant,parent);
        jdbc.update("UPDATE places SET name=?,parent_place_id=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?",
            input.name().trim(), parent, id, tenant, input.expectedRevision());
        audit.record(actor.user().id(), tenant, "UPDATE_PLACE", id, "SUCCESS", requestId);
        return require(actor.user().id(), id, tenant);
    }

    @Transactional
    public void delete(AuthService.Identity actor, String tenant, String id, PlaceInputs.Delete input, String requestId, String audience) {
        guard.begin(actor, "units:write", tenant, audience);
        var rows = jdbc.query("SELECT revision FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL FOR UPDATE",
            (rs, n) -> rs.getLong(1), id, tenant);
        if (rows.isEmpty()) throw ApiError.missing();
        deviceScopes.requirePlace(actor.user().id(), tenant, id);
        if (rows.getFirst() != input.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT", "位置已更新，请刷新");
        Long children = jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE tenant_id=? AND parent_place_id=? AND archived_at IS NULL", Long.class, tenant, id);
        Long devices = jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND place_id=? AND archived_at IS NULL", Long.class, tenant, id);
        if ((children != null && children > 0) || (devices != null && devices > 0)) throw ApiError.conflict("PLACE_NOT_EMPTY", "位置下仍有在用子位置或设备，不能删除");
        int changed=jdbc.update("UPDATE places SET archived_at=CURRENT_TIMESTAMP,archived_reason='用户删除空位置',archived_by=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=? AND archived_at IS NULL",actor.user().id(),id,tenant,input.expectedRevision());
        if(changed!=1)throw ApiError.conflict("REVISION_CONFLICT","位置已变更，请刷新");
        audit.record(actor.user().id(), tenant, "DELETE_PLACE", id, "SUCCESS", requestId);
    }

    private TreeState tree(String tenant,String root,String target) {
        var rows=jdbc.query("SELECT id,parent_place_id,revision FROM places WHERE tenant_id=? AND archived_at IS NULL ORDER BY id",
            (rs,n)->new PlaceState(rs.getString(1),rs.getString(2),rs.getLong(3)),tenant);
        var byId=new HashMap<String,PlaceState>(); var children=new HashMap<String,List<String>>();
        for(var row:rows){byId.put(row.id(),row);if(row.parent()!=null)children.computeIfAbsent(row.parent(),x->new ArrayList<>()).add(row.id());}
        if(!byId.containsKey(root))throw ApiError.missing();
        var subtree=new LinkedHashSet<String>();var pending=new ArrayDeque<String>();pending.add(root);
        while(!pending.isEmpty()){String item=pending.removeFirst();if(!subtree.add(item))continue;pending.addAll(children.getOrDefault(item,List.of()));}
        for(String item:subtree){var seen=new HashSet<String>();String current=item;int depth=0;while(current!=null){if(!seen.add(current))throw ApiError.conflict("PLACE_TREE_INVALID","位置树存在循环");if(++depth>64)throw ApiError.conflict("PLACE_TREE_INVALID","位置层级超过上限");var row=byId.get(current);if(row==null)throw ApiError.conflict("PLACE_TREE_INVALID","位置树存在缺失上级");current=row.parent();}}
        if(target!=null&&!byId.containsKey(target))throw ApiError.bad("上级位置不存在或不属于当前单位");
        var relevant=new TreeSet<String>(subtree);addAncestors(relevant,root,byId);if(target!=null)addAncestors(relevant,target,byId);
        var devices=jdbc.query("SELECT id,place_id,revision FROM devices WHERE tenant_id=? AND archived_at IS NULL ORDER BY id",
            (rs,n)->new DeviceState(rs.getString(1),rs.getString(2),rs.getLong(3)),tenant);
        var placesSnapshot=relevant.stream().map(x->{var p=byId.get(x);return List.of(p.id(),Objects.toString(p.parent(),""),p.revision());}).toList();
        var deviceSnapshot=devices.stream().filter(d->subtree.contains(d.place())).map(d->List.of(d.id(),Objects.toString(d.place(),""),d.revision())).toList();
        String snapshot=Digests.sha256(idem.json(List.of(placesSnapshot,deviceSnapshot)));
        return new TreeState(Set.copyOf(subtree),byId,devices,snapshot);
    }
    private void requirePlaceTarget(AuthService.Identity actor,String tenant,String placeId) {
        if (placeId == null || placeId.isBlank()) deviceScopes.requireAll(actor.user().id(), tenant);
        else deviceScopes.requirePlace(actor.user().id(), tenant, placeId);
    }
    private static void addAncestors(Set<String> output,String id,Map<String,PlaceState> places){String current=id;var seen=new HashSet<String>();while(current!=null){if(!seen.add(current))throw ApiError.conflict("PLACE_TREE_INVALID","位置树存在循环");var row=places.get(current);if(row==null)throw ApiError.conflict("PLACE_TREE_INVALID","位置树存在缺失上级");output.add(current);current=row.parent();}}

    private String parent(String requested, String tenant, String movingId) {
        if (requested == null || requested.isBlank()) return null;
        if (requested.length() > 64) throw ApiError.bad("上级位置ID无效");
        var rows = jdbc.query("SELECT parent_place_id FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",
            (rs, n) -> (String) rs.getObject(1), requested, tenant);
        if (rows.isEmpty()) throw ApiError.bad("上级位置不存在或不属于当前单位");
        String current = requested;
        for (int depth = 0; current != null; depth++) {
            if (depth >= 64) throw ApiError.bad("位置层级超过上限");
            if (current.equals(movingId)) throw ApiError.conflict("PLACE_CYCLE", "不能把位置移动到自身或其下级位置");
            var next = jdbc.query("SELECT parent_place_id FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",
                (rs, n) -> (String) rs.getObject(1), current, tenant);
            current = next.isEmpty() ? null : next.getFirst();
        }
        return requested;
    }

    private PlaceQueries.PlaceNode require(String actorId, String id, String tenant) {
        return queries.placesForTenant(actorId, tenant).stream().filter(place -> place.id().equals(id)).findFirst().orElseThrow(ApiError::missing);
    }
}
