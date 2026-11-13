package io.github.dream07code.innovationspace.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 把日报/周报图片写到本机磁盘，返回站内相对地址。
 *
 * <p>相对地址（{@code /uploads/reports/...}）由后端自己提供读接口：浏览器只连站点域名，
 * 不再请求 {@code *.myqcloud.com}，校园网/VPN 的拦截与限速绕开了。
 *
 * <p>键与文件名都由服务端生成（UUID），所以同名覆盖不会发生；写文件用 CREATE_NEW，
 * 万一键重复宁可报错也不悄悄覆盖别人的图。
 */
public class LocalReportImageStorage implements ReportImageStorage {
  private final LocalReportImageProperties properties;

  public LocalReportImageStorage(LocalReportImageProperties properties) {
    this.properties = properties;
  }

  @Override
  public boolean available() {
    // 目录暂时不可写不算「未配置」：首次启动时目录可能还没建，upload 会自己 mkdirs。
    return properties.enabled();
  }

  @Override
  public String upload(byte[] content, String contentType, String key) {
    Path root = root();
    Path target = resolve(root, key);
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException error) {
      throw new UncheckedIOException("failed to store report image", error);
    }
    return properties.getPublicPrefix() + "/" + key;
  }

  /** 读接口用：把站内相对地址还原成本地文件；越界（../）直接拒绝。 */
  public Path resolveForRead(String relativeKey) {
    return resolve(root(), relativeKey);
  }

  public Path root() {
    Path root = properties.rootPath();
    if (root == null) throw new IllegalStateException("local report image storage is disabled");
    return root;
  }

  /** 归一化后必须仍在根目录内，挡住 {@code ../} 之类的路径穿越。 */
  private static Path resolve(Path root, String key) {
    String cleaned = key == null ? "" : key.trim();
    while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
    if (cleaned.isEmpty()) throw new IllegalArgumentException("empty report image key");
    Path target = root.resolve(cleaned).normalize();
    if (!target.startsWith(root)) throw new IllegalArgumentException("report image key escapes root");
    return target;
  }
}
