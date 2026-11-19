package com.fireplatform;

import com.fireplatform.identity.IdentityExpiryService;
import com.fireplatform.shared.ApiError;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityExpiryServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-06T12:00:00Z");
    private IdentityExpiryService service(OffsetDateTime deadline,boolean longTerm,boolean hasMetadata)throws Exception {
        var jdbc=mock(JdbcTemplate.class); ResultSet row=mock(ResultSet.class);
        if(hasMetadata){when(row.getObject("valid_until",OffsetDateTime.class)).thenReturn(deadline);when(row.getBoolean("long_term")).thenReturn(longTerm);}
        doAnswer(invocation->{if(!hasMetadata)return List.of(); @SuppressWarnings("unchecked") RowMapper<Object[]> mapper=(RowMapper<Object[]>)invocation.getArgument(1);return java.util.Collections.singletonList(mapper.mapRow(row,0));}).when(jdbc).query(anyString(),any(RowMapper.class),eq("account-1"));
        return new IdentityExpiryService(jdbc,Clock.fixed(NOW,ZoneOffset.UTC));
    }
    @Test void legacyAndLongTermProfilesRemainValid()throws Exception {
        service(null,true,false).requireCurrent("account-1");
        service(null,true,true).requireCurrent("account-1");
        service(OffsetDateTime.ofInstant(NOW.plusSeconds(1),ZoneOffset.UTC),false,true).requireCurrent("account-1");
    }
    @Test void deadlineIsExclusiveAndMissingFiniteDeadlineFailsClosed()throws Exception {
        for(OffsetDateTime deadline:Arrays.asList(OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC),OffsetDateTime.ofInstant(NOW.minusSeconds(1),ZoneOffset.UTC),null)){
            var subject=service(deadline,false,true);
            assertThatThrownBy(()->subject.requireCurrent("account-1")).isInstanceOfSatisfying(ApiError.class,error->assertThat(error.code).isEqualTo("ACCOUNT_EXPIRED"));
        }
    }
}
