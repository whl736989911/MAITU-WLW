package com.fireplatform.siminventory;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.siminventory.SimInventoryModels.*;
import static com.fireplatform.siminventory.SimInventoryInputs.Operation;

@Service
public class SimInventoryCommands {
    private final JdbcTemplate jdbc;private final ManagementGuard guard;private final IdempotencyStore idem;private final AuditService audit;private final DatabaseTime clock;private final TransactionRequirements transactions;private final SimInventoryQueries queries;
    public SimInventoryCommands(JdbcTemplate jdbc,ManagementGuard guard,IdempotencyStore idem,AuditService audit,DatabaseTime clock,TransactionRequirements transactions,SimInventoryQueries queries){this.jdbc=jdbc;this.guard=guard;this.idem=idem;this.audit=audit;this.clock=clock;this.transactions=transactions;this.queries=queries;}
    /** Stable compact JSON array; non-ISSUE includes the canonical empty issued_to. */
    public static List<Object> fingerprint(String tenant,String card,Operation operation,SimInventoryInputs.Command input){return Arrays.asList(tenant,operation.namespace(),card,input.expectedInventoryRevision(),input.issuedTo(),input.reason());}
    private State validate(State receipt,String tenant,String card,Operation operation,SimInventoryInputs.Command input){State state=SimInventoryProjection.valid(receipt,tenant,card);long expected=input.expectedInventoryRevision();if(expected==SimInventoryInputs.MAX_SAFE||state.revision()!=expected+1||!operation.target().equals(state.status())||!state.issuedTo().equals(operation==Operation.ISSUE?input.issuedTo():"")||(operation==Operation.CORRECT&&expected==0))throw SimInventoryProjection.unconfirmed();return state;}
    private State replay(AuthService.Identity actor,String tenant,String card,String key,String hash,Operation operation,SimInventoryInputs.Command input){try{State stored=idem.replay(actor.user().id(),operation.namespace(),key,hash,State.class);return stored==null?null:validate(stored,tenant,card,operation,input);}catch(IllegalStateException e){if("Stored response is invalid".equals(e.getMessage()))throw SimInventoryProjection.unconfirmed();throw e;}catch(IllegalArgumentException e){throw SimInventoryProjection.unconfirmed();}}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public State record(AuthService.Identity actor,String tenant,String card,String key,Operation operation,SimInventoryInputs.Command input,String request){
        transactions.readCommitted();if(!"PLATFORM".equals(actor.user().userType()))throw ApiError.forbidden("仅平台账号可登记SIM人工库存");guard.begin(actor,"sim-inventory:write",tenant,"management");guard.requireCurrent(actor,"sim-cards:read",tenant,"management");guard.requireCurrent(actor,"sim-inventory:read",tenant,"management");idem.validateKey(key);if(!SimInventoryProjection.uuid(card))throw SimInventoryProjection.missing();if(operation==null)throw ApiError.bad("库存操作无效");SimInventoryInputs.reason(input.reason());if(operation==Operation.CORRECT&&input.reason().trim().isEmpty())throw ApiError.bad("更正报废须填写依据");
        String hash=idem.fingerprint(fingerprint(tenant,card,operation,input));State previous=replay(actor,tenant,card,key,hash,operation,input);if(previous!=null)return previous;
        queries.card(tenant,card,true);State current=queries.current(tenant,card,true);if(current.revision()!=input.expectedInventoryRevision())throw ApiError.conflict("REVISION_CONFLICT","库存版本已更新，请显式重读");if(!SimInventoryInputs.allows(operation,current.status()))throw SimInventoryProjection.conflict();if(current.revision()==SimInventoryInputs.MAX_SAFE)throw ApiError.conflict("SIM_INVENTORY_REVISION_EXHAUSTED","库存版本达到安全上界");
        long revision=current.revision()+1;String recorded=SimInventoryProjection.instant(clock.now()),issued=operation==Operation.ISSUE?input.issuedTo():"";OffsetDateTime timestamp=OffsetDateTime.parse(recorded);
        if(current.revision()==0)jdbc.update("INSERT INTO sim_card_inventory(card_id,tenant_id,status,issued_to,revision,recorded_at) VALUES (?,?,?,?,?,?)",card,tenant,operation.target(),issued,revision,timestamp);
        else if(jdbc.update("UPDATE sim_card_inventory SET status=?,issued_to=?,revision=revision+1,recorded_at=? WHERE tenant_id=? AND card_id=? AND revision=?",operation.target(),issued,timestamp,tenant,card,current.revision())!=1)throw ApiError.conflict("REVISION_CONFLICT","库存版本已更新，请显式重读");
        State result=validate(queries.current(tenant,card,false),tenant,card,operation,input);
        jdbc.update("INSERT INTO sim_card_inventory_changes(id,card_id,tenant_id,revision,operation,from_status,to_status,issued_to,reason,recorded_at,actor_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),card,tenant,result.revision(),operation.name(),current.status(),result.status(),result.issuedTo(),input.reason(),timestamp,actor.user().id());audit.record(actor.user().id(),tenant,operation.namespace(),card,"SUCCESS",request);idem.save(actor.user().id(),operation.namespace(),key,hash,result);return result;
    }
}
