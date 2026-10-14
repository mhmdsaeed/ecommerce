package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** Every message key exists in both bundles with a non-blank value (SPEC 11). */
class MessageBundleParityTest {

    @Test
    void arabicAndEnglishBundlesHaveTheSameKeys() throws IOException {
        var arabic = load("messages_ar.properties");
        var english = load("messages_en.properties");

        assertThat(arabic.stringPropertyNames())
                .as("keys in messages_ar.properties vs messages_en.properties")
                .containsExactlyInAnyOrderElementsOf(english.stringPropertyNames());
    }

    @Test
    void noMessageIsBlank() throws IOException {
        for (var bundle : new String[] {"messages_ar.properties", "messages_en.properties"}) {
            var messages = load(bundle);
            assertThat(messages.stringPropertyNames())
                    .as("blank messages in %s", bundle)
                    .allSatisfy(key -> assertThat(messages.getProperty(key)).isNotBlank());
        }
    }

    private static Properties load(String name) throws IOException {
        try (var in = MessageBundleParityTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            var properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
