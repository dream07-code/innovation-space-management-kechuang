package io.github.dream07code.innovationspace.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 图片存储装配：配了本地目录就存本地，否则维持原来的 COS。
 *
 * <p>回显也在这里落地：本地模式下把 {@code publicPrefix} 映射成静态资源目录，
 * 由后端直接读磁盘返回，浏览器只访问站点自己的域名。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReportImageStorageProperties.class, LocalReportImageProperties.class})
public class ReportImageStorageConfiguration implements WebMvcConfigurer {
  private final ReportImageStorageProperties cosProperties;
  private final LocalReportImageProperties localProperties;

  public ReportImageStorageConfiguration(
      ReportImageStorageProperties cosProperties, LocalReportImageProperties localProperties) {
    this.cosProperties = cosProperties;
    this.localProperties = localProperties;
  }

  /** 上传走哪个存储。本地目录配好就用本地，避免再往 COS 写新图。 */
  @Bean
  @org.springframework.context.annotation.Primary
  ReportImageStorage reportImageStorage() {
    return localProperties.enabled()
        ? new LocalReportImageStorage(localProperties)
        : new CosReportImageStorage(cosProperties);
  }

  /** COS 仍然保留：迁移历史图片时要从桶里下载。 */
  @Bean
  CosReportImageStorage cosReportImageStorage() {
    return new CosReportImageStorage(cosProperties);
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (!localProperties.enabled()) return;
    String location = localProperties.rootPath().toUri().toString();
    if (!location.endsWith("/")) location = location + "/";
    registry
        .addResourceHandler(localProperties.getPublicPrefix() + "/**")
        .addResourceLocations(location)
        // 文件名是 UUID，内容永不变更，可以长缓存。
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable());
  }
}
