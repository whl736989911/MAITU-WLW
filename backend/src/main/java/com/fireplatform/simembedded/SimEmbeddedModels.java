package com.fireplatform.simembedded;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.List;

public final class SimEmbeddedModels {
    private SimEmbeddedModels(){ }
    @JsonDeserialize(using=StoredProfile.class)
    public record Profile(String deviceId,String tenantId,String iccid,String carrierName,String installationForm,String replaceability,String managementParty,String feeResponsibility,String reportedOperatorStatus,String expiresOn,String evidenceAt,String sourceNote,String informationSource,long revision,String recordedAt){ }
    public record Change(String changeId,String deviceId,String tenantId,long revision,String operation,Profile profile,String reason,String recordedAt){ }
    public record HistoryPage(List<Change> items,long total,int page,int size,long pages){ }
    public static final class StoredProfile extends JsonDeserializer<Profile>{
        @Override public Profile deserialize(JsonParser parser,DeserializationContext context)throws IOException{
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node=parser.getCodec().readTree(SimEmbeddedInputs.integerLexemes(parser,"revision",SimEmbeddedProjection::unconfirmed));
            if(parser.getParsingContext().inRoot()&&parser.nextToken()!=null)throw SimEmbeddedProjection.unconfirmed();
            return SimEmbeddedProjection.stored(node);
        }
        @Override public Profile getNullValue(DeserializationContext context){throw SimEmbeddedProjection.unconfirmed();}
    }
}
