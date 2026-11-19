package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Enforces finite validity at authentication and authorization boundaries. */
@Service
public class IdentityExpiryService {
    private final JdbcTemplate jdbc; private final Clock clock;
    public IdentityExpiryService(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public void requireCurrent(String userId){
        var rows=jdbc.query("SELECT m.valid_until,m.long_term FROM identity_user_metadata m WHERE m.user_id=?",(r,n)->new Object[]{r.getObject("valid_until",OffsetDateTime.class),r.getBoolean("long_term")},userId);
        if(rows.isEmpty())return;
        OffsetDateTime until=(OffsetDateTime)rows.getFirst()[0]; boolean longTerm=(boolean)rows.getFirst()[1];
        if(!longTerm&&(until==null||!until.toInstant().isAfter(clock.instant())))throw new ApiError(403,"ACCOUNT_EXPIRED","账号有效期已结束，请联系管理员");
    }
}
