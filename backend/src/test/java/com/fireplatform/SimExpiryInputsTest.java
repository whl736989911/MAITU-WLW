package com.fireplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import com.fireplatform.simexpiry.SimExpiryInputs;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SimExpiryInputsTest {
    private final SimExpiryInputs inputs=new SimExpiryInputs(new ObjectMapper());
    private byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private String config(String enabled,String zone,String lead,String interval,String count){return "{\"enabled\":"+enabled+",\"timezone\":"+zone+",\"lead_days\":"+lead+",\"repeat_interval_days\":"+interval+",\"repeat_count\":"+count+",\"expected_revision\":0,\"reason\":\"\"}";}
    @Test void keepsVirtualDisabledConfigurationAndAllowsOnlyExplicitCompleteSettings(){var empty=inputs.config(bytes(config("false","null","null","null","null")));assertThat(empty.enabled()).isFalse();assertThat(empty.timezone()).isNull();var configured=inputs.config(bytes(config("true","\"Pacific/Kiritimati\"","2147483647","1","2147483647")));assertThat(configured.leadDays()).isEqualTo(Integer.MAX_VALUE);assertThat(configured.repeatCount()).isEqualTo(Integer.MAX_VALUE);assertThatThrownBy(()->inputs.config(bytes(config("true","null","null","null","null")))).isInstanceOf(ApiError.class);assertThatThrownBy(()->inputs.config(bytes(config("false","\"UTC\"","0","0","0")))).isInstanceOf(ApiError.class);}
    @Test void rejectsNoncanonicalJsonIntegersDuplicateTrailingUnknownAndMalformedUnicode(){for(String bad: new String[]{config("true","\"UTC\"","-0","1","0"),config("true","\"UTC\"","1.0","1","0"),config("true","\"UTC\"","1e0","1","0"),config("true","\"UTC\"","2147483648","1","0"),config("true","\"UTC\"","0","0","0").replace("\"reason\":\"\"","\"reason\":\"\",\"reason\":\"again\""),config("true","\"UTC\"","0","1","0")+" {}",config("true","\"UTC\"","0","0","0").replace("\"reason\":\"\"","\"unknown\":1,\"reason\":\"\"")})assertThatThrownBy(()->inputs.config(bytes(bad))).isInstanceOf(ApiError.class);}
    @Test void disabledMayPreserveCompleteConfigurationButNeverUsesBusinessDefaults(){var result=inputs.config(bytes(config("false","\"America/Adak\"","0","3","0")));assertThat(result.enabled()).isFalse();assertThat(result.timezone()).isEqualTo("America/Adak");assertThat(result.repeatIntervalDays()).isEqualTo(3);}
    @Test void reasonsPreserveLegalCrLfAndTabWithoutAllowingOtherControls(){
        String legal=" reason\r\n\t ";String escaped=legal.replace("\\","\\\\").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t");
        var configured=inputs.config(bytes(config("false","null","null","null","null").replace("\"reason\":\"\"","\"reason\":\""+escaped+"\"")));assertThat(configured.reason()).isEqualTo(legal);
        String handle="{\"reason\":\""+escaped+"\",\"expected_revision\":1}";assertThat(inputs.handle(bytes(handle)).reason()).isEqualTo(legal);
        for(String bad:List.of("a\u0000b","a\u000bb","a\u007fb")){String encoded=bad.replace("\\","\\\\").replace("\u0000","\\u0000").replace("\u000b","\\u000b").replace("\u007f","\\u007f");assertThatThrownBy(()->inputs.handle(bytes("{\"reason\":\""+encoded+"\",\"expected_revision\":1}"))).isInstanceOf(ApiError.class);}
    }
}
