package com.fireplatform.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only projection of the published version's declared measurements into the existing telemetry surface.
 * The version schema stays the single source of truth; this helper never writes, never invents a metric and
 * tolerates legacy/partial schemas by skipping entries it cannot prove.
 */
@Component
public class ProductMeasurementProjection {
    public record Declared(String key,String label,String unit,String valueType) { }
    private static final Pattern KEY=Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public ProductMeasurementProjection(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public static List<Declared> parse(JsonNode schema) {
        if(schema==null || !schema.isObject()) return List.of();
        JsonNode entries=schema.get("measurements");
        if(entries==null || !entries.isArray()) return List.of();
        var result=new ArrayList<Declared>(Math.min(entries.size(),ProductLimits.MAX_MEASUREMENTS));
        var keys=new HashSet<String>();
        for(JsonNode entry:entries) {
            if(!entry.isObject() || result.size()>=ProductLimits.MAX_MEASUREMENTS) break;
            String key=entry.path("key").isTextual()?entry.path("key").textValue():null;
            if(key==null || !KEY.matcher(key).matches() || !keys.add(key)) continue;
            String type=entry.path("value_type").isTextual()?entry.path("value_type").textValue():null;
            if(!"NUMBER".equals(type) && !"STRING".equals(type) && !"BOOLEAN".equals(type)) continue;
            String label=entry.path("label").isTextual()?entry.path("label").textValue():null;
            if(label==null || label.isBlank() || label.length()>ProductLimits.MAX_MEASUREMENT_LABEL) label=key;
            String unit=entry.path("unit").isTextual()?entry.path("unit").textValue():null;
            if(unit!=null && (unit.isBlank() || unit.length()>ProductLimits.MAX_MEASUREMENT_UNIT)) unit=null;
            result.add(new Declared(key,label,unit,type));
        }
        return List.copyOf(result);
    }
    /** Declarations for the requested published versions; unknown, legacy or malformed schemas yield no entry. */
    public Map<String,Map<String,Declared>> forVersions(Collection<String> versions) {
        if(versions==null || versions.isEmpty()) return Map.of();
        var ids=new ArrayList<String>(); var seen=new HashSet<String>();
        for(String version:versions) if(version!=null && !version.isBlank() && seen.add(version)) ids.add(version);
        if(ids.isEmpty()) return Map.of();
        var placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var result=new HashMap<String,Map<String,Declared>>();
        jdbc.query("SELECT id,schema_json FROM product_versions WHERE id IN ("+placeholders+") AND schema_format='JSON_POINTER_V1'",rs->{
            Map<String,Declared> declared=null;
            try {
                var schema=mapper.readTree(rs.getString("schema_json"));
                var list=parse(schema);
                if(list.isEmpty()) return;
                declared=new LinkedHashMap<>();
                for(var item:list) declared.putIfAbsent(item.key(),item);
            } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) { return; }
            result.put(rs.getString("id"),Map.copyOf(declared));
        },ids.toArray());
        return Map.copyOf(result);
    }
    /** True when some published JSON_POINTER_V1 version declares this measurement key (any supported type). */
    public boolean declares(String key) {
        if(key==null || !KEY.matcher(key).matches()) return false;
        var rows=jdbc.queryForList("SELECT schema_json FROM product_versions WHERE status='PUBLISHED' AND schema_format='JSON_POINTER_V1' AND schema_json LIKE ? LIMIT 200",String.class,"%\""+key+"\"%");
        for(String schemaJson:rows) {
            try { for(var declared:parse(mapper.readTree(schemaJson))) if(declared.key().equals(key)) return true; }
            catch(com.fasterxml.jackson.core.JsonProcessingException ignored) { }
        }
        return false;
    }
}
