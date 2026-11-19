package com.fireplatform.legacyingest.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FormTtlRecoveryTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void explicitAlarmRecoveryIsCanonicalButFaultRecoveryIsNeverFireRecovery()throws Exception {
        var normalizer=new FormTtlNormalizer(json);
        var alarm=normalizer.parse(json.readTree("{\"deviceId\":\"fixture\",\"messageType\":\"alarmRecovery\",\"params\":{}}"),"formttl",null);
        var fault=normalizer.parse(json.readTree("{\"deviceId\":\"fixture\",\"messageType\":\"故障恢复\",\"params\":{\"description\":\"normal\"}}"),"formttl",null);
        assertThat(alarm.events().getFirst().kind()).isEqualTo("RECOVERY");
        assertThat(fault.events().getFirst().kind()).isEqualTo("FAULT");
    }
    @Test void vagueNormalAndUnrecognizedFireRecoveryMethodsCannotRaiseFire()throws Exception {
        var normalizer=new FormTtlNormalizer(json);
        var normal=normalizer.parse(json.readTree("{\"deviceId\":\"fixture\",\"messageType\":\"normal\",\"params\":{}}"),"formttl",null);
        assertThat(normal.events().getFirst().kind()).isEqualTo("OTHER");
        assertThat(NormalizerSupport.eventKind("thing.event.fireRecovery")).isNotEqualTo("ALARM");
    }
}
