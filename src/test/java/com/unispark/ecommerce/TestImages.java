package com.unispark.ecommerce;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

/** Real image files generated in memory, as a phone or camera would upload them. */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        return encode(picture(width, height, Color.RED, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return encode(picture(width, height, Color.BLUE, BufferedImage.TYPE_INT_ARGB), "png");
    }

    private static BufferedImage picture(int width, int height, Color color, int type) {
        var image = new BufferedImage(width, height, type);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.YELLOW);
        graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, format, output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
