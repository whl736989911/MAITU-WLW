package com.fireplatform.hikvision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Labels from the shipped Hikvision dictionary; values and units always come from the source row. */
@Component
public class HikvisionCatalog {
    private static final Map<String,String> METRICS = Map.of("400001","battery","400002","signal_level",
        "400006","temperature","400012","maze_pollution","400013","smoke_value","400016","humidity","400033","water_vapor");
    private static final Set<String> FIRE_TYPES = Set.of("200001","200002","200013","200014","200017","200070","200087","200162","200172");
    private final JsonNode codes;
    public HikvisionCatalog(ObjectMapper json) {
        try (var input = HikvisionCatalog.class.getResourceAsStream("/hikvision-code-catalog.json")) {
            if (input == null) throw new IllegalStateException("Hikvision code catalog missing");
            codes = json.readTree(input);
        } catch (IOException e) { throw new IllegalStateException("Hikvision code catalog invalid"); }
    }
    public String faultLabel(String code) { return codes.path("faults").path(code == null ? "" : code).asText("未知故障（来源码 " + code + "）"); }
    public String alarmLabel(String code) { return codes.path("alarms").path(code == null ? "" : code).asText("海康报警（来源码 " + code + "）"); }
    public boolean fire(String code) { return FIRE_TYPES.contains(code); }
    public boolean simulatedAlarm(String code) { return "200160".equals(code); }
    public String metric(String code, String circuit) {
        if (code == null || !code.matches("[0-9]{1,12}")) return null;
        String key = METRICS.getOrDefault(code, "hik_" + code);
        return circuit == null || circuit.equals("0") ? key : key + "_circuit_" + circuit.replaceAll("[^A-Za-z0-9]", "_");
    }
    public String metricLabel(String code, String circuit) {
        String label = codes.path("monitors").path(code).asText("海康监测 " + code);
        return circuit == null || circuit.equals("0") ? label : label + " · 回路 " + circuit;
    }
}
