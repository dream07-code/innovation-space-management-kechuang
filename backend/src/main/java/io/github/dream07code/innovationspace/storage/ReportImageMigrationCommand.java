package io.github.dream07code.innovationspace.storage;

import io.github.dream07code.innovationspace.report.ReportImageCompressor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一次性迁移：把历史日报里的 COS 图片搬到本地磁盘，并把库里的地址改成站内相对地址。
 *
 * <p>用法（维护模式，不启动 Web）：
 * {@code java -jar app.jar --app.storage.image-migration=true --app.storage.local.root=/data/uploads}
 *
 * <p>幂等：已经迁过的版本 JSON 里不再含 COS 域名，重跑不会重复下载；
 * 本地文件已存在时跳过写入，只改地址。单张失败只记日志并保留原地址，不中断整批。
 */
@Component
@ConditionalOnProperty(name = "app.storage.image-migration", havingValue = "true")
public class ReportImageMigrationCommand implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(ReportImageMigrationCommand.class);

  private final JdbcTemplate jdbc;
  private final CosReportImageStorage cos;
  private final LocalReportImageProperties local;
  private final ReportImageCompressor compressor = new ReportImageCompressor();
  private final JsonMapper json = JsonMapper.builder().build();

  public ReportImageMigrationCommand(
      JdbcTemplate jdbc, CosReportImageStorage cos, LocalReportImageProperties local) {
    this.jdbc = jdbc;
    this.cos = cos;
    this.local = local;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!local.enabled()) {
      throw new IllegalStateException("app.storage.local.root 未配置，无法迁移到本地");
    }
    Path root = local.rootPath();
    String cosPrefix = cosDomain() + "/";

    List<Map<String, Object>> versions =
        jdbc.queryForList(
            "SELECT id,content_json FROM report_version WHERE content_json LIKE '%myqcloud.com%'"
                + " ORDER BY id");

    int versionsUpdated = 0;
    int imagesMigrated = 0;
    int imagesFailed = 0;
    long bytesBefore = 0;
    long bytesAfter = 0;

    for (Map<String, Object> row : versions) {
      long versionId = ((Number) row.get("id")).longValue();
      String raw = asText(row.get("content_json"));
      Map<String, Object> content;
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(raw, Map.class);
        content = parsed;
      } catch (RuntimeException error) {
        log.warn("跳过无法解析的 report_version id={}: {}", versionId, error.toString());
        continue;
      }
      Object imagesRaw = content.get("images");
      if (!(imagesRaw instanceof List<?> images) || images.isEmpty()) continue;

      boolean changed = false;
      List<Object> rewritten = new ArrayList<>(images.size());
      for (Object item : images) {
        if (!(item instanceof Map<?, ?> rawImage)) {
          rewritten.add(item);
          continue;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> image = new LinkedHashMap<>((Map<String, Object>) rawImage);
        Object urlValue = image.get("url");
        String url = urlValue == null ? "" : String.valueOf(urlValue);
        if (!url.startsWith(cosPrefix)) {
          rewritten.add(image);
          continue;
        }
        String key = url.substring(cosPrefix.length());
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
          log.warn("跳过越界键 versionId={} key={}", versionId, key);
          rewritten.add(image);
          continue;
        }
        try {
          if (!Files.exists(target)) {
            byte[] original = cos.download(url);
            var compressed = compressor.compress(original);
            byte[] stored = compressed == null ? original : compressed.bytes();
            Files.createDirectories(target.getParent());
            Files.write(target, stored, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            bytesBefore += original.length;
            bytesAfter += stored.length;
            log.info(
                "migrated {} ({}B -> {}B, {}x{} -> {}x{})",
                key,
                original.length,
                stored.length,
                compressed == null ? -1 : compressed.sourceWidth(),
                compressed == null ? -1 : compressed.sourceHeight(),
                compressed == null ? -1 : compressed.width(),
                compressed == null ? -1 : compressed.height());
          }
          image.put("url", local.getPublicPrefix() + "/" + key);
          imagesMigrated++;
          changed = true;
        } catch (Exception error) {
          imagesFailed++;
          log.warn("图片迁移失败，保留原地址 versionId={} url={}: {}", versionId, url, error.toString());
        }
        rewritten.add(image);
      }
      if (!changed) continue;

      content.put("images", rewritten);
      String updated = json.writeValueAsString(content);
      jdbc.update(
          "UPDATE report_version SET content_json=?,content_hash=UNHEX(SHA2(?,256)) WHERE id=?",
          updated,
          updated,
          versionId);
      versionsUpdated++;
    }

    log.info(
        "report image migration done: versions={} updated={} imagesMigrated={} failed={} bytes={}B -> {}B",
        versions.size(),
        versionsUpdated,
        imagesMigrated,
        imagesFailed,
        bytesBefore,
        bytesAfter);
  }

  /** 域名来自配置的 COS domain，与上传时拼 URL 用的是同一个值。 */
  private String cosDomain() {
    return cos.domain();
  }

  private static String asText(Object value) {
    if (value == null) return "{}";
    if (value instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    return String.valueOf(value);
  }
}
