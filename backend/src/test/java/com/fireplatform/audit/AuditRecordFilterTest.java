package com.fireplatform.audit;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.ScopeFilter;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditRecordFilterTest {
    static final String ID="123e4567-e89b-12d3-a456-426614174000";
    static final OffsetDateTime T=OffsetDateTime.parse("2026-10-22T00:00:00.123456Z");
    static AuditRecordFilter parse(String a,String o,String actor,String target,String from,String to){return AuditRecordFilter.parse(a,o,actor,target,from,to);}
    void bad(Runnable body){assertThatThrownBy(body::run).isInstanceOfSatisfying(ApiError.class,e->{assertThat(e.status).isEqualTo(400);assertThat(e.code).isEqualTo("INVALID_REQUEST");});}
    void unconfirmed(org.assertj.core.api.ThrowableAssert.ThrowingCallable body){assertThatThrownBy(body).isInstanceOfSatisfying(ApiError.class,e->{assertThat(e.status).isEqualTo(503);assertThat(e.code).isEqualTo("AUDIT_READ_UNCONFIRMED");assertThat(e.getMessage()).doesNotContain("SECRET");});}
    @Test void lowerHyphenCodesAndEveryWorkerOutcomeAreValidButNotImaginedStates(){
        for(var d:AuditRecordCatalog.ACTIONS)assertThat(parse(d.code(),null,null,null,null,null).action()).isEqualTo(d.code());
        for(var d:AuditRecordCatalog.OUTCOMES)assertThat(parse(null,d.code(),null,null,null,null).outcome()).isEqualTo(d.code());
        for(String a:List.of("DEVICE-EDIT","customer-create","SIMULATE_NORMAL","NOTIFICATION_AUTOMATIC_QUEUED","SECRET_ACTION"))bad(()->parse(a,null,null,null,null,null));
        for(String o:List.of("PENDING","RUNNING","CANCELLED","EXPIRED","SIMULATION_RECONCILED","UNKNOWN","SECRET_RESULT"))bad(()->parse(null,o,null,null,null,null));
    }
    @Test void absentAndEmptyFiltersBecomeExactlyNullWithoutAnImplicitTimeWindow(){
        var a=parse(null,null,null,null,null,null);var scope=new ScopeFilter.Clause("a.tenant_id=?",List.of("tenant-a"));assertThat(a).isEqualTo(parse("","","","","",""));assertThat(a.sql(scope).where()).isEqualTo(" WHERE a.tenant_id=?");assertThat(a.sql(scope).bindings()).containsExactly("tenant-a");
    }
    @Test void classificationAndUuidFilterShareTheExactSqlProjection(){
        var scope=new ScopeFilter.Clause("a.tenant_id=?",List.of("tenant-a"));var plan=parse("UNCLASSIFIED","UNCLASSIFIED",ID,ID,T.toString(),T.plusNanos(1000).toString()).sql(scope);
        assertThat(plan.where()).contains(AuditRecordCatalog.ACTION_SQL,AuditRecordCatalog.OUTCOME_SQL,AuditRecordProjection.ACTOR_SQL,AuditRecordProjection.TARGET_SQL,"occurred_at>=?","occurred_at<?").doesNotContain("tenant_id IS NULL"," OR tenant_id","action='UNCLASSIFIED'");
        assertThat(plan.bindings()).containsExactly("tenant-a","UNCLASSIFIED","UNCLASSIFIED",ID,ID,T,T.plusNanos(1000));
        assertThat(AuditRecordProjection.COLUMNS).contains("CASE WHEN action IN (","ELSE 'UNCLASSIFIED' END","CHAR_LENGTH(actor_id)=36","REGEXP_LIKE","THEN LOWER(actor_id)","AS actor_ref","'NOT_RECORDED'","'WITHHELD'").doesNotContain("request_id","SELECT *");
    }
    @Test void referencesRequireFullLowercaseCanonicalFormNotUuidFromStringAbbreviation(){
        assertThat(AuditRecordProjection.uuid(ID)).isTrue();assertThat(AuditRecordProjection.uuid("00000000-0000-0000-0000-000000000000")).isTrue();
        for(String value:List.of("1-1-1-1-1",ID.toUpperCase(Locale.ROOT),ID+"\n"," "+ID,ID.replace("-",""),"bootstrap-admin","SECRET/path","％")){
            assertThat(AuditRecordProjection.uuid(value)).isFalse();bad(()->parse(null,null,value,null,null,null));bad(()->parse(null,null,null,value,null,null));
        }
    }
    @Test void temporalBoundariesNormalizeOffsetWithoutRounding(){
        var f=parse(null,null,null,null,"2026-10-22T08:00:00.123456+08:00",T.plusNanos(1000).toString());assertThat(f.occurredFrom()).isEqualTo(T);assertThat(f.occurredTo()).isEqualTo(T.plusNanos(1000));
        assertThat(parse(null,null,null,null,"2026-10-22T00:00:00.123456000Z",null).occurredFrom()).isEqualTo(T);
        assertThat(parse(null,null,null,null,null,T.toString()).occurredFrom()).isNull();
    }
    @Test void malformedSubmicroOrOutOfYearDomainAndReversedTimesAreNotSilentlyIgnored(){
        for(String value:List.of("2026-01-01T00:00:00","2026-10-22T00:00:00.123456001Z","0000-01-01T00:00:00Z","0001-01-01T00:00:00+01:00","9999-12-31T23:59:59-01:00","SECRET"))bad(()->parse(null,null,null,null,value,null));
        bad(()->parse(null,null,null,null,T.toString(),T.toString()));bad(()->parse(null,null,null,null,T.toString(),T.minusNanos(1000).toString()));
    }
    @Test void pageTokensAreExactUnsignedCanonicalIntegersAndCannotOverflow(){
        assertThat(AuditRecordController.number(null,1)).isEqualTo(1);assertThat(AuditRecordController.number("1000000",null)).isEqualTo(1_000_000);
        for(String value:List.of("","01","+1","-1"," 1","1 ","1.0","1e2","１","2147483648"))bad(()->AuditRecordController.number(value,null));
    }
    ResultSet row()throws Exception{var rs=mock(ResultSet.class);when(rs.getString("id")).thenReturn(ID);when(rs.getString("tenant_id")).thenReturn("tenant-a");when(rs.getString("tenant_name")).thenReturn("单位甲");when(rs.getString("action")).thenReturn("device-edit");when(rs.getString("outcome")).thenReturn("SUCCESS");when(rs.getString("actor_reference_state")).thenReturn("NOT_RECORDED");when(rs.getString("target_reference_state")).thenReturn("WITHHELD");when(rs.getObject("occurred_at",OffsetDateTime.class)).thenReturn(T);return rs;}
    @Test void rowShapeIncludesOwningUnitAndNullReferenceStatesRemainDistinct()throws Exception{var value=AuditRecordProjection.row(row());assertThat(value.tenantId()).isEqualTo("tenant-a");assertThat(value.tenantName()).isEqualTo("单位甲");assertThat(value.actorRef()).isNull();assertThat(value.actorReferenceState()).isEqualTo("NOT_RECORDED");assertThat(value.targetRef()).isNull();assertThat(value.targetReferenceState()).isEqualTo("WITHHELD");assertThat(value.getClass().getRecordComponents()).hasSize(10);}
    @Test void driverReturningUnprojectedCodeOrPrivateReferenceFailsValueFree()throws Exception{var rs=row();when(rs.getString("action")).thenReturn("SECRET_ACTION");unconfirmed(()->AuditRecordProjection.row(rs));var ref=row();when(ref.getString("actor_ref")).thenReturn("SECRET_ACTOR");unconfirmed(()->AuditRecordProjection.row(ref));}
    @Test void invalidRowIdTimeOrReferenceStateNeverGetsSkippedOrGuessed()throws Exception{
        var rs=row();when(rs.getString("id")).thenReturn("SECRET_ID");unconfirmed(()->AuditRecordProjection.row(rs));
        var a=row();when(a.getString("actor_reference_state")).thenReturn("SYSTEM");unconfirmed(()->AuditRecordProjection.row(a));
        var b=row();when(b.getString("actor_reference_state")).thenReturn("UUID");unconfirmed(()->AuditRecordProjection.row(b));
        var c=row();when(c.getObject("occurred_at",OffsetDateTime.class)).thenReturn(T.plusNanos(1));unconfirmed(()->AuditRecordProjection.row(c));
        unconfirmed(()->AuditRecordProjection.instant(null));unconfirmed(()->AuditRecordProjection.instant(OffsetDateTime.parse("0001-01-01T00:00:00+01:00")));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    AuditRecordQueries reader(org.springframework.jdbc.core.JdbcTemplate jdbc,ResultSet counts,long defaultSize,List<AuditRecordProjection.Row> rows)throws Exception{
        var sizes=mock(com.fireplatform.shared.PageSizePolicy.class);when(sizes.defaultPageSize()).thenReturn(defaultSize);var time=mock(com.fireplatform.configuration.DatabaseTime.class);when(time.now()).thenReturn(T);
        doAnswer(call->((org.springframework.jdbc.core.RowMapper)call.getArgument(1)).mapRow(counts,0)).when(jdbc).queryForObject(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class));
        doReturn(rows.stream().map(row -> new AuditRecordQueries.Entry(row, new AuditLogSearch.Metadata(null, "UNCLASSIFIED", "UNKNOWN"))).toList()).when(jdbc).query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class));
        var scopes=mock(com.fireplatform.shared.ScopeFilter.class);when(scopes.units(anyString(),anyString(),anyString(),any(),any())).thenReturn(new com.fireplatform.shared.ScopeFilter.Clause("1=1",List.of()));
        return new AuditRecordQueries(jdbc,sizes,time,mock(com.fireplatform.shared.TransactionRequirements.class),scopes);
    }
    AuditRecordProjection.Row safeRow(String id){return new AuditRecordProjection.Row(id,"tenant-a","单位甲",T,"CAPTURE","SUCCESS",null,"NOT_RECORDED",null,"WITHHELD");}
    @Test void malformedCentralDefaultIsUnconfirmedEvenWithExplicitSizeAndCannotOverflow()throws Exception{
        for(long value:new long[]{Long.MAX_VALUE,0,4,101})for(Integer size:Arrays.asList(null,1)){
            var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var r=reader(jdbc,mock(ResultSet.class),value,List.of());
            unconfirmed(()->r.query("actor",1,size,parse(null,null,null,null,null,null),com.fireplatform.shared.ScopeFilter.Selection.all(),AuditLogSearch.empty(),new AuditLogSearch.Access(false,false)));
            assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(i->i.getMethod().getName().equals("query"));
        }
    }
    @Test void unsafeTotalFailsBeforePageWithoutGuessingZeroOrDroppingRows()throws Exception{
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var counts=mock(ResultSet.class);when(counts.getLong(1)).thenReturn(AuditRecordProjection.MAX_SAFE_INTEGER+1);var r=reader(jdbc,counts,20,List.of());
        unconfirmed(()->r.query("actor",1,1,parse(null,null,null,null,null,null),com.fireplatform.shared.ScopeFilter.Selection.all(),AuditLogSearch.empty(),new AuditLogSearch.Access(false,false)));assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(i->i.getMethod().getName().equals("query"));
    }
    @Test void pageLengthUniqueIdAndExactOrderingMustAgreeWithSameSnapshotCount()throws Exception{
        String other="223e4567-e89b-12d3-a456-426614174000";
        for(var rows:List.of(List.of(safeRow(ID)),List.of(safeRow(ID),safeRow(ID)),List.of(safeRow(other),safeRow(ID)))){
            var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var counts=mock(ResultSet.class);when(counts.getLong(1)).thenReturn(2L);var r=reader(jdbc,counts,20,rows);unconfirmed(()->r.query("actor",1,2,parse(null,null,null,null,null,null),com.fireplatform.shared.ScopeFilter.Selection.all(),AuditLogSearch.empty(),new AuditLogSearch.Access(false,false)));
        }
    }
    @Test void countMustBeNonnegativeNonNullAndSafeForExactJsonInteger()throws Exception{
        for(long value:new long[]{-1,Long.MIN_VALUE,AuditRecordProjection.MAX_SAFE_INTEGER+1,Long.MAX_VALUE}){var rs=mock(ResultSet.class);when(rs.getLong(1)).thenReturn(value);unconfirmed(()->AuditRecordProjection.total(rs));}
        var rs=mock(ResultSet.class);when(rs.wasNull()).thenReturn(true);unconfirmed(()->AuditRecordProjection.total(rs));
        when(rs.wasNull()).thenReturn(false);when(rs.getLong(1)).thenReturn(AuditRecordProjection.MAX_SAFE_INTEGER);assertThat(AuditRecordProjection.total(rs)).isEqualTo(AuditRecordProjection.MAX_SAFE_INTEGER);
    }
}
