package com.unispark.ecommerce.shared.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class StylesheetCheckTest {

    private final DefaultResourceLoader resources = new DefaultResourceLoader();

    @Test
    void reportsAMissingStylesheet() {
        assertThat(new StylesheetCheck(resources, "classpath:static/css/no-such.css").missing()).isTrue();
    }

    @Test
    void acceptsAStylesheetThatIsThere() {
        assertThat(new StylesheetCheck(resources, "classpath:application.yml").missing()).isFalse();
    }
}
