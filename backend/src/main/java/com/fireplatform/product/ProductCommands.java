package com.fireplatform.product;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCommands {
    private final JdbcTemplate jdbc; private final ManagementGuard guard; private final IdempotencyStore idem; private final AuditService audit; private final ProductQueries queries; private final JsonPointerParser parser; private final Clock clock;
    public ProductCommands(JdbcTemplate jdbc,ManagementGuard guard,IdempotencyStore idem,AuditService audit,ProductQueries queries,JsonPointerParser parser,Clock clock) { this.jdbc=jdbc; this.guard=guard; this.idem=idem; this.audit=audit; this.queries=queries; this.parser=parser; this.clock=clock; }
    private void begin(AuthService.Identity actor,String permission,String key) { guard.begin(actor,permission,null,"management"); idem.validateKey(key); }
    private void saved(AuthService.Identity actor,String op,String key,String hash,Object result,String target,String request) { audit.record(actor.user().id(),null,op,target,"SUCCESS",request); idem.save(actor.user().id(),op,key,hash,result); }
    private void draft(ProductModels.Version version) { if(!version.status().equals("DRAFT")) throw ApiError.conflict("VERSION_IMMUTABLE","已发布版本及证据不可修改"); }
    private void revision(ProductModels.Version version,long expected) { if(version.revision()!=expected) throw ApiError.conflict("REVISION_CONFLICT","版本已更新，请刷新"); }
    private void revision(ProductModels.Product product,long expected) { if(product.revision()!=expected) throw ApiError.conflict("REVISION_CONFLICT","产品已更新，请刷新"); }
    private ProductModels.BatteryThreshold threshold(ProductInputs.BatteryThresholdInput in,com.fasterxml.jackson.databind.JsonNode capabilities) {
        if(in==null) return null;
        if(!"battery_low_threshold_percent".equals(in.parameterKey()) || !"battery_percent".equals(in.metricKey()) || !"%".equals(in.unit()) || in.comparator()==null || !Set.of("LT","LTE").contains(in.comparator()) || in.threshold()==null || in.threshold().precision()>64 || in.threshold().scale()>36 || in.threshold().compareTo(java.math.BigDecimal.ZERO)<0 || in.threshold().compareTo(java.math.BigDecimal.valueOf(100))>0)
            throw ApiError.bad("电量低阈值参数或设备电量能力无效");
        if(!"SUPPORTED".equals(capabilities.path("battery").asText())) throw ApiError.conflict("PARAMETER_INCOMPATIBLE","当前产品版本不支持电量低阈值");
        return new ProductModels.BatteryThreshold(in.parameterKey(),in.metricKey(),in.unit(),in.comparator(),in.threshold());
    }
    @Transactional public ProductModels.Product create(AuthService.Identity actor,String key,ProductInputs.CreateProduct in,String request) {
        begin(actor,"products:write",key); String hash=idem.fingerprint(List.of(in.name(),in.manufacturer(),in.model(),in.description()));
        var replay=idem.replay(actor.user().id(),"product-create",key,hash,ProductModels.Product.class); if(replay!=null) return replay;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE manufacturer=? AND model=?",Long.class,in.manufacturer(),in.model())!=0) throw ApiError.conflict("PRODUCT_CONFLICT","厂商与型号标识已存在");
        String id=UUID.randomUUID().toString(); jdbc.update("INSERT INTO products(id,name,manufacturer,model,description,revision) VALUES (?,?,?,?,?,0)",id,in.name(),in.manufacturer(),in.model(),in.description());
        var result=queries.product(id,false); saved(actor,"product-create",key,hash,result,id,request); return result;
    }
    /** Only the mutable presentation fields change; manufacturer/model stay the stable product identity. */
    @Transactional public ProductModels.Product update(AuthService.Identity actor,String id,String key,ProductInputs.UpdateProduct in,String request) {
        begin(actor,"products:write",key); var product=queries.product(id,true);
        String hash=idem.fingerprint(List.of(id,in.expectedRevision(),in.name(),in.description(),in.reason().trim()));
        var replay=idem.replay(actor.user().id(),"product-update",key,hash,ProductModels.Product.class);
        if(replay!=null) { revision(product,replay.revision()); return replay; }
        revision(product,in.expectedRevision());
        jdbc.update("UPDATE products SET name=?,description=?,revision=revision+1 WHERE id=?",in.name(),in.description(),id);
        var result=queries.product(id,false); saved(actor,"product-update",key,hash,result,id,request); return result;
    }
    @Transactional public ProductModels.Product archive(AuthService.Identity actor,String id,String key,ProductInputs.ProductLifecycle in,String request) {
        begin(actor,"products:write",key); var product=queries.product(id,true);
        String hash=idem.fingerprint(List.of(id,in.expectedRevision(),"ARCHIVE",in.reason().trim()));
        var replay=idem.replay(actor.user().id(),"product-archive",key,hash,ProductModels.Product.class);
        if(replay!=null) { revision(product,replay.revision()); return replay; }
        if("ARCHIVED".equals(product.state())) throw ApiError.conflict("PRODUCT_ALREADY_ARCHIVED","产品已归档");
        revision(product,in.expectedRevision());
        OffsetDateTime now=OffsetDateTime.now(clock);
        jdbc.update("UPDATE products SET state='ARCHIVED',archived_at=?,archived_by=?,archived_reason=?,revision=revision+1 WHERE id=?",now,actor.user().id(),in.reason().trim(),id);
        var result=queries.product(id,false); saved(actor,"product-archive",key,hash,result,id,request); return result;
    }
    @Transactional public ProductModels.Product reactivate(AuthService.Identity actor,String id,String key,ProductInputs.ProductLifecycle in,String request) {
        begin(actor,"products:write",key); var product=queries.product(id,true);
        String hash=idem.fingerprint(List.of(id,in.expectedRevision(),"REACTIVATE",in.reason().trim()));
        var replay=idem.replay(actor.user().id(),"product-reactivate",key,hash,ProductModels.Product.class);
        if(replay!=null) { revision(product,replay.revision()); return replay; }
        if("ACTIVE".equals(product.state())) throw ApiError.conflict("PRODUCT_NOT_ARCHIVED","产品未归档");
        revision(product,in.expectedRevision());
        jdbc.update("UPDATE products SET state='ACTIVE',archived_at=NULL,archived_by=NULL,archived_reason=NULL,revision=revision+1 WHERE id=?",id);
        var result=queries.product(id,false); saved(actor,"product-reactivate",key,hash,result,id,request); return result;
    }
    @Transactional public ProductModels.Version createVersion(AuthService.Identity actor,String product,String key,ProductInputs.CreateVersion in,String request) {
        begin(actor,"products:write",key); var owner=queries.product(product,true);
        String hash=idem.fingerprint(List.of(product,Objects.toString(in.baseVersionId(),""))); var replay=idem.replay(actor.user().id(),"version-create",key,hash,ProductModels.Version.class); if(replay!=null) return replay;
        if("ARCHIVED".equals(owner.state())) throw ApiError.conflict("PRODUCT_ARCHIVED","产品已归档，不能新建或复制版本");
        var schema=parser.defaultSchema(); var capabilities=parser.pendingCapabilities(); ProductModels.BatteryThreshold battery=null;
        if(in.baseVersionId()!=null) { var base=queries.version(in.baseVersionId(),true); if(!base.productId().equals(product)) throw ApiError.conflict("PRODUCT_MISMATCH","基础版本必须属于当前产品"); schema=base.schema(); capabilities=base.capabilities(); battery=base.batteryLowThreshold(); }
        long number=jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM product_versions WHERE product_id=?",Long.class,product); String id=UUID.randomUUID().toString();
        OffsetDateTime createdAt=OffsetDateTime.now(clock);
        jdbc.update("INSERT INTO product_versions(id,product_id,version_number,status,revision,schema_json,capabilities_json,battery_low_comparator,battery_low_threshold,parser_tested,created_at) VALUES (?,?,?,'DRAFT',0,?,?,?,?,FALSE,?)",id,product,number,idem.json(schema),idem.json(capabilities),battery==null ? null : battery.comparator(),battery==null ? null : battery.threshold(),createdAt);
        if(in.baseVersionId()!=null) {
            // Service definitions are part of the version contract and must be cloned with it, as drafts.
            jdbc.query("SELECT operation_key,display_name,category,risk_level,result_kind,input_schema_json,applicability_json,evidence_json,support_state FROM device_service_definitions WHERE product_version_id=? ORDER BY operation_key",
                (rs,n) -> new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)},in.baseVersionId())
                .forEach(def -> jdbc.update("INSERT INTO device_service_definitions(id,product_version_id,operation_key,display_name,category,risk_level,result_kind,input_schema_json,applicability_json,evidence_json,support_state,status,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?)",
                    UUID.randomUUID().toString(),id,def[0],def[1],def[2],def[3],def[4],def[5],def[6],def[7],def[8],createdAt,createdAt));
        }
        var result=queries.version(id,false); saved(actor,"version-create",key,hash,result,id,request); return result;
    }
    @Transactional public ProductModels.Version edit(AuthService.Identity actor,String id,String key,ProductInputs.EditVersion in,String request) {
        begin(actor,"products:write",key); var version=queries.version(id,true); draft(version); parser.validate(in.schema(),in.capabilities()); var battery=threshold(in.batteryLowThreshold(),in.capabilities());
        String hash=idem.fingerprint(List.of(id,in.expectedRevision(),in.schema(),in.capabilities(),Objects.toString(battery,"null"))); var replay=idem.replay(actor.user().id(),"version-edit",key,hash,ProductModels.Version.class);
        if(replay!=null) { revision(version,replay.revision()); return replay; } revision(version,in.expectedRevision());
        jdbc.update("UPDATE product_versions SET schema_json=?,capabilities_json=?,battery_low_comparator=?,battery_low_threshold=?,revision=revision+1,parser_tested=FALSE,test_summary_json=NULL,evidence_revision=NULL,evidence_fingerprint=NULL WHERE id=?",idem.json(in.schema()),idem.json(in.capabilities()),battery==null ? null : battery.comparator(),battery==null ? null : battery.threshold(),id);
        var result=queries.version(id,false); saved(actor,"version-edit",key,hash,result,id,request); return result;
    }
    @Transactional public ProductModels.TestResult test(AuthService.Identity actor,String id,String key,ProductInputs.TestVersion in,String request) {
        begin(actor,"products:write",key); var version=queries.version(id,true); draft(version);
        parser.validateSamples(in.samples()); var config=parser.validate(version.schema(),version.capabilities());
        String hash=idem.fingerprint(List.of(id,in.expectedRevision(),in.samples())); var replay=idem.replay(actor.user().id(),"version-test",key,hash,ProductModels.TestResult.class);
        if(replay!=null) { revision(version,replay.revision()); return replay; } revision(version,in.expectedRevision());
        var evaluation=parser.evaluate(config,in.samples()); long next=version.revision()+1; String fingerprint=parser.fingerprint(version.schema(),version.capabilities(),version.batteryLowThreshold());
        var summary=new ProductModels.TestSummary(evaluation.passed(),next,fingerprint,List.copyOf(in.samples()),evaluation.results());
        jdbc.update("UPDATE product_versions SET revision=?,parser_tested=?,test_summary_json=?,evidence_revision=?,evidence_fingerprint=? WHERE id=?",next,evaluation.passed(),idem.json(summary),next,fingerprint,id);
        var result=new ProductModels.TestResult(id,next,evaluation.passed(),evaluation.results()); saved(actor,"version-test",key,hash,result,id,request); return result;
    }
    @Transactional public ProductModels.Version publish(AuthService.Identity actor,String id,String key,ProductInputs.PublishVersion in,String request) {
        begin(actor,"products:publish",key);
        var productIds=jdbc.queryForList("SELECT product_id FROM product_versions WHERE id=?",String.class,id);
        if(productIds.isEmpty()) throw ApiError.missing();
        var product=queries.product(productIds.getFirst(),true); var version=queries.version(id,true);
        String hash=idem.fingerprint(List.of(id,in.expectedRevision())); var replay=idem.replay(actor.user().id(),"version-publish",key,hash,ProductModels.Version.class); if(replay!=null) return replay;
        if(!"ACTIVE".equals(product.state())) throw ApiError.conflict("PRODUCT_ARCHIVED","产品已归档，不能发布新版本");
        draft(version); revision(version,in.expectedRevision()); var config=parser.validate(version.schema(),version.capabilities()); var summary=version.parserTestSummary();
        if(!version.parserTested() || summary==null || !summary.passed() || summary.testedRevision()!=version.revision() || !summary.schemaFingerprint().equals(parser.fingerprint(version.schema(),version.capabilities(),version.batteryLowThreshold())) || !config.publishableCapabilities()) throw ApiError.conflict("PARSER_EVIDENCE_REQUIRED","发布需要当前revision完整且通过的合成测试证据");
        // Reverify retained evidence rather than trusting the boolean flag or a client-supplied status.
        var recheck=parser.evaluate(config,summary.samples()); if(!recheck.passed() || !recheck.results().equals(summary.results())) throw ApiError.conflict("PARSER_EVIDENCE_REQUIRED","保留测试证据与当前映射不一致");
        OffsetDateTime publishedAt=OffsetDateTime.now(clock);
        jdbc.update("UPDATE product_versions SET status='PUBLISHED',revision=revision+1,published_at=? WHERE id=?",publishedAt,id);
        jdbc.update("UPDATE device_service_definitions SET status='PUBLISHED',published_at=?,revision=revision+1 WHERE product_version_id=? AND status='DRAFT'",publishedAt,id);
        var result=queries.version(id,false); saved(actor,"version-publish",key,hash,result,id,request); return result;
    }
}
