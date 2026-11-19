package com.fireplatform;

import com.fireplatform.notification.*;
import com.fireplatform.shared.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class NotificationPolicyTest {
    @Test void phoneCanonicalizationIsExplicitAndNeverGuessesLandlineAreaOrInternationalCountry(){var storage=new ContactPhoneStorage(null,new MockEnvironment());assertThat(storage.normalize(" 13800001234 ")).isEqualTo("+8613800001234");assertThat(storage.normalize("+8613800001234")).isEqualTo("+8613800001234");assertThat(storage.normalize("010-12345678")).isEqualTo("010-12345678");for(String value:new String[]{"01012345678","+44123456789","138 0000 1234","010--12345678","012-123456","\u000013800001234","\uD800"})assertThatThrownBy(()->storage.normalize(value)).isInstanceOf(ApiError.class);}
}
