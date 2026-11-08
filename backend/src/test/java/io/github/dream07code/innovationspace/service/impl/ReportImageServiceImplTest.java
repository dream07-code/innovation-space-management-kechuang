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
    int calls;

    @Override
    public boolean available() {
      return available;
    }

    @Override
    public String upload(byte[] content, String contentType, String key) {
      calls++;
      lastKey = key;
      return "https://cdn.example.com/" + key;
    }
  }
}
