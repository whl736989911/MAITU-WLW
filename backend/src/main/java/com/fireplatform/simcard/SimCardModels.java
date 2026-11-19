package com.fireplatform.simcard;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.List;

public final class SimCardModels {
    private SimCardModels(){ }
    @JsonDeserialize(using=StoredCard.class)
    public record Card(String id,String tenantId,String iccid,String carrierName,String installationForm,String replaceability,String managementParty,String feeResponsibility,String reportedOperatorStatus,String expiresOn,String evidenceAt,String sourceNote,String informationSource,long revision,String recordedAt){ }
    public record History(String id,String tenantId,String iccid,String carrierName,String installationForm,String replaceability,String managementParty,String feeResponsibility,String reportedOperatorStatus,String expiresOn,String evidenceAt,String sourceNote,String informationSource,long revision,String recordedAt,String changeId,String operation){
        public static History of(Card card,String changeId,String operation){return new History(card.id(),card.tenantId(),card.iccid(),card.carrierName(),card.installationForm(),card.replaceability(),card.managementParty(),card.feeResponsibility(),card.reportedOperatorStatus(),card.expiresOn(),card.evidenceAt(),card.sourceNote(),card.informationSource(),card.revision(),card.recordedAt(),changeId,operation);}
        public Card card(){return new Card(id,tenantId,iccid,carrierName,installationForm,replaceability,managementParty,feeResponsibility,reportedOperatorStatus,expiresOn,evidenceAt,sourceNote,informationSource,revision,recordedAt);}
    }
    public record CardPage(List<Card> items,long total,int page,int size){ }
    public record ChangesPage(Card card,List<History> items,long total,int page,int size){ }
    /** Only this receipt decoder is strict; existing domain/Jackson policies stay untouched. */
    public static final class StoredCard extends JsonDeserializer<Card>{
        @Override public Card deserialize(JsonParser parser,DeserializationContext context)throws IOException{
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            // Bind the codec to our wrapper explicitly; delegate.readValueAsTree could bypass nextToken.
            JsonNode node=parser.getCodec().readTree(SimCardInputs.integerLexemes(parser,"revision",SimCardProjection::unconfirmed));if(parser.getParsingContext().inRoot()&&parser.nextToken()!=null)throw SimCardProjection.unconfirmed();return SimCardProjection.stored(node);
        }
        @Override public Card getNullValue(DeserializationContext context){throw SimCardProjection.unconfirmed();}
    }
}
