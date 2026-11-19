package com.fireplatform.simbinding;

import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.simbinding.SimBindingModels.*;

@Service
public class SimBindingQueries {
    private final JdbcTemplate jdbc;private final PageSizePolicy sizes;private final TransactionRequirements transactions;private final DeviceAccessScope scope;
    public SimBindingQueries(JdbcTemplate jdbc,PageSizePolicy sizes,TransactionRequirements transactions,DeviceAccessScope scope){this.jdbc=jdbc;this.sizes=sizes;this.transactions=transactions;this.scope=scope;}
    public void card(String tenant,String card,boolean lock){
        if(!SimBindingProjection.uuid(card))throw SimBindingProjection.cardMissing();
        var rows=jdbc.queryForList("SELECT id FROM sim_cards WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),String.class,tenant,card);
        if(rows.isEmpty())throw SimBindingProjection.cardMissing();if(rows.size()!=1||!card.equals(rows.getFirst()))throw SimBindingProjection.unconfirmed();
    }
    public State current(String tenant,String card,boolean lock){
        var rows=jdbc.query("SELECT "+SimBindingProjection.STATE_COLUMNS+" FROM sim_card_associations WHERE tenant_id=? AND card_id=?"+(lock?" FOR UPDATE":""),(rs,n)->SimBindingProjection.state(rs,tenant,card),tenant,card);
        if(rows.size()>1)throw SimBindingProjection.unconfirmed();return rows.isEmpty()?SimBindingProjection.empty(tenant,card):rows.getFirst();
    }
    private State visibleCurrent(AuthService.Identity actor,String tenant,String card){
        var access=scope.predicate(actor.user().id(),"sim_card_associations.tenant_id","sim_card_associations.device_id");
        var args=new ArrayList<Object>(access.bindings());args.add(tenant);args.add(card);
        var rows=jdbc.query("SELECT "+SimBindingProjection.STATE_COLUMNS+",("+access.sql()+") AS device_allowed FROM sim_card_associations WHERE tenant_id=? AND card_id=?",(rs,n)->{
            State value=SimBindingProjection.state(rs,tenant,card);
            return value.deviceId()!=null&&!rs.getBoolean("device_allowed")
                ?new State(value.cardId(),value.tenantId(),null,DeviceVisibility.CONCEALED,value.revision(),value.recordedAt()):value;
        },args.toArray());
        if(rows.size()>1)throw SimBindingProjection.unconfirmed();
        return rows.isEmpty()?SimBindingProjection.empty(tenant,card):rows.getFirst();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public State state(AuthService.Identity actor,String tenant,String card){transactions.repeatableReadOnly();card(tenant,card,false);return visibleCurrent(actor,tenant,card);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public HistoryPage history(AuthService.Identity actor,String tenant,String card,int number,Integer requested){
        transactions.repeatableReadOnly();long configured=sizes.defaultPageSize();var definition=Limits.SETTINGS.get("default_page_size");if(configured<definition.min()||configured>definition.max())throw SimBindingProjection.unconfirmed();Pagination page=Pagination.of(number,requested,configured);
        card(tenant,card,false);State current=current(tenant,card,false);
        var counts=jdbc.query("SELECT COUNT(*) FROM sim_card_association_changes WHERE tenant_id=? AND card_id=?",(rs,n)->SimBindingProjection.number(rs.getObject(1)),tenant,card);if(counts.size()!=1)throw SimBindingProjection.unconfirmed();long total=counts.getFirst();if(total!=current.revision())throw SimBindingProjection.unconfirmed();
        var from=scope.predicate(actor.user().id(),"sim_card_association_changes.tenant_id","sim_card_association_changes.from_device_id");
        var to=scope.predicate(actor.user().id(),"sim_card_association_changes.tenant_id","sim_card_association_changes.to_device_id");
        var args=new ArrayList<Object>(from.bindings());args.addAll(to.bindings());args.add(tenant);args.add(card);args.add(page.size());args.add(page.offset());
        Set<String> ids=new HashSet<>();Change[] previous=new Change[1];
        var rows=jdbc.query("SELECT "+SimBindingProjection.CHANGE_COLUMNS+",("+from.sql()+") AS from_allowed,("+to.sql()+") AS to_allowed FROM sim_card_association_changes WHERE tenant_id=? AND card_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",(rs,n)->{
            Change row=SimBindingProjection.change(rs,current);
            if(!ids.add(row.changeId())||row.revision()!=current.revision()-page.offset()-n||(previous[0]!=null&&!Objects.equals(previous[0].fromDeviceId(),row.toDeviceId())))throw SimBindingProjection.unconfirmed();
            previous[0]=row;
            EndpointVisibility fromVisibility=row.fromDeviceId()==null?EndpointVisibility.ABSENT:rs.getBoolean("from_allowed")?EndpointVisibility.VISIBLE:EndpointVisibility.CONCEALED;
            EndpointVisibility toVisibility=row.toDeviceId()==null?EndpointVisibility.ABSENT:rs.getBoolean("to_allowed")?EndpointVisibility.VISIBLE:EndpointVisibility.CONCEALED;
            if(fromVisibility!=EndpointVisibility.CONCEALED&&toVisibility!=EndpointVisibility.CONCEALED)return row;
            return new Change(row.changeId(),row.cardId(),row.tenantId(),row.revision(),row.operation(),
                fromVisibility==EndpointVisibility.CONCEALED?null:row.fromDeviceId(),fromVisibility,
                toVisibility==EndpointVisibility.CONCEALED?null:row.toDeviceId(),toVisibility,row.reason(),row.recordedAt());
        },args.toArray());
        if(rows.size()!=(int)Math.min(page.size(),Math.max(0,total-page.offset())))throw SimBindingProjection.unconfirmed();
        long pages=total/page.size()+(total%page.size()==0?0:1);return new HistoryPage(List.copyOf(rows),total,page.page(),page.size(),pages);
    }
}
