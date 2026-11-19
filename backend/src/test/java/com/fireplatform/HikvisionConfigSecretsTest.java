package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fireplatform.hikvision.HikvisionConfigSecrets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HikvisionConfigSecretsTest {
    private static final String MASTER = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void secretIsEncryptedMaskedAndDecryptableOnlyWithDedicatedMasterKey() {
        var first = new HikvisionConfigSecrets(new MockEnvironment().withProperty("APP_HIKVISION_CONFIG_KEY", MASTER));
        var protectedValue = first.protect("fixture-secret-value");
        assertThat(protectedValue.cipher()).doesNotContain("fixture-secret-value");
        assertThat(protectedValue.masked()).isEqualTo("****alue");
        assertThat(first.reveal(protectedValue.cipher(), protectedValue.nonce())).isEqualTo("fixture-secret-value");

        byte[] different = new byte[32]; different[0] = 1;
        var otherMaster = new HikvisionConfigSecrets(new MockEnvironment().withProperty("APP_HIKVISION_CONFIG_KEY", Base64.getEncoder().encodeToString(different)));
        assertThatThrownBy(() -> otherMaster.reveal(protectedValue.cipher(), protectedValue.nonce()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void missingOrInvalidIndependentMasterKeyRefusesCredentialWrites() {
        var absent = new HikvisionConfigSecrets(new MockEnvironment());
        assertThat(absent.ready()).isFalse();
        assertThatThrownBy(() -> absent.protect("fixture-secret-value")).isInstanceOf(IllegalStateException.class);
        var invalid = new HikvisionConfigSecrets(new MockEnvironment().withProperty("APP_HIKVISION_CONFIG_KEY", "not-base64"));
        assertThat(invalid.ready()).isFalse();
    }
}
