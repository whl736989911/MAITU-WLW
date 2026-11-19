package com.fireplatform.simcard;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.simcard.SimCardModels.*;

@Service
public class SimCardCommands {
    private final JdbcTemplate jdbc;private final ManagementGuard guard;private final SimCardQueries queries;private final IdempotencyStore idem;private final AuditService audit;private final DatabaseTime time;private final TransactionRequirements transactions;
    public SimCardCommands(JdbcTemplate jdbc,ManagementGuard guard,SimCardQueries queries,IdempotencyStore idem,AuditService audit,DatabaseTime time,TransactionRequirements transactions){this.jdbc=jdbc;this.guard=guard;this.queries=queries;this.idem=idem;this.audit=audit;this.time=time;this.transactions=transactions;}
    private void begin(AuthService.Identity actor,String tenant,String key){
        transactions.readCommitted();if(!"PLATFORM".equals(actor.user().userType()))throw ApiError.forbidden("仅平台账号可维护SIM手工台账");
        guard.begin(actor,"sim-cards:write",tenant,"management");guard.requireCurrent(actor,"sim-cards:read",tenant,"management");idem.validateKey(key);
    }
    private String fingerprint(String tenant,String operation,String id,SimCardInputs.Metadata input){return idem.fingerprint(Arrays.asList(tenant,operation,id,input.iccid(),input.carrierName(),input.replaceability(),input.managementParty(),input.feeResponsibility(),input.reportedOperatorStatus(),input.expiresOn(),input.evidenceAt(),input.sourceNote(),input.expectedRevision()));}
    private static boolean matches(Card card,SimCardInputs.Metadata input){return (input.iccid()==null||input.iccid().equals(card.iccid()))&&input.carrierName().equals(card.carrierName())&&input.replaceability().equals(card.replaceability())&&input.managementParty().equals(card.managementParty())&&input.feeResponsibility().equals(card.feeResponsibility())&&input.reportedOperatorStatus().equals(card.reportedOperatorStatus())&&Objects.equals(input.expiresOn(),card.expiresOn())&&Objects.equals(input.evidenceAt(),card.evidenceAt())&&input.sourceNote().equals(card.sourceNote())&&card.revision()==(input.expectedRevision()==null?0:input.expectedRevision()+1);}
    private Card replay(AuthService.Identity actor,String tenant,String operation,String key,String hash,String target,SimCardInputs.Metadata input){
        try{Card value=idem.replay(actor.user().id(),operation,key,hash,Card.class);if(value==null)return null;SimCardProjection.valid(value,tenant);if((target!=null&&!target.equals(value.id()))||!matches(value,input))throw SimCardProjection.unconfirmed();return value;}
        catch(IllegalStateException e){if("Stored response is invalid".equals(e.getMessage()))throw SimCardProjection.unconfirmed();throw e;}
    }
    private static LocalDate date(String value){return value==null?null:LocalDate.parse(value);}
    private static OffsetDateTime instant(String value){return value==null?null:OffsetDateTime.parse(value);}
    private Card saved(AuthService.Identity actor,String tenant,String operation,String key,String hash,String id,String request){
        Card card=queries.current(tenant,id);String kind=operation.equals("sim-card-create")?"CREATE":"EDIT";
        jdbc.update("INSERT INTO sim_card_changes(id,card_id,tenant_id,iccid,carrier_name,installation_form,replaceability,management_party,fee_responsibility,reported_operator_status,expires_on,evidence_at,source_note,information_source,revision,recorded_at,operation,actor_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),card.id(),card.tenantId(),card.iccid(),card.carrierName(),card.installationForm(),card.replaceability(),card.managementParty(),card.feeResponsibility(),card.reportedOperatorStatus(),date(card.expiresOn()),instant(card.evidenceAt()),card.sourceNote(),card.informationSource(),card.revision(),instant(card.recordedAt()),kind,actor.user().id());
        audit.record(actor.user().id(),tenant,operation,id,"SUCCESS",request);idem.save(actor.user().id(),operation,key,hash,card);return card;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Card create(AuthService.Identity actor,String tenant,String key,SimCardInputs.Metadata input,String request){
        begin(actor,tenant,key);String operation="sim-card-create",hash=fingerprint(tenant,operation,null,input);Card previous=replay(actor,tenant,operation,key,hash,null,input);if(previous!=null)return previous;
        String id=UUID.randomUUID().toString(),recorded=SimCardProjection.instant(time.now());
        jdbc.update("INSERT INTO sim_cards(id,tenant_id,iccid,carrier_name,installation_form,replaceability,management_party,fee_responsibility,reported_operator_status,expires_on,evidence_at,source_note,information_source,revision,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,tenant,input.iccid(),input.carrierName(),"REMOVABLE",input.replaceability(),input.managementParty(),input.feeResponsibility(),input.reportedOperatorStatus(),date(input.expiresOn()),instant(input.evidenceAt()),input.sourceNote(),"MANUAL",0L,instant(recorded));
        return saved(actor,tenant,operation,key,hash,id,request);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Card edit(AuthService.Identity actor,String tenant,String id,String key,SimCardInputs.Metadata input,String request){
        begin(actor,tenant,key);if(!SimCardProjection.uuid(id))throw SimCardProjection.missing();String operation="sim-card-edit",hash=fingerprint(tenant,operation,id,input);Card previous=replay(actor,tenant,operation,key,hash,id,input);if(previous!=null)return previous;
        var locked=jdbc.queryForList("SELECT id FROM sim_cards WHERE tenant_id=? AND id=? FOR UPDATE",String.class,tenant,id);if(locked.isEmpty())throw SimCardProjection.missing();if(locked.size()!=1)throw SimCardProjection.unconfirmed();Card current=queries.current(tenant,id);
        if(current.revision()!=input.expectedRevision())throw ApiError.conflict("REVISION_CONFLICT","SIM手工信息已更新，请刷新后重开操作");if(current.revision()==SimCardInputs.MAX_SAFE)throw ApiError.conflict("SIM_CARD_REVISION_EXHAUSTED","SIM版本已达到安全上界");
        String recorded=SimCardProjection.instant(time.now());int changed=jdbc.update("UPDATE sim_cards SET carrier_name=?,replaceability=?,management_party=?,fee_responsibility=?,reported_operator_status=?,expires_on=?,evidence_at=?,source_note=?,revision=revision+1,recorded_at=? WHERE tenant_id=? AND id=? AND revision=?",input.carrierName(),input.replaceability(),input.managementParty(),input.feeResponsibility(),input.reportedOperatorStatus(),date(input.expiresOn()),instant(input.evidenceAt()),input.sourceNote(),instant(recorded),tenant,id,input.expectedRevision());
        if(changed!=1)throw ApiError.conflict("REVISION_CONFLICT","SIM手工信息已更新，请刷新后重开操作");return saved(actor,tenant,operation,key,hash,id,request);
    }
}
