package com.fireplatform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fireplatform.transport.AliyunNotificationClient;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationDeliveryReceiptEvidenceTest {
    private static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Shanghai");

    @Test void smsRequiresBothSuccessfulSendStatusAndDeliveredCarrierCode() {
        assertThat(NotificationDeliveryReceiptEvidence.sms(1, "DELIVERED")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("WAITING", "SEND_WAITING"));
        assertThat(NotificationDeliveryReceiptEvidence.sms(2, "DELIVERED")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("FAILED", "SEND_FAILED"));
        assertThat(NotificationDeliveryReceiptEvidence.sms(3, "DELIVERED")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("DELIVERED", null));
        assertThat(NotificationDeliveryReceiptEvidence.sms(3, " ")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("UNCONFIRMED", "MISSING_ERR_CODE"));
        assertThat(NotificationDeliveryReceiptEvidence.sms(3, "MK:0001")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("FAILED", "CARRIER_REJECTED"));
        assertThat(NotificationDeliveryReceiptEvidence.sms(9, null).state()).isEqualTo("UNCONFIRMED");
    }

    @Test void voiceSeparatesFullPartialNotConnectedAndUnknownEvidence() {
        assertThat(NotificationDeliveryReceiptEvidence.voice("200000", 12, "2026-11-12 10:00:00")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("HEARD_FULL", null));
        assertThat(NotificationDeliveryReceiptEvidence.voice("200000", 0, "2026-11-12 10:00:00")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("UNCONFIRMED", "INCONSISTENT_EVIDENCE"));
        assertThat(NotificationDeliveryReceiptEvidence.voice("200000", 12, "")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("UNCONFIRMED", "INCONSISTENT_EVIDENCE"));
        assertThat(NotificationDeliveryReceiptEvidence.voice("200001", 4, "2026-11-12 10:00:00")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("CONNECTED_PARTIAL", null));
        assertThat(NotificationDeliveryReceiptEvidence.voice("200002", 0, "")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("NOT_CONNECTED", null));
        assertThat(NotificationDeliveryReceiptEvidence.voice("200002", 3, "")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("UNCONFIRMED", "INCONSISTENT_EVIDENCE"));
        assertThat(NotificationDeliveryReceiptEvidence.voice("999999", 0, "")).isEqualTo(new NotificationDeliveryReceiptEvidence.Mapped("UNCONFIRMED", "UNKNOWN_PROVIDER_STATUS"));
    }

    @Test void maskedDestinationMustNotContradictTheSnapshottedPhone() {
        assertThat(NotificationDeliveryReceiptEvidence.phoneCompatible("+8613800001234", "1390000****")).isFalse();
        assertThat(NotificationDeliveryReceiptEvidence.phoneCompatible("+8613900001234", "1390000****")).isTrue();
        assertThat(NotificationDeliveryReceiptEvidence.phoneCompatible("+8613800001234", "138****1234")).isTrue();
        assertThat(NotificationDeliveryReceiptEvidence.phoneCompatible("+8613800001234", "139****1234")).isFalse();
        assertThat(NotificationDeliveryReceiptEvidence.phoneCompatible("+8613800001234", null)).isTrue();
        assertThat(NotificationDeliveryReceiptEvidence.phoneCompatible("010-12345678", "010****5678")).isTrue();
    }

    @Test void stateOnlyAdvancesAndEqualTerminalStatesNeverReplaceEachOther() {
        assertThat(NotificationDeliveryReceiptEvidence.stronger("SMS", null, "WAITING")).isEqualTo("WAITING");
        assertThat(NotificationDeliveryReceiptEvidence.stronger("SMS", "WAITING", "DELIVERED")).isEqualTo("DELIVERED");
        assertThat(NotificationDeliveryReceiptEvidence.stronger("SMS", "DELIVERED", "FAILED")).isEqualTo("DELIVERED");
        assertThat(NotificationDeliveryReceiptEvidence.stronger("SMS", "UNCONFIRMED", "DELIVERED")).isEqualTo("DELIVERED");
        assertThat(NotificationDeliveryReceiptEvidence.stronger("VOICE", "CONNECTED_PARTIAL", "HEARD_FULL")).isEqualTo("HEARD_FULL");
        assertThat(NotificationDeliveryReceiptEvidence.stronger("VOICE", "HEARD_FULL", "NOT_CONNECTED")).isEqualTo("HEARD_FULL");
        assertThat(NotificationDeliveryReceiptEvidence.stronger("VOICE", "NOT_CONNECTED", "UNCONFIRMED")).isEqualTo("NOT_CONNECTED");
    }

    @Test void smsMappingCorrelatesBizIdOutIdAndMaskedDestination() {
        var row = row("SMS", "biz-1");
        var delivered = new AliyunNotificationClient.SmsQuery(true, "OK", "OK", "req-1", 1,
                List.of(new AliyunNotificationClient.SmsDetail("biz-1", row.outId(), "139****1234", 3, "DELIVERED", "SMS_1", "2026-11-12 10:00:00", "2026-11-12 10:00:05")));
        var mapped = NotificationDeliveryReceiptService.mapSms(delivered, row, "13900001234");
        assertThat(mapped.state()).isEqualTo("DELIVERED");
        assertThat(mapped.providerStatus()).isEqualTo("3");
        assertThat(mapped.providerCode()).isEqualTo("DELIVERED");
        assertThat(mapped.providerBizId()).isEqualTo("biz-1");

        var mismatch = NotificationDeliveryReceiptService.mapSms(delivered, row, "13800001234");
        assertThat(mismatch.state()).isEqualTo("UNCONFIRMED");
        assertThat(mismatch.reason()).isEqualTo("DESTINATION_MISMATCH");

        var outIdMismatch = NotificationDeliveryReceiptService.mapSms(new AliyunNotificationClient.SmsQuery(true, "OK", "OK", "req-2", 1,
                List.of(new AliyunNotificationClient.SmsDetail("biz-1", "other-out-id", "139****1234", 3, "DELIVERED", "SMS_1", "2026-11-12 10:00:00", "2026-11-12 10:00:05"))), row, "13900001234");
        assertThat(outIdMismatch.state()).isEqualTo("UNCONFIRMED");
        assertThat(outIdMismatch.reason()).isEqualTo("OUT_ID_MISMATCH");

        var duplicate = NotificationDeliveryReceiptService.mapSms(new AliyunNotificationClient.SmsQuery(true, "OK", "OK", "req-3", 2,
                List.of(new AliyunNotificationClient.SmsDetail("biz-1", row.outId(), "139****1234", 3, "DELIVERED", "SMS_1", null, null),
                        new AliyunNotificationClient.SmsDetail("biz-1", row.outId(), "139****1234", 3, "DELIVERED", "SMS_1", null, null))), row, "13900001234");
        assertThat(duplicate.reason()).isEqualTo("MULTIPLE_MATCHES");

        var absent = NotificationDeliveryReceiptService.mapSms(new AliyunNotificationClient.SmsQuery(true, "OK", "OK", "req-4", 0, List.of()), row, "13900001234");
        assertThat(absent.state()).isEqualTo("WAITING");
        assertThat(absent.reason()).isEqualTo("RECEIPT_NOT_FOUND");
    }

    @Test void voiceMappingCorrelatesCallIdAndCallEvidence() {
        var row = row("VOICE", "call-1");
        var heard = new AliyunNotificationClient.VoiceQuery(true, "OK", "OK", "req-1",
                new AliyunNotificationClient.VoiceDetail("call-1", "200000", "用户听完语音", "2026-11-12 10:00:00", "2026-11-12 10:00:12", "139****1234", null, "2026-11-12 09:59:00", 12));
        var mapped = NotificationDeliveryReceiptService.mapVoice(heard, row, "13900001234");
        assertThat(mapped.state()).isEqualTo("HEARD_FULL");
        assertThat(mapped.durationSeconds()).isEqualTo(12);

        var mismatch = NotificationDeliveryReceiptService.mapVoice(heard, row, "13800001234");
        assertThat(mismatch.reason()).isEqualTo("DESTINATION_MISMATCH");

        var wrongCall = NotificationDeliveryReceiptService.mapVoice(new AliyunNotificationClient.VoiceQuery(true, "OK", "OK", "req-2",
                new AliyunNotificationClient.VoiceDetail("call-2", "200000", "用户听完语音", "2026-11-12 10:00:00", "2026-11-12 10:00:12", "139****1234", null, null, 12)), row, "13900001234");
        assertThat(wrongCall.reason()).isEqualTo("CORRELATION_MISMATCH");

        var pending = NotificationDeliveryReceiptService.mapVoice(new AliyunNotificationClient.VoiceQuery(true, "OK", "OK", "req-3", null), row, "13900001234");
        assertThat(pending.state()).isEqualTo("WAITING");
        assertThat(pending.reason()).isEqualTo("NOT_SYNCHRONIZED");
    }

    @Test void eligibilityRequiresHistoricalAccountAndWindowProof() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        Clock clock = Clock.fixed(today.atStartOfDay(PROVIDER_ZONE).plusHours(12).toInstant(), PROVIDER_ZONE);
        var client = new AliyunNotificationClient("ak-current", "secret", "", "", "", "", "http://127.0.0.1:1/sms", "http://127.0.0.1:1/voice");
        var queries = new NotificationDeliveryReceiptQueries(null, null, null, client, null, clock);
        String bound = client.accountFingerprint();
        assertThat(queries.eligibility("SMS", "SENT", "biz-1", "cipher", "nonce", "mac", today, bound).queryable()).isTrue();
        assertThat(queries.eligibility("SMS", "SENT", "biz-1", "cipher", "nonce", "mac", today.minusDays(31), bound)).isEqualTo(new NotificationDeliveryReceiptQueries.Eligibility(false, "QUERY_WINDOW_EXHAUSTED"));
        assertThat(queries.eligibility("SMS", "SENT", "biz-1", "cipher", "nonce", "mac", today, "other-account")).isEqualTo(new NotificationDeliveryReceiptQueries.Eligibility(false, "ACCOUNT_MISMATCH"));
        assertThat(queries.eligibility("VOICE", "SENT", "call-1", "cipher", "nonce", "mac", today.minusDays(1), bound).queryable()).isTrue();
        assertThat(queries.eligibility("VOICE", "SENT", "call-1", "cipher", "nonce", "mac", today.minusMonths(6), bound).queryable()).isTrue();
        assertThat(queries.eligibility("VOICE", "SENT", "call-1", "cipher", "nonce", "mac", today.minusMonths(6).minusDays(1), bound)).isEqualTo(new NotificationDeliveryReceiptQueries.Eligibility(false, "QUERY_WINDOW_EXHAUSTED"));
        assertThat(queries.eligibility("VOICE", "SENT", "call-1", "cipher", "nonce", "mac", today.plusDays(1), bound)).isEqualTo(new NotificationDeliveryReceiptQueries.Eligibility(false, "SEND_DATE_INVALID"));
        assertThat(queries.eligibility("SMS", "FAILED", "biz-1", "cipher", "nonce", "mac", today, bound)).isEqualTo(new NotificationDeliveryReceiptQueries.Eligibility(false, "ATTEMPT_NOT_ACCEPTED"));
        assertThat(queries.eligibility("SMS", "SENT", "biz-1", null, null, null, today, bound)).isEqualTo(new NotificationDeliveryReceiptQueries.Eligibility(false, "DESTINATION_SNAPSHOT_UNAVAILABLE"));
        assertThat(queries.eligibility("SMS", "SENT", "biz-1", "cipher", "nonce", "mac", today, null)).isEqualTo(new NotificationDeliveryReceiptQueries.Eligibility(false, "ACCOUNT_SNAPSHOT_UNAVAILABLE"));
    }

    private static NotificationDeliveryReceiptCommands.Row row(String channel, String providerId) {
        String outId = NotificationProviderAttemptService.outIdFor("0f0e0d0c-0b0a-4908-8706-050403020100");
        return new NotificationDeliveryReceiptCommands.Row("attempt-1", "tenant-a", "device-1", channel, "SENT", providerId, "cipher", "nonce", "mac",
                LocalDate.of(2026, 11, 12), null, outId);
    }
}
