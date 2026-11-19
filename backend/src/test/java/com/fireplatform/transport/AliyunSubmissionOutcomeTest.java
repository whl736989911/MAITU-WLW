package com.fireplatform.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings({"unchecked", "rawtypes"})
class AliyunSubmissionOutcomeTest {
    @ParameterizedTest
    @ValueSource(strings = {"SMS", "VOICE"})
    void lostResponseAfterSubmissionIsUnknown(String channel) throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new SocketTimeoutException("response lost after submission"));

        var result = send(client(http), channel);

        assertThat(result.outcome()).isEqualTo(AliyunNotificationClient.Result.Outcome.UNKNOWN);
        assertThat(result.success()).isFalse();
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @ParameterizedTest
    @MethodSource("responses")
    void providerResponseMustProveAcceptanceOrRejection(int status, String body,
            AliyunNotificationClient.Result.Outcome expected) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        assertThat(send(client(http), "SMS").outcome()).isEqualTo(expected);
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private static Stream<Arguments> responses() {
        var unknown = AliyunNotificationClient.Result.Outcome.UNKNOWN;
        return Stream.of(
            Arguments.of(200, "{not-json", unknown),
            Arguments.of(200, "", unknown),
            Arguments.of(200, "{}", unknown),
            Arguments.of(200, "{\"Code\":null}", unknown),
            Arguments.of(200, "{\"Code\":200}", unknown),
            Arguments.of(503, "{\"Code\":\"ServiceUnavailable\"}", unknown),
            Arguments.of(502, "{\"Code\":\"OK\"}", unknown),
            Arguments.of(400, "{\"Code\":\"isv.INVALID_PARAMETERS\"}", AliyunNotificationClient.Result.Outcome.REJECTED),
            Arguments.of(200, "{\"Code\":\"OK\",\"BizId\":\"dummy-accepted\"}", AliyunNotificationClient.Result.Outcome.ACCEPTED));
    }

    private static AliyunNotificationClient client(HttpClient http) {
        return new AliyunNotificationClient("dummy-ak", "dummy-secret", "dummy-sign", "SMS_DUMMY", "TTS_DUMMY", "",
            "https://dysmsapi.aliyuncs.com/", "https://dyvmsapi.aliyuncs.com/", http);
    }

    private static AliyunNotificationClient.Result send(AliyunNotificationClient client, String channel) {
        return channel.equals("SMS")
            ? client.sendSms("+8613800000000", Map.of("device_name", "dummy-smoke"), "dummy-out-id")
            : client.sendVoice("+8613800000000", Map.of("device_name", "dummy-smoke"), "dummy-out-id");
    }
}
