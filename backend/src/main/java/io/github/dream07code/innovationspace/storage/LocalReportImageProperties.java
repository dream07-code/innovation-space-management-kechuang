package io.github.dream07code.innovationspace.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 日报/周报图片的本地磁盘存储配置。
 *
 * <p>放本地磁盘而不是对象存储，是为了避开校园网/VPN 对 {@code *.myqcloud.com} 的拦截：
 * 图片和站点同源，浏览器只连自己的域名，不再产生第三方请求。
 *
 * <p>生产环境把 {@code root} 指到挂载卷（如 {@code /data/uploads}），
 * {@code publicPrefix} 保持 {@code /uploads}，数据库里存的就是站内相对地址。
 */
@ConfigurationProperties("app.storage.local")
public class LocalReportImageProperties {
  /** 图片落盘根目录；为空表示不启用本地存储（退回 COS）。 */
  private String root = "";
  /** 对外暴露的站内前缀，不带结尾斜杠。 */
  private String publicPrefix = "/uploads";

  public String getRoot() {
    return root;
  }

  public void setRoot(String root) {
    this.root = root == null ? "" : root.trim();
  }

  public String getPublicPrefix() {
    String value = publicPrefix == null || publicPrefix.isBlank() ? "/uploads" : publicPrefix.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    if (!value.startsWith("/")) value = "/" + value;
    return value;
  }

  public void setPublicPrefix(String publicPrefix) {
    this.publicPrefix = publicPrefix;
  }

  public boolean enabled() {
    return !root.isBlank();
  }

  /** 解析后的根目录；未启用时返回 null。 */
  public Path rootPath() {
    return enabled() ? Path.of(root).toAbsolutePath().normalize() : null;
  }
}
