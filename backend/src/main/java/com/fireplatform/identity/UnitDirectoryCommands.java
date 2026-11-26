package com.fireplatform.identity;

import com.fireplatform.shared.*;
import java.util.*;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnitDirectoryCommands {
    private final JdbcTemplate jdbc; private final ManagementGuard guard; private final SecretCommandStore store; private final UnitDirectoryQueries queries; private final AuditService audit; private final PermissionCatalog catalog; private final AuthService auth; private final PasswordEncoder encoder;
    public UnitDirectoryCommands(JdbcTemplate jdbc,ManagementGuard guard,SecretCommandStore store,UnitDirectoryQueries queries,AuditService audit,PermissionCatalog catalog,AuthService auth,PasswordEncoder encoder){this.jdbc=jdbc;this.guard=guard;this.store=store;this.queries=queries;this.audit=audit;this.catalog=catalog;this.auth=auth;this.encoder=encoder;}
    @Transactional public UnitDirectoryModels.UnitDetails create(AuthService.Identity actor,String key,UnitDirectoryModels.UnitCreate in,String requestId){
        guard.begin(actor,"units:write",null,"management");validateDates(in.serviceStart(),in.serviceEnd(),in.engineeringServiceStart(),in.engineeringServiceEnd());
        boolean hasAdmin=in.adminUsername()!=null&&!in.adminUsername().isBlank();
        List<String> allowed=List.of();
        if(hasAdmin){guard.begin(actor,"users:write",null,"management");if(in.adminPermissions()==null)throw ApiError.bad("必须显式提供管理员权限授权");if(!in.adminUsername().matches("[A-Za-z0-9][A-Za-z0-9_.@-]{2,99}"))throw ApiError.bad("管理员用户名格式无效");allowed=catalog.validate("CUSTOMER",in.adminPermissions(),auth.permissions(actor.user().id()));}
        else if(in.adminName()!=null||in.adminInitialPassword()!=null||in.adminPermissions()!=null&&!in.adminPermissions().isEmpty())throw ApiError.bad("创建单位管理员时必须同时提供用户名");
        String hash=store.hashBusiness(key,Arrays.asList(in.name(),in.creditCode(),in.regionId(),in.unitType(),in.industry(),in.mapAddress(),in.detailAddress(),in.contactName(),in.contactPhone(),in.remark(),in.latitude(),in.longitude(),in.serviceStart(),in.serviceEnd(),in.engineeringServiceStart(),in.engineeringServiceEnd(),hasAdmin,in.adminUsername()==null?"":in.adminUsername(),in.adminName()==null?"":in.adminName(),allowed));
        var replay=store.replay(actor.user().id(),"unit-register",key,hash,hasAdmin?in.adminInitialPassword():null,UnitDirectoryModels.UnitDetails.class);if(replay!=null)return replay;
        if(hasAdmin&&!Limits.validPassword(in.adminInitialPassword()))throw ApiError.bad("管理员初始" + Limits.PASSWORD_REQUIREMENTS);
        if(hasAdmin&&jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?",Long.class,in.adminUsername())!=0)throw ApiError.conflict("USERNAME_CONFLICT","用户名已被使用");
        String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO tenants(id,name,enabled,revision,credit_code,region_id,unit_type,industry,map_address,detail_address,contact_name,contact_phone,remark,latitude,longitude,service_start,service_end,engineering_service_start,engineering_service_end) VALUES (?,?,TRUE,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,in.name().trim(),in.creditCode(),in.regionId(),in.unitType(),in.industry(),in.mapAddress(),in.detailAddress(),in.contactName(),in.contactPhone(),in.remark(),in.latitude(),in.longitude(),in.serviceStart(),in.serviceEnd(),in.engineeringServiceStart(),in.engineeringServiceEnd());
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES (?,?)",actor.user().id(),id);jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES (?,?,'ALL_TENANT',1)",actor.user().id(),id);
        if(hasAdmin){String adminId=UUID.randomUUID().toString();jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,revision,is_protected) VALUES (?,?,?,'CUSTOMER',?,?,TRUE,TRUE,0,FALSE)",adminId,in.adminUsername(),in.adminName()==null||in.adminName().isBlank()?in.adminUsername():in.adminName(),id,encoder.encode(in.adminInitialPassword()));for(String permission:allowed)jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,?)",adminId,permission);jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES (?,?,'DEVICE_SET',1)",adminId,id);}
        var result=queries.detail(actor.user().id(),id);audit.record(actor.user().id(),id,"CREATE_UNIT",id,"SUCCESS",requestId);store.save(actor.user().id(),"unit-register",key,hash,hasAdmin?in.adminInitialPassword():null,result);return result;
    }
    @Transactional public UnitDirectoryModels.UnitDetails update(AuthService.Identity actor,String id,String key,UnitDirectoryModels.UnitWrite in,String requestId){
        guard.begin(actor,"units:write",id,"management");guard.grantRequired(actor.user().id(),id);validateDates(in.serviceStart(),in.serviceEnd(),in.engineeringServiceStart(),in.engineeringServiceEnd());
        String hash=store.hashBusiness(key,Arrays.asList(id,in.name(),in.creditCode(),in.regionId(),in.unitType(),in.industry(),in.mapAddress(),in.detailAddress(),in.contactName(),in.contactPhone(),in.remark(),in.latitude(),in.longitude(),in.serviceStart(),in.serviceEnd(),in.engineeringServiceStart(),in.engineeringServiceEnd(),in.expectedRevision()));
        var replay=store.replay(actor.user().id(),"unit-directory-update",key,hash,null,UnitDirectoryModels.UnitDetails.class);if(replay!=null)return replay;
        var rows=jdbc.queryForList("SELECT revision FROM tenants WHERE id=? FOR UPDATE",Long.class,id);if(rows.isEmpty())throw ApiError.missing();if(rows.getFirst()!=in.expectedRevision())throw ApiError.conflict("REVISION_CONFLICT","单位已更新，请刷新");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM tenants WHERE id=?",Boolean.class,id)))throw ApiError.conflict("UNIT_ARCHIVED","已归档单位不可修改");
        jdbc.update("UPDATE tenants SET name=?,credit_code=?,region_id=?,unit_type=?,industry=?,map_address=?,detail_address=?,contact_name=?,contact_phone=?,remark=?,latitude=?,longitude=?,service_start=?,service_end=?,engineering_service_start=?,engineering_service_end=?,revision=revision+1 WHERE id=? AND revision=?",in.name().trim(),in.creditCode(),in.regionId(),in.unitType(),in.industry(),in.mapAddress(),in.detailAddress(),in.contactName(),in.contactPhone(),in.remark(),in.latitude(),in.longitude(),in.serviceStart(),in.serviceEnd(),in.engineeringServiceStart(),in.engineeringServiceEnd(),id,in.expectedRevision());
        var result=queries.detail(actor.user().id(),id);audit.record(actor.user().id(),id,"UPDATE_UNIT_DETAILS",id,"SUCCESS",requestId);store.save(actor.user().id(),"unit-directory-update",key,hash,null,result);return result;
    }
    private static void validateDates(LocalDate start,LocalDate end,LocalDate engineeringStart,LocalDate engineeringEnd){if(start!=null&&end!=null&&start.isAfter(end)||engineeringStart!=null&&engineeringEnd!=null&&engineeringStart.isAfter(engineeringEnd))throw ApiError.bad("服务有效期起始日不能晚于截止日");}
}
