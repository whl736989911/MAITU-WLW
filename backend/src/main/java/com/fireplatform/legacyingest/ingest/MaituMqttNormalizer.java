package com.fireplatform.legacyingest.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MaituMqttNormalizer implements LegacyIngressNormalizer {
    private final ObjectMapper mapper;
    public MaituMqttNormalizer(ObjectMapper mapper){this.mapper=mapper;}
    @Override public Parsed parse(JsonNode body,String platform,String messageType){String id=NormalizerSupport.text(body,"deviceId");var params=new LinkedHashMap<String,Object>();for(String key:List.of("data","rssi","lsnr","snr","fcnt","counter","port","freq","datr","rate","gateway","devaddr","battery")){JsonNode value=body.get(key);if(value!=null&&!value.isNull())params.put(key,value);}OffsetDateTime observed=null;for(String key:List.of("ts","time","datetime")){JsonNode value=body.get(key);if(value==null||value.isNull())continue;if(value.isNumber()){long epoch=value.longValue();observed=Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC);}else observed=NormalizerSupport.time(value.asText());if(observed!=null)break;}BigDecimal battery=NormalizerSupport.decimal(body,"battery");return new Parsed(id,null,NormalizerSupport.text(body,"fcnt"),observed,battery,"OTHER",true,List.of(NormalizerSupport.event(mapper,"OTHER","maitu.uplink",params,observed)));}
}
