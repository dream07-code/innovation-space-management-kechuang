package io.github.dream07code.innovationspace.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.RateLimitProperties;
import io.github.dream07code.innovationspace.security.RateLimitService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.storage.ReportImageStorage;
import io.github.dream07code.innovationspace.storage.ReportImageStorageProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ReportImageServiceImplTest {
  private static final SessionPrincipal STUDENT =
      new SessionPrincipal(7L, 10L, "STUDENT", List.of(), false, 1, 1);
  private static final byte[] JPEG = {
    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0
  };
  private static final byte[] PNG = {
    (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A, 0, 0
  };
  private static final byte[] WEBP = {
    (byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F', 0, 0, 0, 0,
    (byte) 'W', (byte) 'E', (byte) 'B', (byte) 'P', 0, 0, 0, 0
  };
  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-11-08T02:00:00Z"), ZoneOffset.UTC);

  private final FakeStorage storage = new FakeStorage();
  private ReportImageServiceImpl service() {
    ReportImageStorageProperties props = new ReportImageStorageProperties();
    props.setDomain("https://cdn.example.com");
    return new ReportImageServiceImpl(
        storage, props, new RateLimitService(FIXED, new RateLimitProperties()), FIXED);
  }

  @Test
  void uploadsJpegAndBuildsDatedKeyUnderUserFolder() {
    Map<String, Object> result =
        service().upload(new MockMultipartFile("file", "note.jpg", "image/jpeg", JPEG), STUDENT);

    assertEquals("image/jpeg", result.get("contentType"));
    assertEquals("note.jpg", result.get("name"));
    assertEquals(JPEG.length, result.get("size"));
    assertEquals("https://cdn.example.com/" + storage.lastKey, result.get("url"));
    // 北京时间 10:00，按 前缀/年/月/用户 归档。
    assertTrue(storage.lastKey.startsWith("reports/2026/11/7/"), storage.lastKey);
    assertTrue(storage.lastKey.endsWith(".jpg"), storage.lastKey);
  }

  @Test
  void detectsPngFromMagicBytes() {
    Map<String, Object> result =
        service().upload(new MockMultipartFile("file", "a.png", "image/png", PNG), STUDENT);
    assertEquals("image/png", result.get("contentType"));
    assertTrue(storage.lastKey.endsWith(".png"), storage.lastKey);
  }

  @Test
  void compressesRealImageToJpegBeforeStoring() throws Exception {
    // 2000px 宽的 PNG（模拟手机原图/截图），上传后应压到 1600px 宽并以 JPEG 存储。
    byte[] original = realImage(2000, 1000);

    var result = service().upload(new MockMultipartFile("file", "shot.png", "image/png", original), STUDENT);

    assertEquals("image/jpeg", result.get("contentType"));
    assertTrue(storage.lastKey.endsWith(".jpg"), storage.lastKey);
    assertEquals("image/jpeg", storage.lastType);
    int stored = (Integer) result.get("size");
    assertTrue(stored < original.length, "存下来应比原图小：" + stored + " vs " + original.length);
    assertEquals(storage.lastBytes.length, stored);
    assertEquals(0xFF, storage.lastBytes[0] & 0xFF, "落盘的是 JPEG");
  }

  @Test
  void keepsUndecodableImageAsIsInsteadOfFailingUpload() {
    // WebP 这类 ImageIO 解不了的格式：按原样存储，不能把上传直接拒掉。
    var result =
        service().upload(new MockMultipartFile("file", "a.webp", "image/webp", WEBP), STUDENT);
    assertEquals("image/webp", result.get("contentType"));
    assertEquals(WEBP.length, result.get("size"));
    assertTrue(storage.lastKey.endsWith(".webp"), storage.lastKey);
  }

  /** 生成一张“照片味”的图（带噪声），纯色块 PNG 反而比 JPEG 小，压不出对比。 */
  private static byte[] realImage(int width, int height) throws Exception {
    var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var random = new java.util.Random(20261111L);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int gray = 128 + random.nextInt(96) - 48;
        image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
      }
    }
    var out = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  @Test
  void rejectsBytesThatAreNotAnAllowedImageEvenIfDeclaredAsImage() {
    var fake = new MockMultipartFile("file", "x.jpg", "image/jpeg", "not an image".getBytes());
    ApiException error = assertThrows(ApiException.class, () -> service().upload(fake, STUDENT));
    assertEquals(400, error.status());
    assertEquals(0, storage.calls);
  }

  @Test
  void rejectsEmptyFile() {
    var empty = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[0]);
    assertEquals(400, assertThrows(ApiException.class, () -> service().upload(empty, STUDENT)).status());
  }

  @Test
  void rejectsWhenStorageCredentialsAreMissing() {
    storage.available = false;
    var file = new MockMultipartFile("file", "x.jpg", "image/jpeg", JPEG);
    assertEquals(503, assertThrows(ApiException.class, () -> service().upload(file, STUDENT)).status());
  }

  @Test
  void stripsPathAndControlCharactersFromFileName() {
    var file =
        new MockMultipartFile("file", "C:\\Windows\\evil\u0000name.jpg", "image/jpeg", JPEG);
    Map<String, Object> result = service().upload(file, STUDENT);
    assertEquals("evilname.jpg", result.get("name"));
  }

  @Test
  void fallsBackToDefaultNameWhenOriginalIsMissing() {
    var file = new MockMultipartFile("file", null, "image/jpeg", JPEG);
    assertEquals("note.jpg", service().upload(file, STUDENT).get("name"));
  }

  @Test
  void rateLimitsMoreThanTwentyUploadsPerMinute() {
    var service = service();
    var file = new MockMultipartFile("file", "note.jpg", "image/jpeg", JPEG);
    for (int i = 0; i < 20; i++) service.upload(file, STUDENT);
    assertEquals(429, assertThrows(ApiException.class, () -> service.upload(file, STUDENT)).status());
  }

  private static final class FakeStorage implements ReportImageStorage {
    boolean available = true;
    String lastKey;
    String lastType;
    byte[] lastBytes;
    int calls;

    @Override
    public boolean available() {
      return available;
    }

    @Override
    public String upload(byte[] content, String contentType, String key) {
      calls++;
      lastKey = key;
      lastType = contentType;
      lastBytes = content;
      return "https://cdn.example.com/" + key;
    }
  }
}
