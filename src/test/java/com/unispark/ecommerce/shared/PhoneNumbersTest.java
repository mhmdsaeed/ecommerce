package com.unispark.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"0933123456", "0933 123 456", "0933-123-456", "+963933123456", "+963 933 123 456",
            "00963933123456", "963933123456", "(0933) 123456", "٠٩٣٣١٢٣٤٥٦", "۰۹۳۳۱۲۳۴۵۶"})
    void normalizesSyrianMobileNumbersToE164(String input) {
        assertThat(PhoneNumbers.normalize(input)).contains("+963933123456");
    }

    @ParameterizedTest
    @ValueSource(strings = {"011 2345678", "+963112345678"})
    void normalizesSyrianLandlines(String input) {
        assertThat(PhoneNumbers.normalize(input)).contains("+963112345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+905321234567", "00905321234567"})
    void acceptsInternationalNumbersWithACountryCode(String input) {
        assertThat(PhoneNumbers.normalize(input)).contains("+905321234567");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "933123456", "0933", "09331234567890", "+96309331234", "+0123456789",
            "owner@example.com", "+1234567"})
    void rejectsWhatIsNotAPhoneNumber(String input) {
        assertThat(PhoneNumbers.normalize(input)).isEmpty();
    }
}
