package com.fireplatform.simembedded;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.simembedded.SimEmbeddedModels.*;

@Service
public class SimEmbeddedCommands {
    public static final String OPERATION="sim-embedded-record";
    private final JdbcTemplate jdbc;private final ManagementGuard guard;private final IdempotencyStore idem;private final AuditService audit;private final DatabaseTime clock;private final TransactionRequirements transactions;private final SimEmbeddedQueries queries;private final DeviceAccessScope scope;
    public SimEmbeddedCommands(JdbcTemplate jdbc,ManagementGuard guard,IdempotencyStore idem,AuditService audit,DatabaseTime clock,TransactionRequirements transactions,SimEmbeddedQueries queries,DeviceAccessScope scope){this.jdbc=jdbc;this.guard=guard;this.idem=idem;this.audit=audit;this.clock=clock;this.transactions=transactions;this.queries=queries;this.scope=scope;}
    /** Compact JSON array, nullable-safe. Actor/time are intentionally outside it. */
    public static List<Object> fingerprint(String tenant,SimEmbeddedInputs.Command input){return Arrays.asList(tenant,OPERATION,input.deviceId(),input.expectedProfileRevision(),input.iccid(),input.carrierName(),input.replaceability(),input.managementParty(),input.feeResponsibility(),input.reportedOperatorStatus(),input.expiresOn(),input.evidenceAt(),input.sourceNote(),input.reason());}
    private Profile validate(Profile profile,String tenant,SimEmbeddedInputs.Command input){
        Profile value=SimEmbeddedProjection.valid(profile,tenant,input.deviceId());long expected=input.expectedProfileRevision();if(expected==SimEmbeddedInputs.MAX_SAFE||value.revision()!=expected+1||!Objects.equals(value.iccid(),input.iccid())||!value.carrierName().equals(input.carrierName())||!value.replaceability().equals(input.replaceability())||!value.managementParty().equals(input.managementParty())||!value.feeResponsibility().equals(input.feeResponsibility())||!value.reportedOperatorStatus().equals(input.reportedOperatorStatus())||!Objects.equals(value.expiresOn(),input.expiresOn())||!Objects.equals(value.evidenceAt(),input.evidenceAt())||!value.sourceNote().equals(input.sourceNote()))throw SimEmbeddedProjection.unconfirmed();return value;
    }
    private Profile replay(AuthService.Identity actor,String tenant,String key,String hash,SimEmbeddedInputs.Command input){try{Profile stored=idem.replay(actor.user().id(),OPERATION,key,hash,Profile.class);return stored==null?null:validate(stored,tenant,input);}catch(IllegalStateException e){if("Stored response is invalid".equals(e.getMessage()))throw SimEmbeddedProjection.unconfirmed();throw e;}catch(IllegalArgumentException e){throw SimEmbeddedProjection.unconfirmed();}}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Profile record(AuthService.Identity actor,String tenant,String key,SimEmbeddedInputs.Command input,String request){
        transactions.readCommitted();if(!"PLATFORM".equals(actor.user().userType()))throw ApiError.forbidden("仅平台账号可登记内置资料");guard.begin(actor,"sim-embedded:write",tenant,"management");guard.requireCurrent(actor,"device:read",tenant,"management");guard.requireCurrent(actor,"sim-embedded:read",tenant,"management");idem.validateKey(key);SimEmbeddedInputs.reason(input.reason());
        scope.requireDevice(actor.user().id(),tenant,input.deviceId());String hash=idem.fingerprint(fingerprint(tenant,input));Profile previous=replay(actor,tenant,key,hash,input);if(previous!=null)return previous;
        queries.device(actor.user().id(),tenant,input.deviceId(),true);
        Profile current=queries.current(tenant,input.deviceId(),true);if(current.revision()!=input.expectedProfileRevision())throw ApiError.conflict("REVISION_CONFLICT","内置资料版本已更新，请显式重读");if(current.revision()==SimEmbeddedInputs.MAX_SAFE)throw ApiError.conflict("SIM_EMBEDDED_REVISION_EXHAUSTED","内置资料版本达到安全上界");
        String recorded=SimEmbeddedProjection.instant(clock.now());OffsetDateTime timestamp=OffsetDateTime.parse(recorded);long revision=current.revision()+1;LocalDate expiry=input.expiresOn()==null?null:LocalDate.parse(input.expiresOn());OffsetDateTime evidence=input.evidenceAt()==null?null:OffsetDateTime.parse(input.evidenceAt());
        if(current.revision()==0)jdbc.update("INSERT INTO sim_embedded_profiles(device_id,tenant_id,iccid,carrier_name,installation_form,replaceability,management_party,fee_responsibility,reported_operator_status,expires_on,evidence_at,source_note,information_source,revision,recorded_at) VALUES (?,?,?,?,'EMBEDDED',?,?,?,?,?,?,?,'MANUAL',?,?)",input.deviceId(),tenant,input.iccid(),input.carrierName(),input.replaceability(),input.managementParty(),input.feeResponsibility(),input.reportedOperatorStatus(),expiry,evidence,input.sourceNote(),revision,timestamp);
        else if(jdbc.update("UPDATE sim_embedded_profiles SET iccid=?,carrier_name=?,replaceability=?,management_party=?,fee_responsibility=?,reported_operator_status=?,expires_on=?,evidence_at=?,source_note=?,revision=revision+1,recorded_at=? WHERE tenant_id=? AND device_id=? AND revision=?",input.iccid(),input.carrierName(),input.replaceability(),input.managementParty(),input.feeResponsibility(),input.reportedOperatorStatus(),expiry,evidence,input.sourceNote(),timestamp,tenant,input.deviceId(),current.revision())!=1)throw ApiError.conflict("REVISION_CONFLICT","内置资料版本已更新，请显式重读");
        Profile result=validate(queries.current(tenant,input.deviceId(),false),tenant,input);
        jdbc.update("INSERT INTO sim_embedded_profile_changes(id,device_id,tenant_id,iccid,carrier_name,installation_form,replaceability,management_party,fee_responsibility,reported_operator_status,expires_on,evidence_at,source_note,information_source,revision,recorded_at,operation,reason,actor_user_id) VALUES (?,?,?,?,?,'EMBEDDED',?,?,?,?,?,?,?,'MANUAL',?,?,'RECORD',?,?)",UUID.randomUUID().toString(),result.deviceId(),tenant,result.iccid(),result.carrierName(),result.replaceability(),result.managementParty(),result.feeResponsibility(),result.reportedOperatorStatus(),expiry,evidence,result.sourceNote(),result.revision(),timestamp,input.reason(),actor.user().id());
        audit.record(actor.user().id(),tenant,OPERATION,input.deviceId(),"SUCCESS",request);idem.save(actor.user().id(),OPERATION,key,hash,result);return result;
    }
}
