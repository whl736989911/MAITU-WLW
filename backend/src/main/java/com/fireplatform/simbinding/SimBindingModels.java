package com.fireplatform.simbinding;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.List;

public final class SimBindingModels {
    private SimBindingModels(){ }
    public enum DeviceVisibility { UNBOUND, VISIBLE, CONCEALED }
    public enum EndpointVisibility { ABSENT, VISIBLE, CONCEALED }
    public record State(String cardId,String tenantId,String deviceId,DeviceVisibility deviceVisibility,long revision,String recordedAt){ }
    @JsonDeserialize(using=StoredReceipt.class)
    public record Receipt(State association,boolean changed){ }
    public record Change(String changeId,String cardId,String tenantId,long revision,String operation,String fromDeviceId,EndpointVisibility fromDeviceVisibility,String toDeviceId,EndpointVisibility toDeviceVisibility,String reason,String recordedAt){ }
    public record HistoryPage(List<Change> items,long total,int page,int size,long pages){ }
    public static final class StoredReceipt extends JsonDeserializer<Receipt>{
        @Override public Receipt deserialize(JsonParser parser,DeserializationContext context)throws IOException{
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node=parser.getCodec().readTree(SimBindingInputs.integerLexemes(parser,"revision",SimBindingProjection::unconfirmed));
            if(parser.getParsingContext().inRoot()&&parser.nextToken()!=null)throw SimBindingProjection.unconfirmed();return SimBindingProjection.stored(node);
        }
        @Override public Receipt getNullValue(DeserializationContext context){throw SimBindingProjection.unconfirmed();}
    }
}
