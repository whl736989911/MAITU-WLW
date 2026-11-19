package com.fireplatform.device;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.DeviceScopes;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** N18：设备人工经纬度与无底图分布。坐标只由人工维护，不推断、不接入地图服务。 */
@Service
public class DeviceLocationService {
    public record Point(String id,String name,String address,String physicalState,String latitude,String longitude) { }
    public record Distribution(List<Point> points,long withCoordinates,long withoutCoordinates,String semantics) { }

    private final JdbcTemplate jdbc; private final ManagementGuard guard; private final DeviceScopes scopes; private final DeviceQueryService devices; private final ScopeFilter scopeFilter;
    public DeviceLocationService(JdbcTemplate jdbc,ManagementGuard guard,DeviceScopes scopes,DeviceQueryService devices,ScopeFilter scopeFilter) { this.jdbc=jdbc; this.guard=guard; this.scopes=scopes; this.devices=devices; this.scopeFilter=scopeFilter; }

    private static BigDecimal bound(BigDecimal value,BigDecimal min,BigDecimal max,String label) {
        if(value==null) throw ApiError.bad("必须同时提供经纬度");
        if(value.compareTo(min)<0 || value.compareTo(max)>0) throw ApiError.bad(label+"超出合法范围");
        return value.setScale(6,java.math.RoundingMode.HALF_UP);
    }

    @Transactional public DeviceQueryService.DeviceRow set(AuthService.Identity actor,String tenant,String id,BigDecimal latitude,BigDecimal longitude,long expectedRevision) {
        guard.begin(actor,"device:write",tenant,"management");
        BigDecimal lat=bound(latitude,new BigDecimal("-90"),new BigDecimal("90"),"纬度");
        BigDecimal lng=bound(longitude,new BigDecimal("-180"),new BigDecimal("180"),"经度");
        var current=devices.device(actor.user().id(),tenant,id,true);
        if(current.revision()!=expectedRevision) throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新");
        var scope=scopes.predicate(actor.user().id(),"devices.tenant_id","devices.id");
        var args=new ArrayList<Object>(); args.add(lat); args.add(lng); args.add(id); args.add(tenant); args.add(expectedRevision); args.addAll(scope.bindings());
        if(jdbc.update("UPDATE devices SET latitude=?,longitude=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?"+scope.and(),args.toArray())!=1) throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新");
        return devices.device(actor.user().id(),tenant,id,false);
    }

    @Transactional public DeviceQueryService.DeviceRow clear(AuthService.Identity actor,String tenant,String id,long expectedRevision) {
        guard.begin(actor,"device:write",tenant,"management");
        devices.device(actor.user().id(),tenant,id,true);
        var scope=scopes.predicate(actor.user().id(),"devices.tenant_id","devices.id");
        var args=new ArrayList<Object>(); args.add(id); args.add(tenant); args.add(expectedRevision); args.addAll(scope.bindings());
        if(jdbc.update("UPDATE devices SET latitude=NULL,longitude=NULL,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?"+scope.and(),args.toArray())!=1) throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新");
        return devices.device(actor.user().id(),tenant,id,false);
    }

    @Transactional(readOnly=true) public Distribution distribution(String actor,String tenant) { return distribution(actor,tenant,ScopeFilter.Selection.all()); }
    @Transactional(readOnly=true) public Distribution distribution(String actor,String tenant,ScopeFilter.Selection selection) {
        var scope=scopes.predicate(actor,"d.tenant_id","d.id"); var tree=scopeFilter.devices(actor,"d",tenant,selection);
        var args=new ArrayList<Object>();args.addAll(scope.bindings());args.addAll(tree.bindings());
        String where=" FROM devices d WHERE d.archived_at IS NULL"+scope.and()+" AND ("+tree.sql()+")";
        var points=jdbc.query("SELECT d.id,d.name,d.address,d.physical_state,d.latitude,d.longitude"+where+" AND d.latitude IS NOT NULL AND d.longitude IS NOT NULL ORDER BY d.id",(rs,n)->new Point(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBigDecimal(5).toPlainString(),rs.getBigDecimal(6).toPlainString()),args.toArray());
        var total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());
        long with=points.size(); long without=(total==null?0:total)-with;
        return new Distribution(points,with,without,"坐标由人工维护，不推断；无底图，不引入地图服务或建筑楼层体系。客户界面仍只显示文字地址。");
    }
}
