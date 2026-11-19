package com.fireplatform.legacyingest.importer;

import com.fireplatform.shared.ApiError;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegacyDictionaryImporter {
    public record ImportStats(int tenants,int places,int products,int versions,int attributes,int decoders,int differences,
            String hierarchyRunId,String hierarchySourceSha256,int hierarchyApplied,int hierarchySkipped) { }
    public record PilotDeviceImportStats(int devices,int aliases,int differences) { }
    public record PreviewExample(String pId,String model,String selectedLegacyProductId) { }
    public record PreviewCategory(int count,PreviewExample example) { }
    public record DevicePreviewStats(int totalRows,PreviewCategory importable,PreviewCategory placeMissing,
            PreviewCategory modelMultiple,PreviewCategory modelUnmatched,PreviewCategory deviceIdOrEuiInvalid) { }
    /** Read-only classification; no row is written by a preview call. */
    public record DictionaryPreview(String sourceSha256,int sourceCompanies,int sourcePlaces,int sourceProducts,
            int sourceAttributes,int sourceDecoderFiles,int damagedProductRows,int duplicateModelRows,int emptyLines,
            int placesTenantMissing,int placesIdentityConflict,int placesArchived,int versionsExisting,
            int decodersExisting,boolean hierarchyConfigured,int hierarchySourceRows,int grantLegacyUnits) { }
    public record CompanyCount(String companyId,int rows) { }
    public record DeviceImportPreview(String sourceSha256,int totalRows,int selectedRows,int importableNew,
            int existing,int archived,int moved,int placeMissing,int modelUnmatched,int modelAmbiguous,
            int modelSingle,int identityInvalid,int euiMissing,int euiConflict,int aliasConflict,
            List<CompanyCount> companies) { }
    private record Place(String id,String name) { }
    private record VersionCandidate(String id,String productId,String productName,long version,String legacyProductId,int attributes) { }
    private record Tsv(List<String> headers,List<List<String>> rows,int emptyLines) { }
    private record PlaceImport(int imported,int differences,String hierarchySha256,int hierarchyApplied,int hierarchySkipped) { }
    private record HierarchyImport(int differences,int applied,int skipped,String sourceSha256) { }
    private record ProductImport(int products,Map<String,String> versionsByLegacyId,int differences) { }
    private record Product(String id,String name,String manufacturer,String model) { }
    private final JdbcTemplate jdbc;
    private final String configuredAppendix;
    private final String configuredPlaceHierarchy;

    public LegacyDictionaryImporter(JdbcTemplate jdbc,@Value("${LEGACY_APPENDIX:}") String configuredAppendix,
            @Value("${LEGACY_PLACE_HIERARCHY_EXPORT:}") String configuredPlaceHierarchy) {
        this.jdbc=jdbc; this.configuredAppendix=configuredAppendix; this.configuredPlaceHierarchy=configuredPlaceHierarchy;
    }
    /**
     * Classifies every source device row without consulting or mutating the database.
     * Categories are exclusive in this order: identity issue, missing place, no model
     * candidate, multiple model candidates, importable.
     */
    public DevicePreviewStats previewAllDevices() {
        Path root=appendix();
        Tsv devices=read(root.resolve("legacy/devices_tsf.tsv"));
        Tsv places=read(root.resolve("legacy/places.tsv"));
        Tsv products=read(root.resolve("legacy/products.tsv"));
        Tsv attributes=read(root.resolve("legacy/product_attrs.tsv"));

        Set<String> placeIds=new HashSet<>();
        for(List<String> row:places.rows()) placeIds.add(value(places,row,"p_id").trim());

        Map<String,Integer> attributeCounts=new HashMap<>();
        for(List<String> row:attributes.rows()) {
            String productId=value(attributes,row,"pa_pid").trim();
            if(!productId.isEmpty()) attributeCounts.merge(productId,1,Integer::sum);
        }
        Map<String,Map<String,Integer>> candidatesByModel=new HashMap<>();
        for(List<String> row:products.rows()) {
            String pId=value(products,row,"p_id").trim(), model=value(products,row,"p_model").trim();
            if(!pId.isEmpty()&&!model.isEmpty())
                candidatesByModel.computeIfAbsent(model,ignored->new HashMap<>())
                    .merge(pId,attributeCounts.getOrDefault(pId,0),Math::max);
        }

        Map<String,Integer> deviceIdCounts=new HashMap<>(), euiCounts=new HashMap<>();
        for(List<String> row:devices.rows()) {
            countNonBlank(deviceIdCounts,value(devices,row,"d_device_id").trim());
            countNonBlank(euiCounts,value(devices,row,"d_eui").trim());
        }
        PreviewTally importable=new PreviewTally(), placeMissing=new PreviewTally(),
            modelMultiple=new PreviewTally(), modelUnmatched=new PreviewTally(), identityInvalid=new PreviewTally();
        for(List<String> row:devices.rows()) {
            String deviceId=value(devices,row,"d_device_id").trim(), eui=value(devices,row,"d_eui").trim();
            String pId=value(devices,row,"d_id").trim(), model=value(devices,row,"d_model").trim();
            PreviewExample example=new PreviewExample(pId,model,null);
            if(deviceId.isEmpty()||eui.isEmpty()||deviceIdCounts.getOrDefault(deviceId,0)>1||euiCounts.getOrDefault(eui,0)>1) {
                identityInvalid.add(example); continue;
            }
            if(!placeIds.contains(value(devices,row,"d_pid").trim())) {
                placeMissing.add(example); continue;
            }
            Map<String,Integer> candidates=candidatesByModel.getOrDefault(model,Map.of());
            if(candidates.isEmpty()) { modelUnmatched.add(example); continue; }
            if(candidates.size()>1) { modelMultiple.add(new PreviewExample(pId,model,null)); continue; }
            String selected=candidates.keySet().iterator().next();
            importable.add(new PreviewExample(pId,model,selected));
        }
        return new DevicePreviewStats(devices.rows().size(),importable.category(),placeMissing.category(),
            modelMultiple.category(),modelUnmatched.category(),identityInvalid.category());
    }

    /** Digest of every source that dictionary import reads, including decoder sources and hierarchy. */
    public String sourceDigest() {
        Path root=appendix();
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String name:List.of("legacy/companies.tsv","legacy/places.tsv","legacy/products.tsv","legacy/product_attrs.tsv")) {
                digest.update(name.getBytes(StandardCharsets.UTF_8)); digest.update((byte)0);
                digest.update(Files.readAllBytes(root.resolve(name)));
            }
            Path codecs=root.resolve("codecs");
            if(Files.isDirectory(codecs)) try(var files=Files.list(codecs)) {
                for(Path file:files.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8)); digest.update((byte)0);
                    digest.update(Files.readAllBytes(file));
                }
            }
            Path hierarchy=hierarchyPath();
            if(hierarchy!=null&&Files.isRegularFile(hierarchy)) {
                digest.update("hierarchy".getBytes(StandardCharsets.UTF_8)); digest.update((byte)0);
                digest.update(Files.readAllBytes(hierarchy));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(IOException|java.security.NoSuchAlgorithmException e) { throw ApiError.bad("旧平台归档目录不可读取"); }
    }

    /** Digest of the device ledger only; confirmation compares it before any write. */
    public String deviceSourceDigest() {
        Path root=appendix();
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(root.resolve("legacy/devices_tsf.tsv")));
            return HexFormat.of().formatHex(digest.digest());
        } catch(IOException|java.security.NoSuchAlgorithmException e) { throw ApiError.bad("旧平台设备台账不可读取"); }
    }

    /** Counts the source and existing conflicts without mutating any table. */
    public DictionaryPreview previewDictionaries() {
        Path root=appendix(); String sha=sourceDigest();
        Tsv companies=read(root.resolve("legacy/companies.tsv")), places=read(root.resolve("legacy/places.tsv"));
        Tsv products=read(root.resolve("legacy/products.tsv")), attributes=read(root.resolve("legacy/product_attrs.tsv"));
        int damaged=0,duplicateModels=0; Set<String> uniqueProducts=new HashSet<>();
        for(List<String> row:products.rows()) {
            String pId=value(products,row,"p_id").trim(), model=value(products,row,"p_model").trim();
            if(pId.isBlank()||"TBS-N151".equals(pId)||model.isBlank()) { damaged++; continue; }
            String key=cleanManufacturer(value(products,row,"p_manufacturer"))+"\u0000"+model;
            if(!uniqueProducts.add(key)) duplicateModels++;
        }
        int placesTenantMissing=0,placesIdentityConflict=0,placesArchived=0; Set<String> sourceCompanyIds=new HashSet<>();
        for(List<String> row:companies.rows()) { String id=value(companies,row,"c_id").trim(); if(!id.isBlank()) sourceCompanyIds.add(id); }
        for(List<String> row:places.rows()) {
            String legacyId=value(places,row,"p_id").trim(), tenantId="LEGACY-"+value(places,row,"p_cid").trim();
            if(legacyId.isBlank()) continue;
            Long tenantExists=jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id=?",Long.class,tenantId);
            if(tenantExists==null||tenantExists==0) { placesTenantMissing++; continue; }
            var existing=jdbc.query("SELECT tenant_id,legacy_place_id,archived_at IS NOT NULL FROM places WHERE id=?",
                (rs,rowNum)->new Object[]{rs.getString(1),rs.getString(2),rs.getBoolean(3)},"LEGACY-PLACE-"+legacyId);
            if(existing.isEmpty()) continue;
            Object[] row2=existing.getFirst();
            if(Boolean.TRUE.equals(row2[2])) placesArchived++;
            else if(!tenantId.equals(row2[0])||!legacyId.equals(row2[1])) placesIdentityConflict++;
        }
        int decoderFiles=0; Path codecs=root.resolve("codecs");
        if(Files.isDirectory(codecs)) try(var files=Files.list(codecs)) { decoderFiles=(int)files.filter(Files::isRegularFile).count(); } catch(IOException e) { throw ApiError.bad("旧平台解码器目录不可读取"); }
        int versionsExisting=count("SELECT COUNT(DISTINCT legacy_product_id) FROM product_versions WHERE legacy_product_id IS NOT NULL");
        int decodersExisting=count("SELECT COUNT(DISTINCT legacy_product_id) FROM legacy_product_decoders WHERE revision=1");
        LegacyPlaceHierarchy hierarchy=placeHierarchy();
        int hierarchyRows=hierarchy==null?0:hierarchy.places().size();
        int legacyUnits=count("SELECT COUNT(*) FROM tenants WHERE id LIKE 'LEGACY-%'");
        int newCompanies=(int)sourceCompanyIds.stream().filter(id->{
            Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id=?",Long.class,"LEGACY-"+id); return exists==null||exists==0; }).count();
        return new DictionaryPreview(sha,companies.rows().size(),places.rows().size(),uniqueProducts.size(),attributes.rows().size(),
            decoderFiles,damaged,duplicateModels,companies.emptyLines()+places.emptyLines()+products.emptyLines()+attributes.emptyLines(),
            placesTenantMissing,placesIdentityConflict,placesArchived,versionsExisting,decodersExisting,
            hierarchy!=null,hierarchyRows,legacyUnits+newCompanies);
    }

    /** Read-only device classification; categories describe conflicts and may overlap where import stops at the first one. */
    public DeviceImportPreview previewDevices(Collection<String> companyIds) {
        Path root=appendix(); Tsv devices=read(root.resolve("legacy/devices_tsf.tsv"));
        Set<String> selected=new HashSet<>(); for(String id:companyIds) if(id!=null&&!id.isBlank()) selected.add(id.trim());
        if(selected.isEmpty()) for(List<String> row:devices.rows()) selected.add(value(devices,row,"d_company_id").trim());
        Map<String,Integer> companyRows=new LinkedHashMap<>();
        for(List<String> row:devices.rows()) companyRows.merge(value(devices,row,"d_company_id").trim(),1,Integer::sum);
        Set<String> placeKeys=new HashSet<>();
        jdbc.query("SELECT tenant_id,legacy_place_id FROM places WHERE archived_at IS NULL",(rs,rowNum)->placeKeys.add(rs.getString(1)+"\u0000"+rs.getString(2)));
        Map<String,List<VersionCandidate>> candidateCache=new HashMap<>();
        Map<String,Object[]> existingDevices=new HashMap<>(),euiOwners=new HashMap<>();
        jdbc.query("SELECT id,tenant_id,device_eui,archived_at IS NOT NULL FROM devices WHERE id LIKE 'LEGACY-TSF-%'",(rs,rowNum)->{
            Object[] value=new Object[]{rs.getString(2),rs.getString(3),rs.getBoolean(4)};
            existingDevices.put(rs.getString(1),value);
            if(rs.getString(3)!=null) euiOwners.put(rs.getString(3),value);
            return null;
        });
        Map<String,String> aliasOwners=new HashMap<>();
        jdbc.query("SELECT tenant_id,kind,alias_value,device_id FROM device_aliases",(rs,rowNum)->{
            aliasOwners.put(rs.getString(1)+"\u0000"+rs.getString(2)+"\u0000"+rs.getString(3),rs.getString(4)); return null;
        });
        Set<String> claimedIds=new HashSet<>(),claimedEuis=new HashSet<>();
        int selectedRows=0,importableNew=0,existing=0,archived=0,moved=0,placeMissing=0,modelUnmatched=0,
            modelAmbiguous=0,modelSingle=0,identityInvalid=0,euiMissing=0,euiConflict=0,aliasConflict=0;
        for(List<String> row:devices.rows()) {
            String companyId=value(devices,row,"d_company_id").trim();
            if(!selected.contains(companyId)) continue;
            selectedRows++;
            String legacyId="TSF-"+value(devices,row,"d_id").trim(), deviceId="LEGACY-"+legacyId;
            String tenantId="LEGACY-"+companyId, legacyPlace=value(devices,row,"d_pid").trim();
            String eui=value(devices,row,"d_eui").trim(), vendorId=value(devices,row,"d_device_id").trim();
            String model=value(devices,row,"d_model").trim();
            if(vendorId.isEmpty()||!claimedIds.add(deviceId)) { identityInvalid++; continue; }
            if(!placeKeys.contains(tenantId+"\u0000"+legacyPlace)) { placeMissing++; continue; }
            List<VersionCandidate> candidates=candidateCache.computeIfAbsent(model,this::versionCandidates);
            if(candidates.isEmpty()) { modelUnmatched++; continue; }
            if(candidates.size()>1) modelAmbiguous++; else modelSingle++;
            Object[] current=existingDevices.get(deviceId);
            if(current!=null) {
                if(Boolean.TRUE.equals(current[2])) { archived++; continue; }
                if(!tenantId.equals(current[0])) { moved++; continue; }
                existing++;
                if(aliasOwner(aliasOwners,tenantId,"EUI",eui,deviceId)||aliasOwner(aliasOwners,tenantId,"VENDOR_ID",vendorId,deviceId)) aliasConflict++;
                continue;
            }
            if(eui.isEmpty()) { euiMissing++; continue; }
            if(euiOwners.containsKey(eui)||!claimedEuis.add(eui)) { euiConflict++; continue; }
            importableNew++;
            if(aliasOwner(aliasOwners,tenantId,"EUI",eui,deviceId)||aliasOwner(aliasOwners,tenantId,"VENDOR_ID",vendorId,deviceId)) aliasConflict++;
        }
        List<CompanyCount> companies=new ArrayList<>();
        companyRows.forEach((id,rows)->companies.add(new CompanyCount(id,rows)));
        return new DeviceImportPreview(deviceSourceDigest(),devices.rows().size(),selectedRows,importableNew,existing,archived,moved,
            placeMissing,modelUnmatched,modelAmbiguous,modelSingle,identityInvalid,euiMissing,euiConflict,aliasConflict,companies);
    }

    /** Source company ids offered to the operator; confirmation refuses values outside this set. */
    public java.util.Set<String> sourceCompanyIds() {
        Tsv devices=read(appendix().resolve("legacy/devices_tsf.tsv"));
        var ids=new java.util.LinkedHashSet<String>();
        for(List<String> row:devices.rows()) { String id=value(devices,row,"d_company_id").trim(); if(!id.isEmpty()) ids.add(id); }
        return java.util.Collections.unmodifiableSet(ids);
    }

    private static boolean aliasOwner(Map<String,String> owners,String tenantId,String kind,String value,String deviceId) {
        if(value==null||value.isBlank()) return false;
        String owner=owners.get(tenantId+"\u0000"+kind+"\u0000"+value);
        return owner!=null&&!owner.equals(deviceId);
    }

    private static void countNonBlank(Map<String,Integer> counts,String value) {
        if(!value.isEmpty()) counts.merge(value,1,Integer::sum);
    }
    private static final class PreviewTally {
        private int count;
        private PreviewExample example;
        private void add(PreviewExample value) { count++; if(example==null) example=value; }
        private PreviewCategory category() { return new PreviewCategory(count,example); }
    }

    @Transactional
    public ImportStats importDictionaries() { return importDictionaries(null); }

    /**
     * 导入创建的租户默认对任何人不可见（平台用户需显式逐租户授权）。
     * 因此导入完成后，把本次导入的租户授予执行导入的平台账号，避免“导入完看不到自己导的数据”。
     */
    public ImportStats importDictionaries(String actorId) {
        String runId=UUID.randomUUID().toString(); Path root=appendix();
        Tsv companies=read(root.resolve("legacy/companies.tsv")); Tsv places=read(root.resolve("legacy/places.tsv"));
        Tsv products=read(root.resolve("legacy/products.tsv")); Tsv attributes=read(root.resolve("legacy/product_attrs.tsv"));
        int differences=0;
        differences+=importCompanies(companies,runId);
        PlaceImport placeImport=importPlaces(places,runId,placeHierarchy());
        differences+=placeImport.differences();
        ProductImport imported=importProducts(products,runId);
        differences+=imported.differences();
        int attributeCount=importAttributes(attributes,imported.versionsByLegacyId(),runId);
        int decoderCount=importDecoders(root,imported.versionsByLegacyId().keySet(),runId);
        differences+=emptyLineDifference(runId,"companies.tsv",companies.emptyLines());
        differences+=emptyLineDifference(runId,"places.tsv",places.emptyLines());
        differences+=emptyLineDifference(runId,"products.tsv",products.emptyLines());
        differences+=emptyLineDifference(runId,"product_attrs.tsv",attributes.emptyLines());
        grantLegacyTenants(actorId,"legacy-dictionaries");
        return new ImportStats(count("SELECT COUNT(*) FROM tenants WHERE id LIKE 'LEGACY-%'"),placeImport.imported(),imported.products(),count("SELECT COUNT(*) FROM product_versions WHERE legacy_product_id IS NOT NULL"),attributeCount,decoderCount,differences,placeImport.hierarchySha256()==null?null:runId,placeImport.hierarchySha256(),placeImport.hierarchyApplied(),placeImport.hierarchySkipped());
    }
    @Transactional
    public PilotDeviceImportStats importPilotDevices(String runId) { return importDevices(runId,Set.of("311")); }

    @Transactional
    public PilotDeviceImportStats importPilotDevices(String runId,String actorId) {
        PilotDeviceImportStats stats=importDevices(runId,Set.of("311"));
        grantLegacyTenants(actorId,"legacy-devices");
        return stats;
    }

    @Transactional
    public PilotDeviceImportStats importDevices(String runId,Collection<String> companyIds,String actorId) {
        PilotDeviceImportStats stats=importDevices(runId,companyIds);
        grantLegacyTenants(actorId,"legacy-devices");
        return stats;
    }

    /** 让执行导入的平台账号能看到导入的旧单位数据；只授权 LEGACY-* 租户。 */
    private void grantLegacyTenants(String actorId,String target) {
        if(actorId==null||actorId.isBlank()) return;
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) SELECT ?, t.id FROM tenants t WHERE t.id LIKE 'LEGACY-%' AND NOT EXISTS(SELECT 1 FROM user_tenant_grants g WHERE g.user_id=? AND g.tenant_id=t.id)",actorId,actorId);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) SELECT ?, t.id, 'ALL_TENANT', 0 FROM tenants t WHERE t.id LIKE 'LEGACY-%' AND NOT EXISTS(SELECT 1 FROM user_device_scopes s WHERE s.user_id=? AND s.tenant_id=t.id)",actorId,actorId);
        Long audited=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id=? AND action='LEGACY_IMPORT_TENANT_GRANT' AND target_id=?",Long.class,actorId,target);
        if(audited==null||audited==0) jdbc.update("INSERT INTO audit_log(id,actor_id,tenant_id,action,target_id,outcome,request_id,occurred_at) VALUES (?,?,NULL,'LEGACY_IMPORT_TENANT_GRANT',?,'SUCCESS','legacy-import',CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),actorId,target);
    }

    /** 导入指定旧单位下的设备；公司集合为空时导入台账中出现的全部单位。 */
    @Transactional
    public PilotDeviceImportStats importDevices(String runId,Collection<String> companyIds) {
        Path root=appendix(); Tsv devices=read(root.resolve("legacy/devices_tsf.tsv"));
        Set<String> selectedCompanies=new HashSet<>(); for(String id:companyIds) if(id!=null&&!id.isBlank()) selectedCompanies.add(id.trim());
        if(selectedCompanies.isEmpty()) for(List<String> row:devices.rows()) selectedCompanies.add(value(devices,row,"d_company_id").trim());
        int importedDevices=0,importedAliases=0,differences=0;
        for(List<String> row:devices.rows()) {
            if(!selectedCompanies.contains(value(devices,row,"d_company_id").trim())) continue;
            String legacyId="TSF-"+value(devices,row,"d_id").trim(), legacyPlace=value(devices,row,"d_pid").trim();
            String tenantId="LEGACY-"+value(devices,row,"d_company_id").trim(), deviceId="LEGACY-"+legacyId;
            String name=value(devices,row,"d_name").trim(), eui=value(devices,row,"d_eui").trim(), vendorId=value(devices,row,"d_device_id").trim();
            String model=value(devices,row,"d_model").trim();
            List<Place> places=jdbc.query("SELECT id,name FROM places WHERE tenant_id=? AND legacy_place_id=? AND archived_at IS NULL",(rs,rowNum)->new Place(rs.getString(1),rs.getString(2)),tenantId,legacyPlace);
            if(places.isEmpty()) { differences+=differenceKind(runId,"TENANT_UNASSIGNED","PILOT_PLACE_MISSING",legacyId,null,"场所未在租户 "+tenantId+" 下找到，d_pid="+legacyPlace+"；设备未导入"); continue; }
            List<VersionCandidate> candidates=versionCandidates(model);
            if(candidates.isEmpty()) { differences+=difference(runId,"PILOT_PRODUCT_UNMAPPED",legacyId,null,"d_model="+model+" 未匹配到旧产品版本；设备未导入"); continue; }
            boolean ambiguous=candidates.size()>1;
            String candidateIds=candidates.stream().map(VersionCandidate::legacyProductId).toList().toString();
            Place place=places.get(0); String specific=value(devices,row,"d_address").trim();
            String address=specific.isBlank()?(place.name()==null||place.name().isBlank()?name:place.name()):specific;
            var existingRows=jdbc.query("SELECT tenant_id,address,archived_at IS NOT NULL FROM devices WHERE id=?",
                (rs,rowNum)->new Object[]{rs.getString(1),rs.getString(2),rs.getBoolean(3)},deviceId);
            if(!existingRows.isEmpty()) {
                Object[] existing=existingRows.getFirst();
                if(Boolean.TRUE.equals(existing[2])) { differences+=difference(runId,"PILOT_DEVICE_ARCHIVED",legacyId,deviceId,"设备已归档；不恢复、不改地址或别名"); continue; }
                if(!tenantId.equals(existing[0])) { differences+=difference(runId,"PILOT_DEVICE_MOVED",legacyId,deviceId,"设备已转移到其他单位；保留现有归属与绑定，不再回写来源单位"); continue; }
                if((existing[1]==null||((String)existing[1]).isBlank())&&!address.isBlank())
                    jdbc.update("UPDATE devices SET address=? WHERE id=? AND tenant_id=? AND archived_at IS NULL AND (address IS NULL OR address='')",address,deviceId,tenantId);
            } else {
                if(eui.isBlank()) { differences+=difference(runId,"PILOT_DEVICE_EUI_MISSING",legacyId,null,"设备缺少 d_eui，未导入"); continue; }
                List<String> euiOwner=jdbc.query("SELECT id FROM devices WHERE device_eui=?",(rs,rowNum)->rs.getString(1),eui);
                if(!euiOwner.isEmpty()) { differences+=difference(runId,"PILOT_EUI_CONFLICT",legacyId,euiOwner.getFirst(),"d_eui 已属于其他设备，未导入且未重绑定"); continue; }
                String state="3".equals(value(devices,row,"d_status").trim())?"FIRE":"UNKNOWN";
                if(ambiguous) {
                    differences+=difference(runId,"PILOT_MODEL_AMBIGUOUS",legacyId,null,"d_model="+model+" 命中候选 "+candidateIds+"，候选数 "+candidates.size()+"；未自动绑定产品版本，创建设备并保留待绑定状态");
                    jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,place_id,product_id,product_version_id,product_name,version_number,revision,integration_state) VALUES (?,?,?,?,?,?,?,FALSE,?,NULL,NULL,NULL,NULL,0,'PENDING_INTEGRATION')",
                        deviceId,tenantId,name,eui,model,address,state,place.id());
                } else {
                    VersionCandidate selected=candidates.get(0);
                    jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,place_id,product_id,product_version_id,product_name,version_number,revision,integration_state) VALUES (?,?,?,?,?,?,?,FALSE,?,?,?,?,?,0,'PENDING_INTEGRATION')",
                        deviceId,tenantId,name,eui,model,address,state,place.id(),selected.productId(),selected.id(),selected.productName(),selected.version());
                }
                importedDevices++;
            }
            importedAliases+=insertAlias(runId,tenantId,deviceId,legacyId,"EUI",eui);
            importedAliases+=insertAlias(runId,tenantId,deviceId,legacyId,"VENDOR_ID",vendorId);
        }
        return new PilotDeviceImportStats(importedDevices,importedAliases,differences);
    }

    private List<VersionCandidate> versionCandidates(String model) {
        return jdbc.query("SELECT pv.id,pv.product_id,p.name,pv.version_number,pv.legacy_product_id,COUNT(a.attribute_key) AS attribute_count FROM product_versions pv JOIN products p ON p.id=pv.product_id LEFT JOIN product_version_attributes a ON a.product_version_id=pv.id WHERE p.model=? AND pv.legacy_product_id IS NOT NULL AND pv.schema_format='LEGACY_ATTR_JS_V1' GROUP BY pv.id,pv.product_id,p.name,pv.version_number,pv.legacy_product_id ORDER BY COUNT(a.attribute_key) DESC,pv.version_number,pv.legacy_product_id",
            (rs,rowNum)->new VersionCandidate(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5),rs.getInt(6)),model);
    }

    private int insertAlias(String runId,String tenantId,String deviceId,String legacyId,String kind,String value) {
        if(value==null||value.isBlank()) return difference(runId,"PILOT_ALIAS_MISSING",legacyId,deviceId,"缺少 "+kind+" 别名值，已跳过");
        List<String> owners=jdbc.query("SELECT device_id FROM device_aliases WHERE tenant_id=? AND kind=? AND alias_value=?",(rs,rowNum)->rs.getString(1),tenantId,kind,value);
        if(!owners.isEmpty()) {
            if(!owners.get(0).equals(deviceId)) return differenceKind(runId,"ALIAS_CONFLICT","PILOT_ALIAS_CONFLICT",legacyId,owners.get(0),kind+" 别名与已有设备冲突，已跳过");
            return 0;
        }
        String aliasId=stableId("LEGACY-TSF-ALIAS",legacyId+"\\u0000"+kind);
        List<String[]> existing=jdbc.query("SELECT device_id,alias_value FROM device_aliases WHERE id=?",(rs,rowNum)->new String[]{rs.getString(1),rs.getString(2)},aliasId);
        if(!existing.isEmpty()) {
            if(existing.get(0)[0].equals(deviceId)) { jdbc.update("UPDATE device_aliases SET alias_value=? WHERE id=?",value,aliasId); return 0; }
            return differenceKind(runId,"ALIAS_CONFLICT","PILOT_ALIAS_CONFLICT",legacyId,existing.get(0)[0],kind+" 别名 id 与已有设备冲突，已跳过");
        }
        jdbc.update("INSERT INTO device_aliases(id,tenant_id,device_id,kind,alias_value,origin,created_at) VALUES (?,?,?,?,?,'LEGACY_TSF',?)",aliasId,tenantId,deviceId,kind,value,Timestamp.from(Instant.now()));
        return 1;
    }

    private int importCompanies(Tsv data,String runId) {
        int differences=0;
        for(List<String> row:data.rows()) {
            // Prefix legacy IDs to avoid collisions with native tenant IDs while preserving source identity.
            String legacyId=value(data,row,"c_id"), id="LEGACY-"+legacyId, name=value(data,row,"c_name");
            if(legacyId.isBlank()||name.isBlank()) { differences+=difference(runId,"DAMAGED_COMPANY",legacyId,null,"单位缺少 id 或名称"); continue; }
            Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id=?",Long.class,id);
            if(exists!=null&&exists>0) {
                String current=jdbc.queryForObject("SELECT name FROM tenants WHERE id=?",String.class,id);
                if(!Objects.equals(current,name)) differences+=difference(runId,"TENANT_NAME_PRESERVED",legacyId,id,"单位名称与来源不同，保留本地名称（来源："+name+"）");
            } else jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)",id,name);
        }
        return differences;
    }

    private PlaceImport importPlaces(Tsv data,String runId,LegacyPlaceHierarchy hierarchy) {
        int imported=0,differences=0;
        Set<String> importedIds=new LinkedHashSet<>();
        for(List<String> row:data.rows()) {
            String legacyId=value(data,row,"p_id").trim(), legacyTenant=value(data,row,"p_cid").trim(), name=value(data,row,"p_name").trim();
            if(legacyId.isBlank()||name.isBlank()) { differences+=difference(runId,"DAMAGED_PLACE",legacyId,null,"场所缺少 id 或名称"); continue; }
            String tenantId="LEGACY-"+legacyTenant;
            Long tenantExists=jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id=?",Long.class,tenantId);
            if(tenantExists==null||tenantExists==0) { differences+=difference(runId,"PLACE_TENANT_MISSING",legacyId,null,"p_id="+legacyId+" p_cid="+legacyTenant+" 对应单位不存在，场所未导入"); continue; }
            String id="LEGACY-PLACE-"+legacyId;
            Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=?",Long.class,id);
            if(exists!=null&&exists>0) {
                var current=jdbc.queryForMap("SELECT tenant_id,legacy_place_id,revision,name,archived_at FROM places WHERE id=?",id);
                if(current.get("archived_at")!=null) {
                    differences+=difference(runId,"PLACE_ARCHIVED",legacyId,id,"本地位置已归档；不恢复、不改名或改归属");
                    continue;
                }
                if(!tenantId.equals(current.get("tenant_id"))||!legacyId.equals(current.get("legacy_place_id"))) {
                    differences+=difference(runId,"PLACE_IDENTITY_MISMATCH",legacyId,id,"本地位置单位或旧位置ID已变更；保留现有所有权与元数据");
                    continue;
                }
                if(((Number)current.get("revision")).longValue()==0 && !Objects.equals(current.get("name"),name))
                    jdbc.update("UPDATE places SET name=? WHERE id=? AND tenant_id=? AND legacy_place_id=? AND revision=0 AND archived_at IS NULL",name,id,tenantId,legacyId);
            } else jdbc.update("INSERT INTO places(id,tenant_id,name,legacy_place_id) VALUES (?,?,?,?)",id,tenantId,name,legacyId);
            importedIds.add(legacyId);
            imported++;
        }
        HierarchyImport hierarchyResult=hierarchy==null?new HierarchyImport(0,0,0,null):applyImportedPlaceHierarchy(data,hierarchy,importedIds,runId);
        return new PlaceImport(imported,differences+hierarchyResult.differences(),hierarchyResult.sourceSha256(),hierarchyResult.applied(),hierarchyResult.skipped());
    }

    private HierarchyImport applyImportedPlaceHierarchy(Tsv places,LegacyPlaceHierarchy hierarchy,Set<String> importedIds,String runId) {
        int differences=0,applied=0,skipped=0;
        for(List<String> row:places.rows()) {
            String legacyId=value(places,row,"p_id").trim(), companyId=value(places,row,"p_cid").trim(), name=value(places,row,"p_name").trim();
            if(!importedIds.contains(legacyId)) continue;
            var source=hierarchy.places().get(legacyId);
            String localId="LEGACY-PLACE-"+legacyId, tenantId="LEGACY-"+companyId;
            if(!hierarchy.matchesPlace(legacyId,name,companyId)) {
                differences+=difference(runId,"PLACE_HIERARCHY_SOURCE_MISMATCH",legacyId,localId,"org_all.csv 不存在匹配 p_id/p_cid/p_name 的原始位置；未应用父级");
                recordHierarchyAudit(runId,tenantId,localId,legacyId,null,null,null,null,hierarchy.sha256(),"SOURCE_MISMATCH");
                skipped++;
                continue;
            }

            String sourceParentId=source.parentId();
            var sourceParent=sourceParentId==null?null:hierarchy.places().get(sourceParentId);
            String sourceParentCompany=sourceParent==null?null:sourceParent.companyId();
            jdbc.update("UPDATE places SET legacy_parent_place_id=?,legacy_parent_company_id=? WHERE id=? AND tenant_id=? AND legacy_place_id=? AND archived_at IS NULL",
                sourceParentId,sourceParentCompany,localId,tenantId,legacyId);

            String before=jdbc.queryForObject("SELECT parent_place_id FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",String.class,localId,tenantId);
            long revision=jdbc.queryForObject("SELECT revision FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",Long.class,localId,tenantId);
            String after=before, outcome;
            if(sourceParentId==null) outcome=before==null?"APPLIED":"PRESERVED_MANUAL";
            else if(!Objects.equals(sourceParentCompany,companyId)) {
                outcome="CROSS_TENANT_CONTEXT";
                differences+=difference(runId,"PLACE_HIERARCHY_CROSS_TENANT_PARENT",legacyId,localId,
                    "org_all.csv parent_id="+sourceParentId+" 的 company_id 与子位置不一致；仅保存只读来源关系");
            } else {
                String parentLocalId="LEGACY-PLACE-"+sourceParentId;
                Long parentExists=jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=? AND tenant_id=? AND legacy_place_id=? AND archived_at IS NULL",Long.class,parentLocalId,tenantId,sourceParentId);
                if(parentExists==null||parentExists==0) {
                    outcome="PARENT_UNAVAILABLE";
                    differences+=difference(runId,"PLACE_HIERARCHY_PARENT_UNAVAILABLE",legacyId,localId,
                        "org_all.csv parent_id="+sourceParentId+" 未导入到同一单位；保留只读来源关系");
                } else if(before==null&&revision==0) {
                    jdbc.update("UPDATE places SET parent_place_id=?,revision=revision+1 WHERE id=? AND tenant_id=? AND parent_place_id IS NULL AND revision=0 AND archived_at IS NULL",
                        parentLocalId,localId,tenantId);
                    after=parentLocalId;
                    outcome="APPLIED";
                } else if(parentLocalId.equals(before)) outcome="APPLIED";
                else outcome="PRESERVED_MANUAL";
            }
            recordHierarchyAudit(runId,tenantId,localId,legacyId,before,after,sourceParentId,sourceParentCompany,hierarchy.sha256(),outcome);
            if(outcome.equals("APPLIED")) applied++; else skipped++;
        }
        return new HierarchyImport(differences,applied,skipped,hierarchy.sha256());
    }

    private void recordHierarchyAudit(String runId,String tenantId,String placeId,String legacyPlaceId,String beforeParent,String afterParent,
            String sourceParent,String sourceParentCompany,String sourceSha,String outcome) {
        Long existing=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_place_hierarchy_repair_audit WHERE run_id=? AND place_id=? AND outcome=?",
            Long.class,runId,placeId,outcome);
        if(existing==null||existing==0) jdbc.update(
            "INSERT INTO legacy_place_hierarchy_repair_audit(id,run_id,tenant_id,place_id,legacy_place_id,before_parent_place_id,after_parent_place_id,source_parent_place_id,source_parent_company_id,source_sha256,outcome,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),runId,tenantId,placeId,legacyPlaceId,beforeParent,afterParent,sourceParent,sourceParentCompany,sourceSha,outcome,Timestamp.from(Instant.now()));
    }

    // Numeric p_ids retain their value; textual ids use collision-resolved negative SHA-256 prefixes.
    private Map<String,Long> versionNumbers(Tsv data) {
        Map<String,Long> numbers=new LinkedHashMap<>(); Set<String> nonNumeric=new TreeSet<>(); Set<Long> used=new HashSet<>();
        for(List<String> row:data.rows()) {
            String pId=value(data,row,"p_id").trim(); if(pId.isBlank()) continue;
            if(pId.matches("[0-9]+")) {
                try { long number=Long.parseLong(pId); numbers.putIfAbsent(pId,number); used.add(number); continue; }
                catch(NumberFormatException ignored) { }
            }
            nonNumeric.add(pId);
        }
        for(String pId:nonNumeric) {
            try {
                long number=-1L-Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(pId.getBytes(StandardCharsets.UTF_8))).getInt());
                while(!used.add(number)) number--;
                numbers.put(pId,number);
            } catch(Exception e) { throw new IllegalStateException("Unable to derive stable legacy version number",e); }
        }
        return numbers;
    }

    private ProductImport importProducts(Tsv data,String runId) {
        Map<String,Product> normalized=new LinkedHashMap<>(); Map<String,String> versions=new LinkedHashMap<>(); Map<String,Integer> duplicateGroups=new LinkedHashMap<>(); int differences=0;
        Map<String,Long> versionNumbers=versionNumbers(data);
        for(List<String> row:data.rows()) {
            String pId=value(data,row,"p_id").trim();
            if(pId.isBlank()) { differences+=difference(runId,"DAMAGED_PRODUCT_ID","<blank>",null,"产品 p_id 缺失，记录未导入"); continue; }
            if("TBS-N151".equals(pId)) { differences+=difference(runId,"CORRUPT_ROW",pId,null,"p_id 断行损坏（包含换行），该记录已排除"); continue; }
            if(!pId.matches("[0-9]+")) differences+=difference(runId,"DAMAGED_PRODUCT_ID",pId,null,"产品 p_id 非数字，已使用稳定哈希版本号");
            String model=value(data,row,"p_model").trim(), manufacturer=cleanManufacturer(value(data,row,"p_manufacturer"));
            if(model.isBlank()) { differences+=difference(runId,"DAMAGED_PRODUCT",pId,null,"产品型号为空，记录未导入"); continue; }
            String key=manufacturer+"\u0000"+model;
            String id=stableId("LEGACY-PRODUCT",key); Product p=new Product(id,value(data,row,"p_name").trim(),manufacturer,model);
            Product previous=normalized.putIfAbsent(key,p); if(previous!=null) duplicateGroups.merge(key,1,Integer::sum);
            List<String> existingIds=jdbc.query("SELECT id FROM products WHERE manufacturer=? AND model=?",(rs,rowNum)->rs.getString(1),manufacturer,model);
            if(!existingIds.isEmpty()) {
                id=existingIds.get(0);
                String currentName=jdbc.queryForObject("SELECT name FROM products WHERE id=?",String.class,id);
                if(!Objects.equals(currentName,p.name())) differences+=difference(runId,"PRODUCT_NAME_PRESERVED",pId,id,"产品名称与来源不同，保留本地名称（来源："+p.name()+"）");
            }
            else jdbc.update("INSERT INTO products(id,name,manufacturer,model,description) VALUES (?,?,?,?,?)",id,p.name(),manufacturer,model,"Imported from legacy dictionary");
            long versionNumber=versionNumbers.get(pId);
            var existingVersions=jdbc.query("SELECT id,version_number,schema_format,legacy_decoder_revision FROM product_versions WHERE legacy_product_id=? ORDER BY version_number,id",
                (rs,rowNum)->new Object[]{rs.getString(1),rs.getLong(2),rs.getString(3),rs.getObject(4,Long.class)},pId);
            if(!existingVersions.isEmpty()) {
                Object[] existing=existingVersions.getFirst();
                versions.put(pId,(String)existing[0]);
                boolean differs=((Long)existing[1]).longValue()!=versionNumber
                    || !"LEGACY_ATTR_JS_V1".equals(existing[2])
                    || existing[3]==null || ((Long)existing[3]).longValue()!=1L;
                if(differs) differences+=difference(runId,"VERSION_METADATA_PRESERVED",pId,(String)existing[0],"产品版本字段与来源不同，保留本地版本号/格式/解码器修订");
            } else {
                String versionId=stableId("LEGACY-VERSION",pId); versions.put(pId,versionId);
                jdbc.update("INSERT INTO product_versions(id,product_id,version_number,status,schema_json,capabilities_json,created_at,schema_format,legacy_product_id,legacy_decoder_revision) VALUES (?,?,?,'DRAFT','{}','{}',?,'LEGACY_ATTR_JS_V1',?,1)",versionId,id,versionNumber,Timestamp.from(Instant.now()),pId);
            }
            if(isDirtyManufacturer(value(data,row,"p_manufacturer"))) { difference(runId,"DIRTY_MANUFACTURER",pId,null,"manufacturer 已归一为 未标注"); differences++; }
        }
        for(var entry:duplicateGroups.entrySet()) { String[] identity=entry.getKey().split("\u0000",2); difference(runId,"DUPLICATE_MODEL",identity[1],null,"重复型号已按 manufacturer/model 归一: "+identity[0]+" / "+identity[1]); differences++; }
        int missingKeys=(int)data.rows().stream().filter(row->!value(data,row,"p_id").isBlank()).count();
        if(missingKeys>0) { difference(runId,"P_KEY_PENDING","products.tsv",null,"p_key 待提供；导入器不读取或保存其值"); differences++; }
        return new ProductImport(normalized.size(),versions,differences);
    }

    private int importAttributes(Tsv data,Map<String,String> versions,String runId) {
        int count=0;
        for(List<String> row:data.rows()) {
            String productId=value(data,row,"pa_pid").trim(), versionId=versions.get(productId); if(versionId==null) continue;
            String attrId=value(data,row,"pa_id").trim(); if(attrId.isBlank()) { difference(runId,"DAMAGED_ATTRIBUTE",productId,null,"属性缺少 pa_id，记录未导入"); continue; }
            String type=value(data,row,"pa_data_type").trim(); if(type.isBlank()||type.equalsIgnoreCase("NULL")) type="unknown";
            String range=value(data,row,"pa_data_range").trim(), enumJson=null; BigDecimal min=null,max=null;
            try { if(range.startsWith("[")||range.startsWith("{")) enumJson=range; else { String[] bounds=range.split("\\s*(?:\\.\\.|,|~)\\s*",-1); if(bounds.length==2) { min=new BigDecimal(bounds[0]); max=new BigDecimal(bounds[1]); } } } catch(NumberFormatException ignored) { min=null; max=null; }
            String attrName=value(data,row,"pa_name").isBlank()?attrId:value(data,row,"pa_name"), unit=nullable(value(data,row,"pa_unit"));
            var existingRows=jdbc.query("SELECT name,unit,data_type,enum_json,num_min,num_max,legacy_attr_id FROM product_version_attributes WHERE product_version_id=? AND attribute_key=?",
                (rs,rowNum)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getString(7)},versionId,attrId);
            if(existingRows.isEmpty()) jdbc.update("INSERT INTO product_version_attributes(product_version_id,attribute_key,name,unit,data_type,enum_json,num_min,num_max,legacy_attr_id) VALUES (?,?,?,?,?,?,?,?,?)",versionId,attrId,attrName,unit,type,enumJson,min,max,attrId);
            else {
                Object[] existing=existingRows.getFirst();
                boolean same=Objects.equals(existing[0],attrName)&&Objects.equals(existing[1],unit)&&Objects.equals(existing[2],type)
                    &&Objects.equals(existing[3],enumJson)&&sameNumber((BigDecimal)existing[4],min)&&sameNumber((BigDecimal)existing[5],max)
                    &&Objects.equals(existing[6],attrId);
                if(!same) difference(runId,"ATTRIBUTE_METADATA_PRESERVED",productId,versionId+":"+attrId,"属性定义与来源不同，保留本地定义");
            }
            count++;
        }
        return count;
    }

    private int importDecoders(Path root,Set<String> productIds,String runId) {
        int count=0;
        for(String productId:productIds) {
            Path file=root.resolve("codecs").resolve(productId+".js"); if(!Files.isRegularFile(file)) { difference(runId,"DECODER_MISSING",productId,null,"未找到对应旧解码器源码"); continue; }
            try { byte[] bytes=Files.readAllBytes(file); String source=new String(bytes,StandardCharsets.UTF_8); String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                var existing=jdbc.query("SELECT source_sha256 FROM legacy_product_decoders WHERE legacy_product_id=? AND revision=1",
                    (rs,rowNum)->rs.getString(1),productId);
                if(existing.isEmpty()) jdbc.update("INSERT INTO legacy_product_decoders(legacy_product_id,revision,source,source_sha256,created_at) VALUES (?,1,?,?,?)",productId,source,hash,Timestamp.from(Instant.now()));
                else if(!existing.getFirst().equalsIgnoreCase(hash)) difference(runId,"DECODER_SOURCE_PRESERVED",productId,null,"来源解码器与本地 revision 1 不同，保留本地源码；如需更新请使用解码器导入");
                count++;
            } catch(Exception e) { throw new IllegalStateException("Unable to import decoder source for legacy product "+productId,e); }
        }
        return count;
    }

    private int difference(String runId,String category,String legacyId,String newId,String detail) {
        return differenceKind(runId,"LEGACY_FIELD_MISMATCH",category,legacyId,newId,detail);
    }
    private int differenceKind(String runId,String kind,String category,String legacyId,String newId,String detail) {
        String safeDetail="IMPORTER:"+category+": "+detail;
        if(safeDetail.length()>1000) safeDetail=safeDetail.substring(0,1000);
        Long existing=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_reconcile_differences WHERE kind=? AND legacy_id=? AND COALESCE(new_id,'')=COALESCE(?,'') AND detail=?",Long.class,kind,legacyId,newId,safeDetail);
        if(existing!=null&&existing>0)return 0;
        jdbc.update("INSERT INTO legacy_reconcile_differences(id,run_id,kind,legacy_id,new_id,detail,created_at) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID().toString(),runId,kind,legacyId==null||legacyId.isBlank()?"unknown":legacyId,newId,safeDetail,Timestamp.from(Instant.now())); return 1;
    }


    private int emptyLineDifference(String runId,String file,int count) { return count==0?0:difference(runId,"EMPTY_LINE",file,null,"忽略空行数量: "+count); }
    private int count(String sql) { Long result=jdbc.queryForObject(sql,Long.class); return result==null?0:Math.toIntExact(result); }

    private static String cleanManufacturer(String value) { return isDirtyManufacturer(value)?"未标注":value.trim(); }
    private static boolean isDirtyManufacturer(String value) { return value==null||value.isBlank()||value.trim().equalsIgnoreCase("NULL")||value.trim().equals("10"); }
    private static String nullable(String value) { return value==null||value.isBlank()||value.equalsIgnoreCase("NULL")?null:value; }
    private static boolean sameNumber(BigDecimal left,BigDecimal right) { return left==null?right==null:right!=null&&left.compareTo(right)==0; }
    private static String stableId(String prefix,String value) { return prefix+"-"+UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }

    private Path hierarchyPath() {
        String source=configuredPlaceHierarchy;
        if(source==null||source.isBlank()) source=System.getenv("LEGACY_PLACE_HIERARCHY_EXPORT");
        return source==null||source.isBlank()?null:Path.of(source);
    }

    private LegacyPlaceHierarchy placeHierarchy() {
        Path source=hierarchyPath();
        if(source==null) return null;
        try { return LegacyPlaceHierarchy.parse(Files.readString(source,StandardCharsets.UTF_8)); }
        catch(IOException e) { throw new IllegalStateException("Unable to read legacy place hierarchy export",e); }
    }

    private Path appendix() {
        List<String> candidates=new ArrayList<>(); if(configuredAppendix!=null&&!configuredAppendix.isBlank()) candidates.add(configuredAppendix); String env=System.getenv("LEGACY_APPENDIX"); if(env!=null&&!env.isBlank()) candidates.add(env);
        candidates.add("/mnt/d/烟感/_incoming/omp-docs/appendix"); candidates.add("D:/烟感/_incoming/omp-docs/appendix");
        for(String candidate:candidates) { Path path=Path.of(candidate); if(Files.isDirectory(path.resolve("legacy"))&&Files.isDirectory(path.resolve("codecs"))) return path; }
        throw ApiError.bad("未找到旧平台归档目录；请由运维配置 LEGACY_APPENDIX");
    }

    private static Tsv read(Path file) {
        try {
            List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8); if(lines.isEmpty()) throw new IllegalStateException("Empty TSV: "+file.getFileName());
            List<String> headers=List.of(lines.get(0).split("\\t",-1)); List<List<String>> rows=new ArrayList<>(); int empty=0;
            for(String line:lines.subList(1,lines.size())) {
                if(line.isBlank()) { empty++; continue; }
                String[] cells=line.split("\\t",-1); if(cells.length!=headers.size()) { // A damaged line is retained for product p_id reconciliation, never guessed into another field.
                    List<String> malformed=new ArrayList<>(Collections.nCopies(headers.size(),"")); malformed.set(0,cells[0].trim()); rows.add(malformed); continue;
                }
                int pKeyIndex=headers.indexOf("p_key"); if(pKeyIndex>=0) cells[pKeyIndex]="";
                rows.add(List.of(cells));
            }
            return new Tsv(headers,rows,empty);
        } catch(IOException e) { throw new IllegalStateException("Unable to read legacy TSV "+file.getFileName(),e); }
    }
    private static String value(Tsv data,List<String> row,String column) { int index=data.headers().indexOf(column); return index<0||index>=row.size()?"":row.get(index); }
}
