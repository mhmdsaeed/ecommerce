package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Templates use only logical (RTL-safe) Tailwind utilities (CLAUDE.md, SPEC 9.1). */
class RtlSafeTemplatesTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** class, th:class, th:classappend, :class (Alpine) attribute values. */
    private static final Pattern CLASS_ATTRIBUTE =
            Pattern.compile("(?:\\bclass|th:class|th:classappend|:class|x-bind:class)\\s*=\\s*([\"'])(.*?)\\1", Pattern.DOTALL);

    /** Physical-direction utilities, with optional variants (sm:, hover:, rtl:...) and a negative sign. */
    private static final Pattern PHYSICAL_UTILITY = Pattern.compile(
            "(?:^|[\\s'\"{])(?:[\\w\\[\\]&-]+:)*-?"
                    + "(?:m[lr]|p[lr]|scroll-m[lr]|scroll-p[lr]|left|right|inset-[lr]"
                    + "|border-[lr]|rounded-[lr]|rounded-[tb][lr]"
                    + "|text-left|text-right|float-left|float-right|clear-left|clear-right)"
                    + "(?:-|(?=[\\s'\"}]|$))");

    @Test
    void templatesUseOnlyLogicalDirectionUtilities() throws IOException {
        var violations = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (var file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                var matcher = CLASS_ATTRIBUTE.matcher(Files.readString(file));
                while (matcher.find()) {
                    if (PHYSICAL_UTILITY.matcher(matcher.group(2)).find()) {
                        violations.add(TEMPLATES.relativize(file) + ": class=\"" + matcher.group(2).strip() + "\"");
                    }
                }
            }
        }
        assertThat(violations).as("use ms-/me-/ps-/pe-/start-/end-/text-start/text-end instead").isEmpty();
    }

    @Test
    void detectsPhysicalUtilities() {
        assertThat(List.of("ml-4", "mr-auto", "pl-2", "pr-0", "-ml-1", "left-0", "right-2", "sm:text-left",
                "rounded-l-lg", "border-r", "text-right", "hover:pl-3", "rounded-tr-md"))
                .allSatisfy(utility -> assertThat(PHYSICAL_UTILITY.matcher(utility).find()).as(utility).isTrue());
    }

    @Test
    void allowsLogicalUtilities() {
        assertThat(List.of("ms-4", "me-auto", "ps-2", "pe-0", "start-0", "end-2", "text-start", "text-end",
                "rounded-s-lg", "border-e", "max-w-5xl", "mx-auto", "px-4", "items-center", "translate-x-1"))
                .allSatisfy(utility -> assertThat(PHYSICAL_UTILITY.matcher(utility).find()).as(utility).isFalse());
    }
}
