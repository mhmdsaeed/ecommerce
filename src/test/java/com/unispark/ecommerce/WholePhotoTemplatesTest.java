package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Photos are shown whole inside their frame (object-contain on a light square), never cropped to fill it: a tall
 * bottle or a wide sofa keeps its edges in cards, thumbnails and the cart.
 */
class WholePhotoTemplatesTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.DOTALL);

    @Test
    void imagesAreNeverCroppedToTheirFrame() throws IOException {
        var cropped = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (var file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                var matcher = IMG.matcher(Files.readString(file));
                while (matcher.find()) {
                    if (matcher.group().contains("object-cover")) {
                        cropped.add(TEMPLATES.relativize(file) + ": " + matcher.group().replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertThat(cropped).as("use object-contain on a background instead of object-cover").isEmpty();
    }
}
