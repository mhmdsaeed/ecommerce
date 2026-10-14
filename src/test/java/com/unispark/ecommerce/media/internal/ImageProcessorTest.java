package com.unispark.ecommerce.media.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore.Reason;
import com.unispark.ecommerce.media.internal.ImageProcessor.Failed;
import com.unispark.ecommerce.media.internal.ImageProcessor.Processed;

class ImageProcessorTest {

    final ImageProcessor processor = new ImageProcessor(5 * 1024 * 1024, 100_000_000, 2);

    @Test
    void turnsAJpegIntoThreeWebpRenditions() throws IOException {
        var processed = processed(processor, encode(image(2000, 1000, Color.RED), "jpeg"));

        assertThat(processed.width()).isEqualTo(2000);
        assertThat(processed.height()).isEqualTo(1000);
        assertThat(processed.renditions()).extracting(ImageProcessor.Rendition::size)
                .containsExactly(ImageSize.SMALL, ImageSize.MEDIUM, ImageSize.LARGE);
        assertThat(processed.renditions()).extracting(ImageProcessor.Rendition::width).containsExactly(320, 640, 1024);
        assertThat(processed.renditions()).extracting(ImageProcessor.Rendition::height).containsExactly(160, 320, 512);
        for (var rendition : processed.renditions()) {
            assertThat(isWebp(rendition.webp())).isTrue();
            var decoded = ImageIO.read(new ByteArrayInputStream(rendition.webp()));
            assertThat(decoded.getWidth()).isEqualTo(rendition.width());
        }
    }

    @Test
    void acceptsPngWithTransparencyAndWebp() throws IOException {
        var transparent = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        assertThat(processed(processor, encode(transparent, "png")).renditions()).hasSize(3);

        var webp = ImageProcessor.webp(image(700, 300, Color.BLUE));
        assertThat(processed(processor, webp).width()).isEqualTo(700);
    }

    @Test
    void neverUpscalesSmallImages() throws IOException {
        var processed = processed(processor, encode(image(200, 100, Color.GREEN), "png"));

        assertThat(processed.renditions()).extracting(ImageProcessor.Rendition::width).containsOnly(200);
    }

    @Test
    void turnsPhonePhotosUprightFromTheirExifOrientation() throws IOException {
        // A landscape JPEG whose EXIF says "rotate 90° clockwise to display": the upright image is portrait.
        var jpeg = withExifOrientation(encode(image(800, 400, Color.ORANGE), "jpeg"), 6);

        var processed = processed(processor, jpeg);

        assertThat(ImageProcessor.orientation(jpeg)).isEqualTo(6);
        assertThat(processed.width()).isEqualTo(400);
        assertThat(processed.height()).isEqualTo(800);
        assertThat(processed.renditions().getFirst().height()).isGreaterThan(processed.renditions().getFirst().width());
    }

    @Test
    void rotatesAndMirrorsPixelsForEachOrientation() {
        var source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        source.setRGB(1, 0, Color.BLUE.getRGB());

        assertThat(pixels(ImageProcessor.upright(source, 1))).containsExactly(Color.RED.getRGB(), Color.BLUE.getRGB());
        assertThat(pixels(ImageProcessor.upright(source, 2))).containsExactly(Color.BLUE.getRGB(), Color.RED.getRGB());
        assertThat(pixels(ImageProcessor.upright(source, 3))).containsExactly(Color.BLUE.getRGB(), Color.RED.getRGB());
        // 90° clockwise: the left pixel ends up on top.
        assertThat(pixels(ImageProcessor.upright(source, 6))).containsExactly(Color.RED.getRGB(), Color.BLUE.getRGB());
        assertThat(ImageProcessor.upright(source, 6).getWidth()).isEqualTo(1);
        // 90° counter-clockwise: the right pixel ends up on top.
        assertThat(pixels(ImageProcessor.upright(source, 8))).containsExactly(Color.BLUE.getRGB(), Color.RED.getRGB());
    }

    @Test
    void sniffsTheRealFormatInsteadOfTrustingTheUpload() throws IOException {
        assertThat(failure(processor, "GIF89a fake".getBytes(StandardCharsets.US_ASCII))).isEqualTo(Reason.UNSUPPORTED_TYPE);
        assertThat(failure(processor, "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(Reason.UNSUPPORTED_TYPE);
        assertThat(failure(processor, "just text renamed to photo.jpg".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(Reason.UNSUPPORTED_TYPE);
        assertThat(failure(processor, new byte[0])).isEqualTo(Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void reportsBrokenImagesAsUnreadable() throws IOException {
        var jpeg = encode(image(300, 300, Color.RED), "jpeg");

        assertThat(failure(processor, Arrays.copyOf(jpeg, 40))).isEqualTo(Reason.UNREADABLE);
    }

    @Test
    void rejectsUploadsOverTheSizeLimit() throws IOException {
        var small = new ImageProcessor(1_000, 100_000_000, 1);

        assertThat(failure(small, encode(image(600, 600, Color.RED), "png"))).isEqualTo(Reason.TOO_LARGE);
    }

    @Test
    void rejectsTooManyPixelsBeforeDecoding() throws IOException {
        var strict = new ImageProcessor(5 * 1024 * 1024, 10_000, 1);

        assertThat(failure(strict, encode(image(200, 100, Color.RED), "png"))).isEqualTo(Reason.TOO_MANY_PIXELS);
    }

    @Test
    void decodesHugePhotosAtReducedResolution() throws IOException {
        var processed = processed(processor, encode(image(6000, 4000, Color.GRAY), "jpeg"));

        assertThat(processed.width()).isEqualTo(6000);
        assertThat(processed.height()).isEqualTo(4000);
        assertThat(processed.renditions().getLast().width()).isEqualTo(1024);
    }

    private static Processed processed(ImageProcessor processor, byte[] bytes) throws IOException {
        var outcome = processor.process(new ByteArrayInputStream(bytes));
        assertThat(outcome).isInstanceOf(Processed.class);
        return (Processed) outcome;
    }

    private static Reason failure(ImageProcessor processor, byte[] bytes) throws IOException {
        var outcome = processor.process(new ByteArrayInputStream(bytes));
        assertThat(outcome).isInstanceOf(Failed.class);
        return ((Failed) outcome).reason();
    }

    static BufferedImage image(int width, int height, Color color) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width / 4, height / 4);
        graphics.dispose();
        return image;
    }

    static byte[] encode(BufferedImage image, String format) throws IOException {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    /** Inserts an APP1 Exif segment with only an Orientation tag right after the JPEG's SOI marker. */
    static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] exif = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,                       // APP1, length 34
                'E', 'x', 'i', 'f', 0x00, 0x00,                              // Exif header
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,                // big-endian TIFF, IFD0 at offset 8
                0x00, 0x01,                                                  // one entry
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,              // Orientation, SHORT, count 1
                0x00, (byte) orientation, 0x00, 0x00,                        // value
                0x00, 0x00, 0x00, 0x00                                       // no next IFD
        };
        var result = new byte[jpeg.length + exif.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(exif, 0, result, 2, exif.length);
        System.arraycopy(jpeg, 2, result, 2 + exif.length, jpeg.length - 2);
        return result;
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length > 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
