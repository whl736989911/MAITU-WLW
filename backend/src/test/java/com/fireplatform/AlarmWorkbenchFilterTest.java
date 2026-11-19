package com.fireplatform;

import com.fireplatform.alarm.AlarmWorkbenchFilter;
import com.fireplatform.shared.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AlarmWorkbenchFilterTest {
    @Test void canonicalConditionsHaveNoImplicitWindowAndNormalizeOffsetsAndBrowserWhitespace() {
        var all=AlarmWorkbenchFilter.parse(null,"","",null,null,null); assertThat(all.keyword()).isEmpty(); assertThat(all.physicalState()).isNull(); assertThat(all.responseState()).isNull(); assertThat(all.conclusion()).isNull(); assertThat(all.occurredFrom()).isNull(); assertThat(all.occurredTo()).isNull();
        var parsed=AlarmWorkbenchFilter.parse("\uFEFF\u00A0\u3000Alpha%_!\u3000 ","FIRE","HANDLED","TRUE_FIRE","2026-10-22T08:00:00.123456+08:00","2026-10-22T00:00:01.123456Z"); assertThat(parsed.keyword()).isEqualTo("Alpha%_!"); assertThat(parsed.occurredFrom().toString()).isEqualTo("2026-10-22T00:00:00.123456Z"); assertThat(parsed.occurredFrom().getOffset()).isEqualTo(ZoneOffset.UTC); assertThat(LiteralLike.contains(parsed.keyword())).isEqualTo("%Alpha!%!_!!%");
        assertThat(AlarmWorkbenchFilter.parse("",null,null,"UNREVIEWED","0001-01-01T00:00:00Z","9999-12-31T23:59:59.999999Z").conclusion()).isEqualTo("UNREVIEWED");
    }
    @Test void illegalEnumsOffsetsBoundsPrecisionAndEncodingNeverFallBackToAll() {
        for(String value:new String[]{"all","fire"," FIRE","UNKNOWN"}) assertThatThrownBy(()->AlarmWorkbenchFilter.parse("",value,null,null,null,null)).isInstanceOf(ApiError.class);
        for(String value:new String[]{"2026-10-22T08:00","2026-02-29T08:00:00Z","2026-10-22T08:00:00.1234567Z","0000-01-01T00:00:00Z","+10000-01-01T00:00:00Z","0001-01-01T00:00:00+08:00","9999-12-31T23:59:59-08:00","2026-10-22T00:00:00Z[UTC]"}) assertThatThrownBy(()->AlarmWorkbenchFilter.parse("",null,null,null,value,null)).isInstanceOf(ApiError.class);
        for(String end:new String[]{"2026-10-22T00:00:00Z","2026-10-21T23:59:59Z"}) assertThatThrownBy(()->AlarmWorkbenchFilter.parse("",null,null,null,"2026-10-22T00:00:00Z",end)).isInstanceOf(ApiError.class);
        for(String value:new String[]{"x".repeat(Limits.MAX_KEYWORD_LENGTH+1),"\u0000keyword","\uD800"}) assertThatThrownBy(()->AlarmWorkbenchFilter.parse(value,null,null,null,null,null)).isInstanceOf(ApiError.class);
    }
}
