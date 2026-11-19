package com.fireplatform.legacyingest.query;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.legacyingest.mirror.LegacyMirrorIntakeService;
import com.fireplatform.legacyingest.mqtt.LegacyMqttSubscriber;
import java.util.*;
import com.fireplatform.shared.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/legacy-ingest")
public class LegacyQueryController {
    private final RequestSecurity security; private final LegacyQueries queries;
    private final LegacyMirrorIntakeService mirror;
    private final LegacyMqttSubscriber mqtt;
    public LegacyQueryController(RequestSecurity security, LegacyQueries queries, LegacyMirrorIntakeService mirror,
            LegacyMqttSubscriber mqtt) {
        this.security=security; this.queries=queries; this.mirror=mirror; this.mqtt=mqtt;
    }

    @GetMapping("/messages")
    public Page<LegacyQueries.MessageRow> messages(@RequestParam(defaultValue = "1") int page, @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String state, @RequestParam(required = false) String platform,
                                                   @RequestParam(defaultValue = "") String keyword, HttpServletRequest request) {
        var actor=security.require(request,"legacy-ingest:read","management");
        return queries.messages(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size,state,platform,keyword);
    }

    @GetMapping("/mqtt")
    public Map<String, Object> mqtt(HttpServletRequest request) {
        security.require(request,"legacy-ingest:read","management");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", mqtt.getStatus().name());
        result.put("readOnly", mqtt.readOnly());
        result.put("topics", mqtt.topics());
        return result;
    }

    @GetMapping("/mirror")
    public LegacyMirrorIntakeService.Stats mirrorStats(HttpServletRequest request) {
        security.require(request,"legacy-ingest:read","management");
        return mirror.stats();
    }

    @GetMapping("/channels")
    public java.util.List<LegacyQueries.ChannelRow> channels(HttpServletRequest request) {
        var actor=security.require(request,"legacy-ingest:read","management");
        return queries.channels(actor.user().id(),security.optionalTenant(request),security.readSelection(request));
    }

    @GetMapping("/messages/{id}")
    public LegacyQueries.MessageDetail detail(@PathVariable String id, HttpServletRequest request) {
        var actor=security.require(request,"legacy-ingest:read","management");
        boolean includeRaw=actor.permissions().contains("messages:raw");
        return queries.detail(actor.user().id(),security.tenant(request),id,includeRaw);
    }

    /** Explicit export/import target list; never falls back to a source/default unit. */
    @GetMapping("/units")
    public java.util.List<LegacyQueries.UnitRow> units(HttpServletRequest request) {
        var actor=security.require(request,"legacy-ingest:read","management");
        return queries.units(actor.user().id());
    }

    @GetMapping("/reconcile")
    public Page<LegacyQueries.ReconcileRow> differences(@RequestParam(defaultValue = "1") int page, @RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) String runId, HttpServletRequest request) {
        var actor=security.require(request,"legacy-ingest:read","management");
        return queries.differences(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size,runId);
    }

    @GetMapping("/devices")
    public Page<LegacyQueries.DeviceRow> devices(@RequestParam(defaultValue = "1") int page, @RequestParam(required = false) Integer size,
                                                 @RequestParam(defaultValue = "") String keyword, HttpServletRequest request) {
        var actor=security.require(request,"legacy-ingest:read","management");
        return queries.devices(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size,keyword);
    }
}
