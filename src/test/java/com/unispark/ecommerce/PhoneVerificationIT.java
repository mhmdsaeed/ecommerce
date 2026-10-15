package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.unispark.ecommerce.customer.PhoneVerification;
import com.unispark.ecommerce.customer.PhoneVerification.InvalidPhone;
import com.unispark.ecommerce.customer.PhoneVerification.NoCode;
import com.unispark.ecommerce.customer.PhoneVerification.Sent;
import com.unispark.ecommerce.customer.PhoneVerification.TooManyCodes;
import com.unispark.ecommerce.customer.PhoneVerification.Verified;
import com.unispark.ecommerce.customer.PhoneVerification.WrongCode;
import com.unispark.ecommerce.shared.TenantContext;

/** Phone codes (SPEC 6.4) through the logging channel (SPEC 12): sending, guessing, limits, tokens. */
@SpringBootTest
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PhoneVerificationIT {

    static final Locale ARABIC = Locale.forLanguageTag("ar");

    @Autowired
    PhoneVerification verification;

    @Autowired
    JdbcTemplate jdbc;

    UUID store;

    @BeforeEach
    void createStore() {
        store = TestStores.create(jdbc, TestStores.randomSlug(), "ACTIVE");
    }

    @Test
    void sendsACodeAndVerifiesIt(CapturedOutput output) {
        assertThat(as(() -> verification.send("0933 500 001", ARABIC))).isEqualTo(new Sent("+963933500001"));
        var code = lastCode(output, "+963933500001");
        assertThat(output.getOut()).contains("رمز التحقق لدى متجر");

        var verified = (Verified) as(() -> verification.verify("+963 933 500 001", " " + code + " "));

        assertThat(verified.phone()).isEqualTo("+963933500001");
        assertThat(as(() -> verification.isVerified("0933500001", verified.token()))).isTrue();
        // The token belongs to that phone, and to nothing once used up or tampered with.
        assertThat(as(() -> verification.isVerified("0933500002", verified.token()))).isFalse();
        assertThat(as(() -> verification.isVerified("0933500001", "x".repeat(43)))).isFalse();
        assertThat(as(() -> verification.isVerified("0933500001", null))).isFalse();
        // A used code cannot be used again.
        assertThat(as(() -> verification.verify("0933500001", code))).isInstanceOf(NoCode.class);
    }

    @Test
    void acceptsArabicDigits(CapturedOutput output) {
        as(() -> verification.send("0933 500 002", ARABIC));
        var arabic = new StringBuilder();
        for (var digit : lastCode(output, "+963933500002").toCharArray()) {
            arabic.append((char) ('٠' + (digit - '0')));
        }

        assertThat(as(() -> verification.verify("0933500002", arabic.toString()))).isInstanceOf(Verified.class);
    }

    @Test
    void allowsFiveGuessesPerCode(CapturedOutput output) {
        as(() -> verification.send("0933 500 003", ARABIC));
        var code = lastCode(output, "+963933500003");
        var wrong = code.equals("000000") ? "111111" : "000000";

        for (var left = 4; left >= 1; left--) {
            assertThat(as(() -> verification.verify("0933500003", wrong))).isEqualTo(new WrongCode(left));
        }
        assertThat(as(() -> verification.verify("0933500003", wrong))).isInstanceOf(NoCode.class);
        // Out of attempts: even the right code no longer works.
        assertThat(as(() -> verification.verify("0933500003", code))).isInstanceOf(NoCode.class);
    }

    @Test
    void limitsCodesPerPhone() {
        for (var i = 0; i < PhoneVerification.MAX_CODES; i++) {
            assertThat(as(() -> verification.send("0933 500 004", ARABIC))).isInstanceOf(Sent.class);
        }
        assertThat(as(() -> verification.send("0933500004", ARABIC))).isInstanceOf(TooManyCodes.class);
        assertThat(as(() -> verification.send("0933500005", ARABIC))).isInstanceOf(Sent.class);
        assertThat(as(() -> verification.send("12345", ARABIC))).isInstanceOf(InvalidPhone.class);
    }

    @Test
    void codesExpire(CapturedOutput output) {
        as(() -> verification.send("0933 500 006", ARABIC));
        var code = lastCode(output, "+963933500006");
        TenantContext.runAs(store, () -> jdbc.update(
                "UPDATE otp_challenge SET expires_at = now() - interval '1 second' WHERE phone = '+963933500006'"));

        assertThat(as(() -> verification.verify("0933500006", code))).isInstanceOf(NoCode.class);
    }

    @Test
    void codesBelongToTheirStore(CapturedOutput output) {
        as(() -> verification.send("0933 500 007", ARABIC));
        var code = lastCode(output, "+963933500007");
        var other = TestStores.create(jdbc, TestStores.randomSlug(), "ACTIVE");

        assertThat(TenantContext.callAs(other, () -> verification.verify("0933500007", code))).isInstanceOf(NoCode.class);
        assertThat(as(() -> verification.verify("0933500007", code))).isInstanceOf(Verified.class);
    }

    private static String lastCode(CapturedOutput output, String phone) {
        // The code ends the message; the store name before it may contain digits too.
        var matcher = Pattern.compile("Message to " + Pattern.quote(phone) + ": .*: (\\d{6})\\s*$", Pattern.MULTILINE)
                .matcher(output.getOut());
        String code = null;
        while (matcher.find()) {
            code = matcher.group(1);
        }
        assertThat(code).as("code logged for %s", phone).isNotNull();
        return code;
    }

    private <T> T as(Supplier<T> action) {
        return TenantContext.callAs(store, action);
    }
}
