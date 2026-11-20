package com.fireplatform.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AliyunNotificationClientTest {
    @Test void sendsSignedSmsAndVoiceRpcRequests() throws Exception {
        var captured = new java.util.ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.add(exchange.getRequestURI().getRawQuery());
            byte[] body = exchange.getRequestURI().getPath().contains("voice") || captured.size() > 1
                    ? "{\"Code\":\"OK\",\"CallId\":\"call-1\"}".getBytes(StandardCharsets.UTF_8)
                    : "{\"Code\":\"OK\",\"BizId\":\"biz-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new AliyunNotificationClient(new AliyunNotificationClient.Config("ak", "secret", "测试签名", "SMS_1",
                "TTS_1", "0571", base + "/sms", base + "/voice", Map.of("name","device_name"), Map.of("loc","address")),
                HttpClient.newHttpClient(), Duration.ofSeconds(8));
            var sms = client.sendSms("+8613800000000", Map.of("device_name", "烟感", "address", "1号楼"), null);
            assertThat(sms.success()).isTrue();
            assertThat(sms.providerId()).isEqualTo("biz-1");
            assertThat(captured.get(0)).contains("Action=SendSms", "Signature=", "PhoneNumbers=%2B8613800000000");
            assertThat(java.net.URLDecoder.decode(captured.get(0), StandardCharsets.UTF_8)).contains("TemplateParam={\"name\":\"烟感\"}").doesNotContain("1号楼");
            var voice = client.sendVoice("+8613800000000", Map.of("device_name", "烟感", "address", "1号楼"), null);
            assertThat(voice.success()).isTrue();
            assertThat(voice.providerId()).isEqualTo("call-1");
            assertThat(captured.get(1)).contains("Action=SingleCallByTts", "TtsCode=TTS_1", "Signature=");
            assertThat(java.net.URLDecoder.decode(captured.get(1), StandardCharsets.UTF_8)).contains("TtsParam={\"loc\":\"1号楼\"}").doesNotContain("烟感");
            assertThat(client.sendVoice("+8613800000000", Map.of("device_name", "烟感"), null).code()).isEqualTo("TEMPLATE_VARIABLE_MISSING");
            assertThat(captured).hasSize(2);
        } finally {
            server.stop(0);
        }
    }

    @Test void signsReceiptQueriesAndPreservesCorrelationFieldsAndOutId() throws Exception {
        var captured = new java.util.ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            captured.add(query);
            String payload;
            if (query.contains("Action=QuerySendDetails")) payload = "{\"Code\":\"OK\",\"Message\":\"OK\",\"RequestId\":\"r-sms\",\"TotalCount\":\"1\",\"SmsSendDetailDTOs\":{\"SmsSendDetailDTO\":[{\"BizId\":\"biz-1\",\"OutId\":\"abc\",\"PhoneNum\":\"1390000****\",\"SendStatus\":3,\"ErrCode\":\"DELIVERED\",\"TemplateCode\":\"SMS_1\",\"SendDate\":\"2026-11-12 10:00:00\",\"ReceiveDate\":\"2026-11-12 10:00:05\"}]}}";
            else if (query.contains("Action=QueryCallDetailByCallId")) payload = "{\"Code\":\"OK\",\"Message\":\"OK\",\"RequestId\":\"r-voice\",\"Data\":{\"call_id\":\"call-1\",\"state\":\"200000\",\"stateDesc\":\"用户听完语音\",\"startDate\":\"2026-11-12 10:00:00\",\"endDate\":\"2026-11-12 10:00:12\",\"callee\":\"1390000****\",\"duration\":12,\"gmtCreate\":\"2026-11-12 09:59:00\"}}";
            else payload = query.contains("Action=SingleCallByTts") ? "{\"Code\":\"OK\",\"CallId\":\"call-1\"}" : "{\"Code\":\"OK\",\"BizId\":\"biz-1\"}";
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new AliyunNotificationClient(new AliyunNotificationClient.Config("ak", "secret", "测试签名", "SMS_1",
                "TTS_1", "0571", base + "/sms", base + "/voice", Map.of(), Map.of()),
                HttpClient.newHttpClient(), Duration.ofSeconds(8));
            assertThat(client.accountFingerprint()).hasSize(64);
            assertThat(client.sendSms("+8613900001234", Map.of("device_name", "烟感"), "abc").success()).isTrue();
            assertThat(client.sendVoice("+8613900001234", Map.of("device_name", "烟感"), "abc").success()).isTrue();
            LocalDate date = LocalDate.of(2026, 11, 12);
            var sms = client.querySms("+8613900001234", "biz-1", date, 50, 1);
            assertThat(sms.success()).isTrue();
            assertThat(sms.requestId()).isEqualTo("r-sms");
            assertThat(sms.details()).singleElement().satisfies(detail -> {
                assertThat(detail.bizId()).isEqualTo("biz-1");
                assertThat(detail.outId()).isEqualTo("abc");
                assertThat(detail.sendStatus()).isEqualTo(3);
                assertThat(detail.errCode()).isEqualTo("DELIVERED");
            });
            var voice = client.queryVoice("call-1", date);
            assertThat(voice.success()).isTrue();
            assertThat(voice.detail()).isNotNull();
            assertThat(voice.detail().state()).isEqualTo("200000");
            assertThat(voice.detail().duration()).isEqualTo(12);
            long queryDate = date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            assertThat(captured.get(0)).contains("Action=SendSms", "OutId=abc", "Signature=");
            assertThat(captured.get(1)).contains("Action=SingleCallByTts", "OutId=abc", "Signature=");
            assertThat(captured.get(0)).doesNotContain("TemplateParam=");
            assertThat(captured.get(1)).doesNotContain("TtsParam=");
            assertThat(captured.get(2)).contains("Action=QuerySendDetails", "BizId=biz-1", "SendDate=20261112", "PageSize=50", "CurrentPage=1", "Signature=");
            assertThat(captured.get(3)).contains("Action=QueryCallDetailByCallId", "CallId=call-1", "ProdId=11000000300006", "QueryDate=" + queryDate, "Signature=");
        } finally {
            server.stop(0);
        }
    }

    @Test void voiceOnlyConfigurationDoesNotRequireSmsSignOrTemplate() throws Exception {
        var captured = new java.util.ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.add(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"Code\":\"OK\",\"CallId\":\"voice-only\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new AliyunNotificationClient(new AliyunNotificationClient.Config("ak", "secret", "", "", "TTS_ONLY", "",
                base, base, null, Map.of()), HttpClient.newHttpClient(), Duration.ofSeconds(8));
            assertThat(client.sendSms("+8613800000000", Map.of(), null).code()).isEqualTo("NOT_CONFIGURED");
            assertThat(client.sendVoice("+8613800000000", Map.of("device_name", "烟感"), null).providerId()).isEqualTo("voice-only");
            assertThat(captured).singleElement().asString().contains("Action=SingleCallByTts", "TtsCode=TTS_ONLY");
        } finally {
            server.stop(0);
        }
    }

    @Test void missingCredentialsDisableProvider() {
        var client = new AliyunNotificationClient(new AliyunNotificationClient.Config("", "", "", "", "", "",
            "http://127.0.0.1:1/sms", "http://127.0.0.1:1/voice", Map.of(), Map.of()), HttpClient.newHttpClient(), Duration.ofSeconds(8));
        assertThat(client.enabled()).isFalse();
        assertThat(client.sendSms("+8613800000000", Map.of(), null).code()).isEqualTo("NOT_CONFIGURED");
    }
}
