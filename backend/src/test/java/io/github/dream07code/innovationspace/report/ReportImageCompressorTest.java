package io.github.dream07code.innovationspace.report;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 压缩口径：限宽 1600 只缩不放、输出 JPEG 且不带 EXIF。 */
class ReportImageCompressorTest {
  private final ReportImageCompressor compressor = new ReportImageCompressor();

  @Test
  void downscalesWideImageTo1600AndKeepsAspectRatio() throws IOException {
    byte[] source = image(2400, 1200, "png");

    var result = compressor.compress(source);

    assertNotNull(result);
    assertEquals(2400, result.sourceWidth());
    assertEquals(1600, result.width());
    assertEquals(800, result.height(), "等比缩放，不拉伸");
    assertTrue(result.resized());
    assertTrue(result.bytes().length < source.length, "重编码后应更小");
    assertEquals(0xFF, result.bytes()[0] & 0xFF, "输出必须是 JPEG");
    assertEquals(0xD8, result.bytes()[1] & 0xFF);
  }

  @Test
  void neverUpscalesSmallerImage() throws IOException {
    byte[] source = image(800, 600, "jpg");

    var result = compressor.compress(source);

    assertNotNull(result);
    assertEquals(800, result.width());
    assertEquals(600, result.height());
    assertFalse(result.resized());
  }

  @Test
  void stripsExifMetadata() throws IOException {
    byte[] withExif = jpegWithExifMarker();

    var result = compressor.compress(withExif);

    assertNotNull(result);
    // 重编码只保留像素：原图的 EXIF（拍摄设备/地点）不应出现在输出里。
    String asText = new String(result.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(asText.contains("JFIF") || asText.length() > 0);
    assertFalse(asText.contains("Exif"), "EXIF 标记应被丢弃");
    assertFalse(asText.contains("DreamPhone"), "EXIF 里的设备信息应被丢弃");
  }

  @Test
  void returnsNullForUndecodableBytes() {
    assertNull(compressor.compress(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
    assertNull(compressor.compress(new byte[0]));
  }

  /** 造一张“照片味”的图（带伪随机噪声）；纯色块 PNG 比 JPEG 还小，压不出对比效果。 */
  static byte[] image(int width, int height, String format) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    java.util.Random random = new java.util.Random(20261111L);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int gray = 128 + random.nextInt(96) - 48;
        image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
      }
    }
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }

  /** 在真实 JPEG 里插入一段最小 EXIF 结构，用来验证“重编码即去元数据”。 */
  private static byte[] jpegWithExifMarker() throws IOException {
    byte[] plain = image(1200, 900, "jpg");
    byte[] exif = ("Exif\0\0MM\0*DreamPhone\0").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    byte[] app1 = new byte[4 + exif.length];
    app1[0] = (byte) 0xFF;
    app1[1] = (byte) 0xE1;
    int length = exif.length + 2;
    app1[2] = (byte) ((length >> 8) & 0xFF);
    app1[3] = (byte) (length & 0xFF);
    System.arraycopy(exif, 0, app1, 4, exif.length);

    var out = new ByteArrayOutputStream();
    out.write(plain, 0, 2); // SOI
    out.write(app1);
    out.write(plain, 2, plain.length - 2);
    return out.toByteArray();
  }
}
