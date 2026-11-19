package com.fireplatform.siminventory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.List;

public final class SimInventoryModels {
    private SimInventoryModels(){ }
    @JsonDeserialize(using=StoredState.class)
    public record State(String cardId,String tenantId,String status,String issuedTo,long revision,String recordedAt){ }
    public record Change(String changeId,String cardId,String tenantId,long revision,String operation,String fromStatus,String toStatus,String issuedTo,String reason,String recordedAt){ }
    public record StatePage(List<State> items,long total,int page,int size,long pages){ }
    public record HistoryPage(List<Change> items,long total,int page,int size,long pages){ }
    public static final class StoredState extends JsonDeserializer<State>{
        @Override public State deserialize(JsonParser parser,DeserializationContext context)throws IOException{
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node=parser.getCodec().readTree(SimInventoryInputs.integerLexemes(parser,"revision",SimInventoryProjection::unconfirmed));
            if(parser.getParsingContext().inRoot()&&parser.nextToken()!=null)throw SimInventoryProjection.unconfirmed();
            return SimInventoryProjection.stored(node);
        }
        @Override public State getNullValue(DeserializationContext context){throw SimInventoryProjection.unconfirmed();}
    }
}
