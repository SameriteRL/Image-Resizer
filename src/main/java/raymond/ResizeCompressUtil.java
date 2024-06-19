package raymond;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.commons.io.IOUtils;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;

public class ResizeCompressUtil {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println(
                "Usage: [program] [image path] [target N x N dimension] " +
                "[target file size]"
            );
            System.exit(0);
        }
        File imgFile = new File(args[0]);
        if (!imgFile.exists() || imgFile.isDirectory()) {
            System.out.println("Missing or invalid file " + args[0]);
            System.exit(0);
        }
        int targetSquareSize = -1;
        try {
            targetSquareSize = Integer.parseInt(args[1]);
        }
        catch (NumberFormatException e) {
            System.out.println("Invalid dimension " + args[1]);
            System.exit(0);
        }
        long targetFileSize = -1;
        try {
            targetFileSize = (long) Double.parseDouble(args[2]);
        }
        catch (NumberFormatException e) {
            System.out.println("Invalid target byte size " + args[2]);
            System.exit(0);
        }
        String format = imgFile.getName().substring(
                imgFile.getName().lastIndexOf('.') + 1
        );
        try (InputStream inStream = new FileInputStream(imgFile)) {
            System.out.println();
            System.out.println("Source image: " + imgFile.getCanonicalPath());
            System.out.println("Target dimensions: " + targetSquareSize + "x" + targetSquareSize);
            System.out.println("Target file size: " + targetFileSize + " bytes");
            System.out.println();
            InputStream newInStream = resizeCompressImage(
                    inStream,
                    format,
                    targetSquareSize,
                    targetFileSize
            );
            BufferedImage img = ImageIO.read(newInStream);
            File outImgFile = new File(imgFile.getParentFile() + "/out.jpg");
            FileImageOutputStream fileOutStream = new FileImageOutputStream(outImgFile);
            // Only JPEG output is supported as of now
            ImageIO.write(img, "jpg", fileOutStream);
            fileOutStream.flush();
            fileOutStream.close();
            System.out.println("Image successfully saved to: " + outImgFile.getCanonicalPath());
            System.out.println();
        }
    }

    /**
     * Resizes and compresses the image represented by the given input stream,
     * and returns a new input stream to the modified image. The result
     * image will be JPEG regardless of whether it was compressed. <br></br>
     * 
     * The image will be resized and compressed such that the result fits
     * within a resizeTarget x resizeTarget space and it is < compressTarget MB
     * in size, if necessary and if possible. <br></br>
     * 
     * Note that the image is not guaranteed to be compressed down all the way
     * to the target size, due to the nature of compression algorithms.
     * <br></br>
     * 
     * This method does not close the provided InputStream after the read
     * operation has completed; it is the responsibility of the caller to close
     * the stream, if desired.
     * 
     * @param inStream Stream to the image to resize and/or compress.
     * @param format Format of the image (e.g. PNG, JPEG).
     * @param resizeTarget Size of the area to resize to, if needed.
     * @param compressTarget File size to compress down to or below, if needed.
     * @return A new InputStream to the modified image.
     * @throws IOException For general I/O errors.
     */
    public static InputStream resizeCompressImage(
            InputStream inStream,
            String format,
            int resizeTarget,
            long compressTarget
    ) throws IOException {
        long imgSizeBytes = inStream.available();
        // Clones input stream, one to be consumed and one to be preserved
        byte[] inStreamArr = IOUtils.toByteArray(inStream);
        inStream.close();
        ByteArrayInputStream origInStream =
               new ByteArrayInputStream(inStreamArr);
        ByteArrayInputStream clonedInStream =
               new ByteArrayInputStream(inStreamArr);
        BufferedImage img = createBufferedImage(clonedInStream);
        // Converts input image to JPEG, if necessary
        if (!format.equalsIgnoreCase("jpeg")
                || !format.equalsIgnoreCase("jpg")
        ) {
            img = convertToJPEG(img);
        }
        ByteArrayOutputStream outByteStream = null;
        boolean imageShrank = false;
        // Resizes image to fit within target space, if necessary
        if (img.getWidth() > resizeTarget || img.getHeight() > resizeTarget) {
            System.out.println("Resizing image...");
            img = shrinkImage(img, resizeTarget);
            imageShrank = true;
            // Estimates file size of resized image
            outByteStream = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", outByteStream);
            imgSizeBytes = outByteStream.size();
        }
        // Compresses image down to target file size, if necessary
        if (imgSizeBytes > compressTarget) {
            System.out.println("Compressing image...");
            return compressImage(img, compressTarget);
        }
        // Returns cloned original InputStream if no modifications needed
        if (!imageShrank) {
            return origInStream;
        }
        // Converts resized-only image OutputStream to InputStream
        ByteArrayInputStream inByteStream =
                new ByteArrayInputStream(outByteStream.toByteArray());
        return inByteStream;
    }

    /**
     * Creates a BufferedImage from an image input stream and corrects its
     * orientation, if necessary. <br></br>
     * 
     * Because ImageIO.read() does not read image metadata, photos that were
     * rotated on smartphones might be loaded in with their original
     * orientation. This function checks the image's orientation metadata and
     * corrects the image if necessary, resulting in a BufferedImage that
     * matches the orientation you see in the file system. <br></br>
     * 
     * If metadata cannot be determined from the stream, an unmodified
     * BufferedImage is returned. <br></br>
     * 
     * This method does not close the provided InputStream after the read
     * operation has completed; it is the responsibility of the caller to close
     * the stream, if desired.
     * 
     * @param inStream Input stream to the image file.
     * @return BufferedImage with the correct orientation.
     * @throws IOException If the stream is invalid.
     */
    public static BufferedImage createBufferedImage(
            InputStream inStream
    ) throws IOException {
        BufferedImage img = ImageIO.read(inStream);
        if (img == null) {
            throw new IOException("Stream could not be read as an image");
        }
        Metadata metadata = null;
        // Returns unmodified image if EXIF orientation cannot be determined
        try {
            metadata = ImageMetadataReader.readMetadata(inStream);
        }
        catch (ImageProcessingException e) {
            return img;
        }
        ExifIFD0Directory exifIFD0 =
            metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (exifIFD0 == null) {
            return img;
        }
        int orientation = -1;
        try {
            orientation = exifIFD0.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        }
        catch (MetadataException e) {
            return img;
        }
        int rotateDegrees = -1;
        switch (orientation) {
            // Image is oriented normally
            case 1:
                return img;
            // Right side, top (rotate 90 degrees CW)
            case 6:
                rotateDegrees = 90;
                break;
            // Bottom, right side (rotate 180 degrees)
            case 3:
                rotateDegrees = 180;
                break;
            // Left side, bottom (rotate 270 degrees CW)
            case 8:
                rotateDegrees = 270;
                break;
        }
        // Rotates the image if orientation is incorrect
        int width = img.getWidth();
        int height = img.getHeight();
        BufferedImage rotatedImage = new BufferedImage(
                height,
                width,
                img.getType()
        );
        Graphics2D g2d = rotatedImage.createGraphics();
        AffineTransform transform = new AffineTransform();
        transform.translate(height / 2, width / 2);
        transform.rotate(Math.toRadians(rotateDegrees));
        transform.translate(-width / 2, -height / 2);
        g2d.setTransform(transform);
        g2d.drawImage(img, 0, 0, null);
        g2d.dispose();
        return rotatedImage;
    }

    /**
     * Creates a new JPEG version of the image, typically PNG but may work with
     * other formats. The original image is not modified.
     * 
     * @param pngImage Image to be converted, typically PNG.
     * @return A JPEG version of the input image.
     */
    public static BufferedImage convertToJPEG(BufferedImage src) {
        BufferedImage jpgImage = new BufferedImage(
                src.getWidth(),
                src.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g2d = jpgImage.createGraphics();
        g2d.drawImage(src, 0, 0, Color.WHITE, null);
        g2d.dispose();
        return jpgImage;
    }

    /**
     * Creates and returns a resized version of the given image such that it
     * fits within a square target x target area, if necessary. <br></br>
     * 
     * Aspect ratio is preserved as much as possible while maximizing visual
     * quality. The original image is not modified.
     * 
     * @param src The image to resize.
     * @param target The square dimension to shrink the image down into.
     * @return The new resized image, which may be the same as the original.
     */
    public static BufferedImage shrinkImage(BufferedImage src, int target) {
        int width = src.getWidth(), height = src.getHeight();
        if (width <= target && height <= target) {
            // No resizing necessary
            return src;
        }
        // Calculates width and height aspect ratio
        int gcd = gcd(width, height);
        int wRatio = width / gcd;
        int hRatio = height / gcd;
        // Resize factor based on longer side of image
        int biggerRatio = Math.max(wRatio, hRatio);
        int newW = -1, newH = -1;
        // For unusual aspect ratios that cannot perfectly shrink down to the
        // target size, resizing is approximated and some pixels will be lost.
        if (biggerRatio > target) {
            double factor = ((double) target) / biggerRatio;
            newW = (int) (wRatio * factor);
            newH = (int) (hRatio * factor);
        }
        else {
            int factor = target / biggerRatio;
            newW = wRatio * factor;
            newH = hRatio * factor;
        }
        Image scaledImg =
                src.getScaledInstance(newW, newH, Image.SCALE_SMOOTH);
        BufferedImage resizedImg =
                new BufferedImage(newW, newH, src.getType());
        Graphics2D g2d = resizedImg.createGraphics();
        g2d.drawImage(scaledImg, 0, 0, null);
        g2d.dispose();
        return resizedImg;
    }

    /**
     * Uses the Euclidean algorithm for greatest common divisors to compute
     * the GCD of two integers.
     * 
     * @param a The first number.
     * @param b The second number.
     * @return The greatest common divisor (GCD) of the two numbers.
     */
    private static int gcd(int a, int b) {
        if (a == 0) {
            return b;
        }
        return gcd(b % a, a);
    }

    /**
     * Creates and returns a compressed version of the given JPEG image as an
     * input stream such that the size of the new image is under the target
     * size in bytes, if possible. At least one compression will be performed
     * regardless of input image's size. <br></br>
     * 
     * Note that the target size is not guaranteed to be reached due to the
     * nature of compression algorithms. <br></br>
     * 
     * The original image is not modified.
     * 
     * @param jpegImg JPEG image to be compressed.
     * @param targetSize Target size in bytes.
     * @return The compressed version of the image as an InputStream.
     * @throws IOException If the image is not JPEG or for general I/O errors.
     */
    private static InputStream compressImage(
            BufferedImage jpegImg,
            long targetSize
    ) throws IOException {
        Iterator<ImageWriter> writers =
                ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("Image is not a valid JPEG");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam writerParams = writer.getDefaultWriteParam();
        if (writerParams.canWriteCompressed()) {
            writerParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        }
        ByteArrayOutputStream outByteStream = new ByteArrayOutputStream();
        float compressionQuality = 1.0f;
        long oldImgSizeBytes = -1, newImgSizeBytes = -1;
        // Repeatedly compresses the image until target size is reached or
        // further compression is useless.
        do {
            outByteStream.reset();
            oldImgSizeBytes = newImgSizeBytes;
            // Strengthens compression each iteration
            if (writerParams.canWriteCompressed()) {
                compressionQuality -= 0.05f;
                writerParams.setCompressionQuality(compressionQuality);
            }
            try (ImageOutputStream imgOutStream =
                    new MemoryCacheImageOutputStream(outByteStream)
            ) {
                writer.setOutput(imgOutStream);
                writer.write(
                        null,
                        new IIOImage(jpegImg, null, null),
                        writerParams
                );
                newImgSizeBytes = imgOutStream.length();
                imgOutStream.flush();
            }
        } while (newImgSizeBytes > targetSize
                    && oldImgSizeBytes != newImgSizeBytes
                    && compressionQuality > 0.05);
        writer.dispose();
        ByteArrayInputStream inByteStream =
                new ByteArrayInputStream(outByteStream.toByteArray());
        return inByteStream;
    }
}
