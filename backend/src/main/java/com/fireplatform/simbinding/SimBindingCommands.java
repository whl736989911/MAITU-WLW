package com.fireplatform.simbinding;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.simbinding.SimBindingModels.*;

@Service
public class SimBindingCommands {
    private final JdbcTemplate jdbc;private final ManagementGuard guard;private final SimBindingQueries queries;private final IdempotencyStore idem;private final AuditService audit;private final DatabaseTime clock;private final TransactionRequirements transactions;private final DeviceAccessScope scope;
    public SimBindingCommands(JdbcTemplate jdbc,ManagementGuard guard,SimBindingQueries queries,IdempotencyStore idem,AuditService audit,DatabaseTime clock,TransactionRequirements transactions,DeviceAccessScope scope){this.jdbc=jdbc;this.guard=guard;this.queries=queries;this.idem=idem;this.audit=audit;this.clock=clock;this.transactions=transactions;this.scope=scope;}
    private void begin(AuthService.Identity actor,String tenant,String key){transactions.readCommitted();if(!"PLATFORM".equals(actor.user().userType()))throw ApiError.forbidden("仅平台账号可手工关联SIM设备");guard.begin(actor,"sim-cards:bind",tenant,"management");guard.requireCurrent(actor,"sim-cards:read",tenant,"management");guard.requireCurrent(actor,"device:read",tenant,"management");idem.validateKey(key);}
    private void device(String tenant,String device){var rows=jdbc.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE",String.class,tenant,device);if(rows.isEmpty())throw SimBindingProjection.deviceMissing();if(rows.size()!=1||!device.equals(rows.getFirst()))throw SimBindingProjection.unconfirmed();}
    private Receipt validate(Receipt receipt,String tenant,String card,SimBindingInputs.Command input,boolean bind){
        if(receipt==null)throw SimBindingProjection.unconfirmed();State state=SimBindingProjection.valid(receipt.association(),tenant,card);long expected=input.expectedAssociationRevision();
        if(receipt.changed()){if((!bind&&expected==0)||expected==SimBindingInputs.MAX_SAFE||state.revision()!=expected+1)throw SimBindingProjection.unconfirmed();}else if(state.revision()!=expected)throw SimBindingProjection.unconfirmed();
        if(bind?!input.deviceId().equals(state.deviceId()):state.deviceId()!=null)throw SimBindingProjection.unconfirmed();return receipt;
    }
    private Receipt replay(AuthService.Identity actor,String tenant,String card,String operation,String key,String hash,SimBindingInputs.Command input,boolean bind){
        try{Receipt stored=idem.replay(actor.user().id(),operation,key,hash,Receipt.class);return stored==null?null:validate(stored,tenant,card,input,bind);}catch(IllegalStateException e){if("Stored response is invalid".equals(e.getMessage()))throw SimBindingProjection.unconfirmed();throw e;}catch(IllegalArgumentException e){throw SimBindingProjection.unconfirmed();}
    }
    private Receipt save(AuthService.Identity actor,String tenant,String card,String operation,String key,String hash,Receipt receipt,String request){audit.record(actor.user().id(),tenant,operation,card,"SUCCESS",request);idem.save(actor.user().id(),operation,key,hash,receipt);return receipt;}
    private Receipt command(AuthService.Identity actor,String tenant,String card,String key,SimBindingInputs.Command input,String request,boolean bind){
        begin(actor,tenant,key);if(!SimBindingProjection.uuid(card))throw SimBindingProjection.cardMissing();String operation=bind?"sim-card-bind":"sim-card-unbind";
        String hash=idem.fingerprint(List.of(tenant,operation,card,input.deviceId(),input.expectedAssociationRevision(),input.reason()));scope.requireDevice(actor.user().id(),tenant,input.deviceId());Receipt previous=replay(actor,tenant,card,operation,key,hash,input,bind);if(previous!=null)return previous;
        // Fixed lock order shared with manual card editing: guard, card, requested device, current.
        queries.card(tenant,card,true);device(tenant,input.deviceId());State current=queries.current(tenant,card,true);if(current.deviceId()!=null)scope.requireDevice(actor.user().id(),tenant,current.deviceId());
        if(current.revision()!=input.expectedAssociationRevision())throw ApiError.conflict("REVISION_CONFLICT","设备关联版本已更新，请显式重读后操作");
        if(bind&&Objects.equals(current.deviceId(),input.deviceId())||!bind&&current.deviceId()==null)return save(actor,tenant,card,operation,key,hash,validate(new Receipt(current,false),tenant,card,input,bind),request);
        if(bind&&current.deviceId()!=null||!bind&&!Objects.equals(current.deviceId(),input.deviceId()))throw ApiError.conflict("SIM_BINDING_STATE_CONFLICT","当前关联不是本次操作状态，请先显式解绑或重读");
        if(bind){var occupants=jdbc.queryForList("SELECT card_id FROM sim_card_associations WHERE tenant_id=? AND device_id=?",String.class,tenant,input.deviceId());if(occupants.size()>1||occupants.stream().anyMatch(value->!SimBindingProjection.uuid(value)||card.equals(value)))throw SimBindingProjection.unconfirmed();if(!occupants.isEmpty())throw SimBindingProjection.occupied();}
        if(current.revision()==SimBindingInputs.MAX_SAFE)throw ApiError.conflict("SIM_BINDING_REVISION_EXHAUSTED","关联版本已达到安全上界");
        String recorded=SimBindingProjection.instant(clock.now()),nextDevice=bind?input.deviceId():null;OffsetDateTime timestamp=OffsetDateTime.parse(recorded);
        if(current.revision()==0)jdbc.update("INSERT INTO sim_card_associations(card_id,tenant_id,device_id,revision,recorded_at) VALUES (?,?,?,1,?)",card,tenant,nextDevice,timestamp);
        else if(jdbc.update("UPDATE sim_card_associations SET device_id=?,revision=revision+1,recorded_at=? WHERE tenant_id=? AND card_id=? AND revision=?",nextDevice,timestamp,tenant,card,current.revision())!=1)throw ApiError.conflict("REVISION_CONFLICT","设备关联版本已更新，请显式重读后操作");
        State after=queries.current(tenant,card,false);Receipt result=validate(new Receipt(after,true),tenant,card,input,bind);
        jdbc.update("INSERT INTO sim_card_association_changes(id,card_id,tenant_id,revision,operation,from_device_id,to_device_id,reason,recorded_at,actor_user_id) VALUES (?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),card,tenant,after.revision(),bind?"BIND":"UNBIND",current.deviceId(),after.deviceId(),input.reason(),OffsetDateTime.parse(after.recordedAt()),actor.user().id());
        return save(actor,tenant,card,operation,key,hash,result,request);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt bind(AuthService.Identity actor,String tenant,String card,String key,SimBindingInputs.Command input,String request){return command(actor,tenant,card,key,input,request,true);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt unbind(AuthService.Identity actor,String tenant,String card,String key,SimBindingInputs.Command input,String request){return command(actor,tenant,card,key,input,request,false);}
}
