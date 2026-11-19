package com.fireplatform;

import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.ingestion.*;
import com.fireplatform.shared.ApiError;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class IngestionPolicyTest {
    @Test void isoTimesAreSourceClaimsWithPortableUtcBoundsNotArtificialFutureWindows() {
        for(String value:new String[]{"0001-01-01T00:00:00Z","9999-12-31T23:59:59Z","2999-01-01T00:00:00+08:00","2026-10-23T03:00:00+08:00"}) { var result=IngestionLimits.reported(value); assertThat(result.quality()).isEqualTo("REPORTED"); assertThat(result.value().getOffset()).isEqualTo(java.time.ZoneOffset.UTC); }
        for(String value:new String[]{"0001-01-01T00:00:00+01:00","9999-12-31T23:59:59-01:00","+10000-01-01T00:00:00Z","0000-12-31T00:00:00Z","2026-10-23T03:00:00","","not-iso"}) { var result=IngestionLimits.reported(value); assertThat(result.quality()).isEqualTo("INVALID"); assertThat(result.value()).isNull(); }
        assertThat(IngestionLimits.reported(null).quality()).isEqualTo("MISSING"); assertThat(IngestionLimits.reported(null).value()).isNull();
    }
    @Test void rawUtf8PreservesWhitespaceUnicodeAndNulButRejectsUnpairedSurrogates() {
        String original=" \n中文 🚒\u0000 "; byte[] bytes=IngestionLimits.utf8(original); assertThat(bytes).isEqualTo(original.getBytes(StandardCharsets.UTF_8)); assertThat(IngestionLimits.decode(bytes)).isEqualTo(original);
        assertThatThrownBy(()->IngestionLimits.utf8("\ud800")).isInstanceOf(ApiError.class); assertThatThrownBy(()->IngestionLimits.text("bad\u0000id",128)).isInstanceOf(ApiError.class);
    }
    private RuntimeConfig config(String roles,String flag) { var env=new MockEnvironment().withProperty("spring.datasource.url","jdbc:h2:mem:policy").withProperty("app.roles",roles); env.setActiveProfiles("test"); if(flag!=null) env.setProperty("app.generic-ingest-enabled",flag); return new RuntimeConfig(env); }
    @Test void captureDefaultsOffAndOnlyApiAllCanExposeCapabilityEvenWhenExplicitlyEnabled() {
        assertThat(config("all",null).genericIngestEnabled()).isFalse(); assertThat(config("all","true").genericIngestEnabled()).isTrue(); assertThat(config("api","true").genericIngestEnabled()).isTrue();
        for(String role:new String[]{"ingest","worker","ingest,worker"}) { var runtime=config(role,"true"); assertThat(runtime.genericIngestEnabled()).isFalse(); assertThatThrownBy(()->new SourceAuthentication(null,null,runtime,null).initial("source","Bearer anything")).isInstanceOfSatisfying(ApiError.class,e->{assertThat(e.status).isEqualTo(503); assertThat(e.code).isEqualTo("GENERIC_INGEST_DISABLED");}); }
    }
}
