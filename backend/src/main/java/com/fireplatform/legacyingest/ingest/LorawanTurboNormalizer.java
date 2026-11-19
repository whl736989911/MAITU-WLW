package com.fireplatform.legacyingest.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class LorawanTurboNormalizer implements LegacyIngressNormalizer {
    private final ObjectMapper mapper;
    public LorawanTurboNormalizer(ObjectMapper mapper){this.mapper=mapper;}
    @Override public Parsed parse(JsonNode body,String platform,String messageType){String id=NormalizerSupport.text(body,"deveui");var params=new LinkedHashMap<String,Object>();for(String key:List.of("data","fcnt","port","datr","freq","rssi","lsnr","battery","datetime")){JsonNode v=body.get(key);if(v!=null&&!v.isNull())params.put(key,v);}var observed=NormalizerSupport.time(NormalizerSupport.text(body,"datetime"));return new Parsed(id,NormalizerSupport.text(body,"upPacketSN"),NormalizerSupport.text(body,"fcnt"),observed,NormalizerSupport.decimal(body,"battery"),"OTHER",true,List.of(NormalizerSupport.event(mapper,"OTHER","lorawan.uplink",params,observed)));}
}
