package com.unispark.ecommerce.media.internal;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.luciad.imageio.webp.WebPWriteParam;
import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore.Reason;

/**
 * Turns an upload into upright WebP renditions (SPEC 9.5, 10).
 *
 * <ul>
 *   <li>The format is sniffed from the bytes: only JPEG, PNG and WebP are accepted, whatever the upload claims.
 *   <li>Size and pixel count are checked before decoding, against decompression bombs.
 *   <li>Large photos are decoded with subsampling, so at most about {@value #DECODE_TARGET} pixels wide are
 *       ever held in memory, and only {@code permits} images are decoded at once.
 *   <li>Re-encoding drops all metadata (EXIF GPS included) after the EXIF orientation has been applied.
 * </ul>
 */
final class ImageProcessor {

    static final int DECODE_TARGET = 2048;
    static final float WEBP_QUALITY = 0.8f;

    private final long maxBytes;
    private final long maxPixels;
    private final Semaphore decodes;

    ImageProcessor(long maxBytes, long maxPixels, int permits) {
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
        this.decodes = new Semaphore(permits);
    }

    sealed interface Outcome {
    }

    /** {@code width} and {@code height} of the upright original; one rendition per {@link ImageSize}. */
    record Processed(int width, int height, List<Rendition> renditions) implements Outcome {
    }

    record Failed(Reason reason) implements Outcome {
    }

    record Rendition(ImageSize size, int width, int height, byte[] webp) {
    }

    enum Format {
        JPEG("jpeg"),
        PNG("png"),
        WEBP("webp");

        final String imageIoName;

        Format(String imageIoName) {
            this.imageIoName = imageIoName;
        }
    }

    Outcome process(InputStream upload) throws IOException {
        var bytes = upload.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        if (bytes.length > maxBytes) {
            return new Failed(Reason.TOO_LARGE);
        }
        var format = sniff(bytes);
        if (format == null) {
            return new Failed(Reason.UNSUPPORTED_TYPE);
        }
        decodes.acquireUninterruptibly();
        try {
            return render(bytes, format);
        } finally {
            decodes.release();
        }
    }

    private Outcome render(byte[] bytes, Format format) {
        Decoded decoded;
        try {
            decoded = decode(bytes, format);
        } catch (IOException | RuntimeException e) {
            return new Failed(Reason.UNREADABLE);
        }
        if (decoded == null) {
            return new Failed(Reason.TOO_MANY_PIXELS);
        }
        var orientation = orientation(bytes);
        var image = upright(normalized(decoded.image()), orientation);
        var swapped = orientation >= 5;
        var width = swapped ? decoded.originalHeight() : decoded.originalWidth();
        var height = swapped ? decoded.originalWidth() : decoded.originalHeight();
        try {
            var renditions = new ArrayList<Rendition>();
            for (var size : ImageSize.values()) {
                var resized = resize(image, size.width());
                renditions.add(new Rendition(size, resized.getWidth(), resized.getHeight(), webp(resized)));
            }
            return new Processed(width, height, List.copyOf(renditions));
        } catch (IOException | RuntimeException e) {
            return new Failed(Reason.UNREADABLE);
        }
    }

    static Format sniff(byte[] bytes) {
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return Format.JPEG;
        }
        if (startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Format.PNG;
        }
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
            return Format.WEBP;
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private record Decoded(BufferedImage image, int originalWidth, int originalHeight) {
    }

    /** Null when the header announces more pixels than allowed; nothing is decoded in that case. */
    private Decoded decode(byte[] bytes, Format format) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReadersByFormatName(format.imageIoName);
            if (!readers.hasNext()) {
                throw new IOException("No ImageIO reader for " + format);
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                var width = reader.getWidth(0);
                var height = reader.getHeight(0);
                if ((long) width * height > maxPixels) {
                    return null;
                }
                var param = reader.getDefaultReadParam();
                var step = Math.max(1, Math.max(width, height) / DECODE_TARGET);
                if (step > 1 && format != Format.WEBP) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                return new Decoded(reader.read(0, param), width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    /** EXIF orientation 1 to 8; 1 (upright) when absent or unreadable. */
    static int orientation(byte[] bytes) {
        try {
            var directory = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes))
                    .getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                var value = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                return value >= 1 && value <= 8 ? value : 1;
            }
        } catch (Exception e) {
            // No usable metadata: treat the image as upright.
        }
        return 1;
    }

    /** Applies an EXIF orientation, so the result is upright. Orientations 5 to 8 swap width and height. */
    static BufferedImage upright(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        double w = image.getWidth();
        double h = image.getHeight();
        // new AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12
        var transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);     // mirror horizontally
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);    // rotate 180
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);     // mirror vertically
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);      // transpose
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);     // rotate 90 clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);    // transverse
            default -> new AffineTransform(0, -1, 1, 0, 0, w);    // 8: rotate 90 counter-clockwise
        };
        var swap = orientation >= 5;
        var result = new BufferedImage(swap ? image.getHeight() : image.getWidth(),
                swap ? image.getWidth() : image.getHeight(), image.getType());
        var graphics = result.createGraphics();
        try {
            graphics.drawImage(image, transform, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /** Downscales to {@code targetWidth} in halving steps, which looks far better than one big step. */
    static BufferedImage resize(BufferedImage image, int targetWidth) {
        if (targetWidth >= image.getWidth()) {
            return image;
        }
        var targetHeight = Math.max(1, (int) Math.round(image.getHeight() * (double) targetWidth / image.getWidth()));
        var current = image;
        var width = image.getWidth();
        var height = image.getHeight();
        do {
            width = Math.max(width / 2, targetWidth);
            height = Math.max(height / 2, targetHeight);
            current = draw(current, width, height);
        } while (width != targetWidth || height != targetHeight);
        return current;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height) {
        var result = new BufferedImage(width, height, source.getType());
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /** One of two pixel layouts for everything after decoding: RGB, or ARGB when the image has transparency. */
    private static BufferedImage normalized(BufferedImage image) {
        var type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (image.getType() == type) {
            return image;
        }
        var result = new BufferedImage(image.getWidth(), image.getHeight(), type);
        var graphics = result.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    static byte[] webp(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) {
            throw new IOException("No WebP writer available");
        }
        var writer = writers.next();
        var output = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType(param.getCompressionTypes()[WebPWriteParam.LOSSY_COMPRESSION]);
            param.setCompressionQuality(WEBP_QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }
}
