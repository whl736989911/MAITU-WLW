package com.fireplatform.siminventory;

import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.siminventory.SimInventoryModels.*;

@Service
public class SimInventoryQueries {
    private final JdbcTemplate jdbc;private final PageSizePolicy sizes;private final TransactionRequirements transactions;
    public SimInventoryQueries(JdbcTemplate jdbc,PageSizePolicy sizes,TransactionRequirements transactions){this.jdbc=jdbc;this.sizes=sizes;this.transactions=transactions;}
    public void card(String tenant,String card,boolean lock){if(!SimInventoryProjection.uuid(card))throw SimInventoryProjection.missing();var rows=jdbc.queryForList("SELECT id FROM sim_cards WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),String.class,tenant,card);if(rows.isEmpty())throw SimInventoryProjection.missing();if(rows.size()!=1||!card.equals(rows.getFirst()))throw SimInventoryProjection.unconfirmed();}
    public State current(String tenant,String card,boolean lock){var rows=jdbc.query("SELECT "+SimInventoryProjection.STATE_COLUMNS+" FROM sim_card_inventory WHERE tenant_id=? AND card_id=?"+(lock?" FOR UPDATE":""),(rs,n)->SimInventoryProjection.state(rs,tenant,card),tenant,card);if(rows.size()>1)throw SimInventoryProjection.unconfirmed();return rows.isEmpty()?SimInventoryProjection.empty(tenant,card):rows.getFirst();}
    private Pagination page(int number,Integer requested){long configured=sizes.defaultPageSize();var policy=Limits.SETTINGS.get("default_page_size");if(configured<policy.min()||configured>policy.max())throw SimInventoryProjection.unconfirmed();return Pagination.of(number,requested,configured);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public State state(String tenant,String card){transactions.repeatableReadOnly();card(tenant,card,false);return current(tenant,card,false);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public StatePage list(String tenant,int number,Integer requested,String status){
        transactions.repeatableReadOnly();Pagination page=page(number,requested);SimInventoryInputs.status(status);String where=" WHERE tenant_id=?";List<Object> args=new ArrayList<>();args.add(tenant);if(status!=null){where+=" AND status=?";args.add(status);}
        var counts=jdbc.query("SELECT COUNT(*) FROM sim_card_inventory"+where,(rs,n)->SimInventoryProjection.number(rs.getObject(1)),args.toArray());if(counts.size()!=1)throw SimInventoryProjection.unconfirmed();long total=counts.getFirst();args.add(page.size());args.add(page.offset());
        var rows=jdbc.query("SELECT "+SimInventoryProjection.STATE_COLUMNS+" FROM sim_card_inventory"+where+" ORDER BY card_id ASC LIMIT ? OFFSET ?",(rs,n)->SimInventoryProjection.state(rs,tenant,rs.getString("card_id")),args.toArray());if(rows.size()!=(int)Math.min(page.size(),Math.max(0,total-page.offset())))throw SimInventoryProjection.unconfirmed();String previous=null;for(State row:rows){if((previous!=null&&previous.compareTo(row.cardId())>=0)||(status!=null&&!status.equals(row.status())))throw SimInventoryProjection.unconfirmed();previous=row.cardId();}return new StatePage(List.copyOf(rows),total,page.page(),page.size(),total/page.size()+(total%page.size()==0?0:1));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public HistoryPage history(String tenant,String card,int number,Integer requested){
        transactions.repeatableReadOnly();Pagination page=page(number,requested);card(tenant,card,false);State current=current(tenant,card,false);var counts=jdbc.query("SELECT COUNT(*) FROM sim_card_inventory_changes WHERE tenant_id=? AND card_id=?",(rs,n)->SimInventoryProjection.number(rs.getObject(1)),tenant,card);if(counts.size()!=1)throw SimInventoryProjection.unconfirmed();long total=counts.getFirst();if(total!=current.revision())throw SimInventoryProjection.unconfirmed();
        var rows=jdbc.query("SELECT "+SimInventoryProjection.CHANGE_COLUMNS+" FROM sim_card_inventory_changes WHERE tenant_id=? AND card_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",(rs,n)->SimInventoryProjection.change(rs,current),tenant,card,page.size(),page.offset());if(rows.size()!=(int)Math.min(page.size(),Math.max(0,total-page.offset())))throw SimInventoryProjection.unconfirmed();
        Set<String> ids=new HashSet<>();Change previous=null;for(int i=0;i<rows.size();i++){Change row=rows.get(i);if(!ids.add(row.changeId())||row.revision()!=total-page.offset()-i||(previous!=null&&!previous.fromStatus().equals(row.toStatus())))throw SimInventoryProjection.unconfirmed();previous=row;}return new HistoryPage(List.copyOf(rows),total,page.page(),page.size(),total/page.size()+(total%page.size()==0?0:1));
    }
}
