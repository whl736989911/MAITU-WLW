package com.fireplatform.simcard;

import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.simcard.SimCardModels.*;

@Service
public class SimCardQueries {
    private final JdbcTemplate jdbc;private final PageSizePolicy sizes;private final TransactionRequirements transactions;
    public SimCardQueries(JdbcTemplate jdbc,PageSizePolicy sizes,TransactionRequirements transactions){this.jdbc=jdbc;this.sizes=sizes;this.transactions=transactions;}
    private Pagination page(int number,Integer requested){transactions.repeatableReadOnly();long value=sizes.defaultPageSize();var definition=Limits.SETTINGS.get("default_page_size");if(definition==null||value<definition.min()||value>definition.max())throw SimCardProjection.unconfirmed();return Pagination.of(number,requested,value);}
    public Card current(String tenant,String id){
        if(!SimCardProjection.uuid(id))throw SimCardProjection.missing();
        var rows=jdbc.query("SELECT "+SimCardProjection.CARD_COLUMNS+" FROM sim_cards c WHERE c.tenant_id=? AND c.id=?",(rs,n)->SimCardProjection.card(rs,tenant),tenant,id);
        if(rows.isEmpty())throw SimCardProjection.missing();if(rows.size()!=1)throw SimCardProjection.unconfirmed();return rows.getFirst();
    }
    private long total(String sql,Object[] args){var rows=jdbc.query(sql,(rs,n)->SimCardProjection.total(rs),args);if(rows.size()!=1)throw SimCardProjection.unconfirmed();return rows.getFirst();}
    private static Object[] arguments(List<Object> args,Pagination page){var values=new ArrayList<>(args);values.add(page.size());values.add(page.offset());return values.toArray();}
    private static void window(int length,long total,Pagination page){if(length!=(int)Math.min(page.size(),Math.max(0,total-page.offset())))throw SimCardProjection.unconfirmed();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CardPage cards(String tenant,int number,Integer requested,SimCardFilter filter){
        Pagination page=page(number,requested);var sql=filter.cards(tenant);long total=total("SELECT COUNT(*) FROM sim_cards c"+sql.where(),sql.arguments().toArray());
        var rows=jdbc.query("SELECT "+SimCardProjection.CARD_COLUMNS+" FROM sim_cards c"+sql.where()+" ORDER BY c.id ASC LIMIT ? OFFSET ?",(rs,n)->SimCardProjection.card(rs,tenant),arguments(sql.arguments(),page));
        window(rows.size(),total,page);String previous=null;for(Card row:rows){if(previous!=null&&previous.compareTo(row.id())>=0)throw SimCardProjection.unconfirmed();previous=row.id();}return new CardPage(List.copyOf(rows),total,page.page(),page.size());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Card detail(String tenant,String id){transactions.repeatableReadOnly();return current(tenant,id);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ChangesPage changes(String tenant,String id,int number,Integer requested){
        Pagination page=page(number,requested);Card current=current(tenant,id);long total=total("SELECT COUNT(*) FROM sim_card_changes WHERE tenant_id=? AND card_id=?",new Object[]{tenant,id});
        var rows=jdbc.query("SELECT "+SimCardProjection.HISTORY_COLUMNS+" FROM sim_card_changes h WHERE h.tenant_id=? AND h.card_id=? ORDER BY h.revision DESC LIMIT ? OFFSET ?",(rs,n)->SimCardProjection.history(rs,current),tenant,id,page.size(),page.offset());
        window(rows.size(),total,page);Set<String> ids=new HashSet<>();long previous=Long.MAX_VALUE;for(History row:rows){if(!ids.add(row.changeId())||row.revision()>=previous)throw SimCardProjection.unconfirmed();previous=row.revision();}
        return new ChangesPage(current,List.copyOf(rows),total,page.page(),page.size());
    }
}
