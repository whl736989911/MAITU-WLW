package com.fireplatform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.RequestSecurity;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

class NotificationCapabilitiesReadinessTest {
    @ParameterizedTest
    @MethodSource("readiness")
    void requestedSendingDoesNotInventReadyChannels(boolean requested, boolean smsConfigured,
            boolean voiceConfigured, boolean smsReady, boolean voiceReady) {
        var request = new MockHttpServletRequest();
        var security = mock(RequestSecurity.class);
        var actor = new AuthService.Identity(
            new AuthService.UserView("capability-reader", "capability-reader", "Reader", "PLATFORM", null, false),
            List.of("notifications:read"), "management", "dummy-session");
        when(security.identity(request)).thenReturn(actor);
        var phones = mock(ContactPhoneStorage.class);
        when(phones.contactStorageReady()).thenReturn(true);
        var provider = mock(NotificationProviderService.class);
        when(provider.status()).thenReturn(new NotificationProviderService.Status(requested, smsConfigured,
            voiceConfigured, true, smsConfigured, voiceConfigured, false, null, 0));
        var real = mock(RealNotificationService.class);
        when(real.stats()).thenReturn(new RealNotificationService.Stats(requested, 0, 0, null));
        var controller = new NotificationController(security, phones, mock(NotificationReads.class),
            mock(NotificationConfigCommands.class), real, mock(NotificationChannelPolicyService.class), provider);

        var result = controller.capabilities(request);

        assertThat(result.get("provider_ready")).isEqualTo(smsReady || voiceReady);
        assertThat(result.get("sms_ready")).isEqualTo(smsReady);
        assertThat(result.get("voice_ready")).isEqualTo(voiceReady);
        assertThat(result.get("contact_storage_ready")).isEqualTo(true);
    }

    private static Stream<Arguments> readiness() {
        return Stream.of(
            Arguments.of(true, false, false, false, false),
            Arguments.of(true, true, false, true, false),
            Arguments.of(true, false, true, false, true),
            Arguments.of(false, true, true, false, false));
    }
}
