package com.fireplatform.simembedded;

import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.simembedded.SimEmbeddedModels.*;

@Service
public class SimEmbeddedQueries {
    private final JdbcTemplate jdbc;private final PageSizePolicy sizes;private final TransactionRequirements transactions;private final DeviceAccessScope scope;
    public SimEmbeddedQueries(JdbcTemplate jdbc,PageSizePolicy sizes,TransactionRequirements transactions,DeviceAccessScope scope){this.jdbc=jdbc;this.sizes=sizes;this.transactions=transactions;this.scope=scope;}
    public void device(String actor,String tenant,String device,boolean lock){
        SimEmbeddedInputs.device(device);var access=scope.predicate(actor,"devices.tenant_id","devices.id");
        var args=new ArrayList<Object>();args.add(tenant);args.add(device);args.addAll(access.bindings());
        var rows=jdbc.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=?"+access.and()+(lock?" FOR UPDATE":""),String.class,args.toArray());
        if(rows.isEmpty())throw SimEmbeddedProjection.missing();if(rows.size()!=1||!device.equals(rows.getFirst()))throw SimEmbeddedProjection.unconfirmed();
    }
    public Profile current(String tenant,String device,boolean lock){var rows=jdbc.query("SELECT "+SimEmbeddedProjection.PROFILE_COLUMNS+" FROM sim_embedded_profiles WHERE tenant_id=? AND device_id=?"+(lock?" FOR UPDATE":""),(rs,n)->SimEmbeddedProjection.profile(rs,tenant,device),tenant,device);if(rows.size()>1)throw SimEmbeddedProjection.unconfirmed();return rows.isEmpty()?SimEmbeddedProjection.empty(tenant,device):rows.getFirst();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Profile profile(AuthService.Identity actor,String tenant,String device){transactions.repeatableReadOnly();device(actor.user().id(),tenant,device,false);return current(tenant,device,false);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public HistoryPage history(AuthService.Identity actor,String tenant,String device,int number,Integer requested){
        transactions.repeatableReadOnly();long configured=sizes.defaultPageSize();var policy=Limits.SETTINGS.get("default_page_size");if(configured<policy.min()||configured>policy.max())throw SimEmbeddedProjection.unconfirmed();Pagination page=Pagination.of(number,requested,configured);device(actor.user().id(),tenant,device,false);Profile current=current(tenant,device,false);
        var counts=jdbc.query("SELECT COUNT(*) FROM sim_embedded_profile_changes WHERE tenant_id=? AND device_id=?",(rs,n)->SimEmbeddedProjection.number(rs.getObject(1)),tenant,device);if(counts.size()!=1)throw SimEmbeddedProjection.unconfirmed();long total=counts.getFirst();if(total!=current.revision())throw SimEmbeddedProjection.unconfirmed();
        var rows=jdbc.query("SELECT "+SimEmbeddedProjection.CHANGE_COLUMNS+" FROM sim_embedded_profile_changes WHERE tenant_id=? AND device_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",(rs,n)->SimEmbeddedProjection.change(rs,current),tenant,device,page.size(),page.offset());if(rows.size()!=(int)Math.min(page.size(),Math.max(0,total-page.offset())))throw SimEmbeddedProjection.unconfirmed();
        Set<String> ids=new HashSet<>();for(int i=0;i<rows.size();i++){Change row=rows.get(i);if(!ids.add(row.changeId())||row.revision()!=total-page.offset()-i)throw SimEmbeddedProjection.unconfirmed();}return new HistoryPage(List.copyOf(rows),total,page.page(),page.size(),total/page.size()+(total%page.size()==0?0:1));
    }
}
