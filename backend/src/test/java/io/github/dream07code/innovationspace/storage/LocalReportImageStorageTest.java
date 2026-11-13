package io.github.dream07code.innovationspace.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 本地存储：落盘位置、返回的站内相对地址、路径穿越防护。 */
class LocalReportImageStorageTest {

  private static LocalReportImageStorage storage(Path root) {
    var props = new LocalReportImageProperties();
    props.setRoot(root.toString());
    props.setPublicPrefix("/uploads");
    return new LocalReportImageStorage(props);
  }

  @Test
  void writesFileUnderRootAndReturnsSiteRelativeUrl(@TempDir Path root) throws IOException {
    var storage = storage(root);
    byte[] content = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};

    String url = storage.upload(content, "image/jpeg", "reports/2026/11/7/abc.jpg");

    assertEquals("/uploads/reports/2026/11/7/abc.jpg", url);
    Path file = root.resolve("reports/2026/11/7/abc.jpg");
    assertTrue(Files.exists(file), "文件应落在配置的根目录下");
    assertArrayEquals(content, Files.readAllBytes(file));
  }

  @Test
  void rejectsKeyEscapingRoot(@TempDir Path root) {
    var storage = storage(root);
    assertThrows(
        IllegalArgumentException.class,
        () -> storage.upload(new byte[] {1}, "image/jpeg", "../outside.jpg"));
  }

  @Test
  void doesNotSilentlyOverwriteExistingKey(@TempDir Path root) {
    var storage = storage(root);
    storage.upload(new byte[] {1}, "image/jpeg", "reports/a.jpg");
    assertThrows(RuntimeException.class, () -> storage.upload(new byte[] {2}, "image/jpeg", "reports/a.jpg"));
  }

  @Test
  void disabledWhenRootBlank() {
    var props = new LocalReportImageProperties();
    assertFalse(props.enabled());
    assertFalse(new LocalReportImageStorage(props).available());
  }
}
