package com.unispark.ecommerce.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.unispark.ecommerce.notification.internal.Message.Medium;
import com.unispark.ecommerce.notification.internal.NotificationProperties.Sms;
import com.unispark.ecommerce.notification.internal.NotificationProperties.WhatsApp;

@ExtendWith(OutputCaptureExtension.class)
class LoggingChannelTest {

    static final String CODE_TEXT = "Rose Shop: your code is 482913";

    @Test
    void withholdsTheTextAndMasksThePhoneOutsideLocalRuns(CapturedOutput output) {
        channel(false).send(Message.phone("+963944555666", CODE_TEXT));

        assertThat(output.getOut())
                .contains("PHONE to +963******666 not delivered")
                .doesNotContain("944555666")
                .doesNotContain("482913")
                .doesNotContain("Rose Shop");
    }

    @Test
    void withholdsEmailsOutsideLocalRuns(CapturedOutput output) {
        channel(false).send(new Message(Medium.EMAIL, "owner@demo1.test", "New order #1001", "Rana, 0944 555 666",
                null));

        assertThat(output.getOut())
                .contains("EMAIL to o***@demo1.test not delivered")
                .doesNotContain("owner@")
                .doesNotContain("New order")
                .doesNotContain("Rana");
    }

    @Test
    void logsMessagesInFullOnLocalRuns(CapturedOutput output) {
        channel(true).send(Message.phone("+963944555666", CODE_TEXT));

        assertThat(output.getOut()).contains("Message to +963944555666: " + CODE_TEXT);
    }

    @Test
    void masksOddRecipientsCompletely() {
        assertThat(LoggingChannel.maskPhone("+96312")).isEqualTo("***");
        assertThat(LoggingChannel.maskPhone(null)).isEqualTo("***");
        assertThat(LoggingChannel.maskEmail("@nobody")).isEqualTo("***");
        assertThat(LoggingChannel.maskEmail("plain")).isEqualTo("***");
        assertThat(LoggingChannel.maskPhone("+12025550123")).isEqualTo("+120*****123");
    }

    private static LoggingChannel channel(boolean logContent) {
        return new LoggingChannel(new NotificationProperties(null, "https", new Sms(null, null),
                new WhatsApp(null, null), logContent));
    }
}
