package com.fireplatform;

import com.fireplatform.simcard.SimCardConflicts;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Real H2 driver regressions; reports structural index metadata, never row values. */
class SimCardH2NullOrderingTest {
    private static final String DRIVER="org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException";
    private static final String VIOLATION_PREFIX = "Unique index or primary key violation: \"";
    private static final Pattern INDEX = Pattern.compile(
            "\\A" + VIOLATION_PREFIX + "PUBLIC\\.UQ_SIM_CARDS_TENANT_ICCID INDEX PUBLIC\\.(UQ_SIM_CARDS_TENANT_ICCID_INDEX_[0-9A-F]+) "
                    + "ON PUBLIC\\.SIM_CARDS\\((TENANT_ID NULLS (?:FIRST|LAST), ICCID NULLS (?:FIRST|LAST))\\)",
            Pattern.CASE_INSENSITIVE);
    @Test void realLowNullOrderingClassifiesOnlyTheNamedTenantIccidConstraint(){exercise("LOW","FIRST");}
    @Test void realHighNullOrderingClassifiesOnlyTheNamedTenantIccidConstraint(){exercise("HIGH","LAST");}

    private static DataAccessException failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){
        Throwable caught=catchThrowable(action);
        assertThat(caught instanceof DataAccessException).as("the controlled SQL must fail through the actual JDBC driver").isTrue();
        return (DataAccessException)caught;
    }
    private static SQLException driver(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=failure;cause!=null&&seen.size()<32&&seen.add(cause);cause=cause.getCause())if(cause instanceof SQLException sql&&DRIVER.equals(sql.getClass().getName()))return sql;
        throw new AssertionError("the actual H2 integrity driver exception is required; payload omitted");
    }
    private static void notIccid(Throwable failure,String expectedState){
        assertThat(driver(failure).getSQLState()).as("actual driver SQLSTATE, without row payload").isEqualTo(expectedState);
        assertThat(SimCardConflicts.iccid(failure)).as("unrelated constraint must remain a storage failure").isFalse();
    }
    private void exercise(String ordering,String nullPosition){
        String url="jdbc:h2:mem:sim-null-order-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING="+ordering+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        var fixtures=new SimCardMigrationTest();fixtures.tenant(jdbc);
        String id=fixtures.card(jdbc,"a","000Case");
        DataAccessException duplicate=failure(()->fixtures.card(jdbc,"a","000Case"));
        SQLException actual=driver(duplicate);
        assertThat(actual.getSQLState()).isEqualTo("23505");assertThat(actual.getErrorCode()).isEqualTo(23505);
        var index=INDEX.matcher(actual.getMessage());
        assertThat(index.find()).as("driver must name the exact tenant/ICCID index and its declared columns; row payload omitted").isTrue();
        String declaration=index.group(2).toUpperCase(Locale.ROOT);
        System.out.printf(Locale.ROOT,"SIM_H2_ICCID_DRIVER_METADATA ordering=%s state=%s error=%d driver=%s index=%s columns=%s%n",ordering,actual.getSQLState(),actual.getErrorCode(),actual.getClass().getName(),index.group(1).toUpperCase(Locale.ROOT),declaration);
        assertThat(declaration).isEqualTo("TENANT_ID NULLS "+nullPosition+", ICCID NULLS "+nullPosition);

        // Case-exact and tenant-local uniqueness are unchanged under either NULL ordering.
        fixtures.card(jdbc,"a","000case");fixtures.card(jdbc,"b","000Case");
        notIccid(failure(()->fixtures.card(jdbc,id,"a","Other")),"23505");
        notIccid(failure(()->fixtures.card(jdbc,"missing-tenant","Other")),"23506");

        // Same columns with a lookalike constraint name cannot impersonate the one owner.
        jdbc.execute("ALTER TABLE sim_cards DROP CONSTRAINT uq_sim_cards_tenant_iccid");
        jdbc.execute("ALTER TABLE sim_cards ADD CONSTRAINT uq_sim_cards_tenant_iccid_lookalike UNIQUE(tenant_id,iccid)");
        notIccid(failure(()->fixtures.card(jdbc,"a","000Case")),"23505");
        jdbc.execute("ALTER TABLE sim_cards DROP CONSTRAINT uq_sim_cards_tenant_iccid_lookalike");

        // Even the exact name with different declared columns is not this business conflict.
        jdbc.update("UPDATE sim_cards SET carrier_name=id");
        jdbc.execute("ALTER TABLE sim_cards ADD CONSTRAINT uq_sim_cards_tenant_iccid UNIQUE(tenant_id,carrier_name)");
        fixtures.card(jdbc,"a","ProbeFirst");
        notIccid(failure(()->fixtures.card(jdbc,"a","ProbeSecond")),"23505");

        String structuralOnly = VIOLATION_PREFIX + "PUBLIC.UQ_SIM_CARDS_TENANT_ICCID INDEX PUBLIC.UQ_SIM_CARDS_TENANT_ICCID_INDEX_A"
                + " ON PUBLIC.SIM_CARDS(TENANT_ID NULLS " + nullPosition + ", ICCID NULLS " + nullPosition + ")";
        var arbitrary=new SQLException(structuralOnly,"23505");
        assertThat(SimCardConflicts.iccid(arbitrary)).as("unstructured SQLException cannot impersonate driver metadata").isFalse();
        assertThat(SimCardConflicts.iccid(new DataIntegrityViolationException("synthetic wrapper",arbitrary))).isFalse();
        // Keep these positive assertions last, so HIGH's first RED also exercises every negative boundary.
        assertThat(SimCardConflicts.iccid(duplicate)).as("the actual named H2 tenant/ICCID constraint must map under "+ordering).isTrue();
        assertThat(SimCardConflicts.iccid(actual)).isTrue();
        assertThat(SimCardConflicts.iccid(new DataIntegrityViolationException("synthetic wrapper",duplicate))).isTrue();
    }
}
