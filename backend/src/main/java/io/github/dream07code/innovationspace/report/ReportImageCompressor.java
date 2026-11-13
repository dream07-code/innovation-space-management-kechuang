package io.github.dream07code.innovationspace.report;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 日报图片压缩：限宽 1600px（只缩不放）、JPEG 质量 82、重编码丢掉 EXIF。
 *
 * <p>为什么重编码就能去元数据：ImageIO 只解出像素，写出时是全新的 JPEG 文件，
 * 原图的 EXIF（拍摄地点、设备、时间）不会跟着走，不需要额外的元数据剥离库。
 *
 * <p>为什么先铺白底：PNG/WebP 可能带透明通道，直接写 JPEG 会把透明区域变黑；
 * 日报里的截图、手写照片按白底合成更接近原意。
 */
public final class ReportImageCompressor {
  /** 内容宽度上限；超过才缩，避免小图被放大糊掉。 */
  public static final int MAX_WIDTH = 1600;
  /** JPEG 质量：82 在“文字清晰”和“体积一百来 KB”之间比较稳。 */
  public static final float JPEG_QUALITY = 0.82f;

  /** 压缩结果；{@code contentType} 固定为 image/jpeg。 */
  public record Compressed(
      byte[] bytes, int sourceWidth, int sourceHeight, int width, int height) {
    public boolean resized() {
      return width != sourceWidth || height != sourceHeight;
    }
  }

  /**
   * 压一张图。
   *
   * @return 压缩结果；无法解码（例如 ImageIO 不支持的 WebP）时返回 {@code null}，
   *     由调用方按原样存储，不能因为压不了就把用户的上传拒掉。
   */
  public Compressed compress(byte[] original) {
    if (original == null || original.length == 0) return null;
    BufferedImage source;
    try {
      source = ImageIO.read(new ByteArrayInputStream(original));
    } catch (IOException | RuntimeException error) {
      return null;
    }
    if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return null;

    int sourceWidth = source.getWidth();
    int sourceHeight = source.getHeight();
    int targetWidth = Math.min(sourceWidth, MAX_WIDTH);
    int targetHeight = sourceWidth <= MAX_WIDTH
        ? sourceHeight
        : Math.max(1, (int) Math.round(sourceHeight * (targetWidth / (double) sourceWidth)));

    BufferedImage canvas = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = canvas.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, targetWidth, targetHeight);
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
    } finally {
      graphics.dispose();
    }

    byte[] encoded = encodeJpeg(canvas);
    if (encoded == null || encoded.length == 0) return null;
    return new Compressed(encoded, sourceWidth, sourceHeight, targetWidth, targetHeight);
  }

  private static byte[] encodeJpeg(BufferedImage image) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) return null;
    ImageWriter writer = writers.next();
    try (ByteArrayOutputStream out = new ByteArrayOutputStream(image.getWidth() * 4)) {
      try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
        writer.setOutput(stream);
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
          param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
          param.setCompressionQuality(JPEG_QUALITY);
        }
        writer.write(null, new IIOImage(image, null, null), param);
      }
      return out.toByteArray();
    } catch (IOException | RuntimeException error) {
      return null;
    } finally {
      writer.dispose();
    }
  }
}
