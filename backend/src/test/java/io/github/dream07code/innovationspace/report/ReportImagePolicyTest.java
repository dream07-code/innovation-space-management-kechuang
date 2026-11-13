package io.github.dream07code.innovationspace.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportImagePolicyTest {
  private static final String DOMAIN = "https://cdn.example.com";
  private final ReportImagePolicy policy = new ReportImagePolicy(DOMAIN);

  private static Map<String, Object> image(String url) {
    Map<String, Object> item = new HashMap<>();
    item.put("url", url);
    item.put("name", "笔记.jpg");
    item.put("size", 1024);
    return item;
  }

  @Test
  void acceptsOwnStorageUrlAndDefaultsAnchorToEnd() {
    var result = policy.normalize(List.of(image(DOMAIN + "/reports/2026/11/7/a.jpg")), ReportType.DAILY);
    assertEquals(1, result.size());
    assertEquals("end", result.get(0).get("anchor"));
    assertEquals("笔记.jpg", result.get(0).get("name"));
    assertEquals(1024L, result.get(0).get("size"));
  }

  @Test
  void keepsExplicitAnchorWhenItBelongsToThatReportType() {
    Map<String, Object> item = image(DOMAIN + "/reports/2026/11/7/a.jpg");
    item.put("anchor", "gains");
    var daily = policy.normalize(List.of(item), ReportType.DAILY);
    assertEquals("gains", daily.get(0).get("anchor"));

    // 周报没有 gains 段落，同一锚点必须被拒。
    assertEquals(400, assertThrows(ApiException.class, () -> policy.normalize(List.of(item), ReportType.WEEKLY)).status());
  }

  @Test
  void rejectsExternalUrls() {
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () -> policy.normalize(List.of(image("https://evil.example/track.gif")), ReportType.DAILY))
            .status());
  }

  @Test
  void rejectsLookalikeDomainThatOnlySharesPrefix() {
    String lookalike = DOMAIN + ".evil.com/x.jpg";
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> policy.normalize(List.of(image(lookalike)), ReportType.DAILY))
            .status());
  }

  @Test
  void rejectsMoreThanTenImages() {
    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = 0; i < 11; i++) items.add(image(DOMAIN + "/reports/a" + i + ".jpg"));
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> policy.normalize(items, ReportType.DAILY)).status());
  }

  @Test
  void rejectsOversizedEntryAndBadTypes() {
    Map<String, Object> tooBig = image(DOMAIN + "/reports/a.jpg");
    tooBig.put("size", 11 * 1024 * 1024);
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> policy.normalize(List.of(tooBig), ReportType.DAILY)).status());

    Map<String, Object> notANumber = image(DOMAIN + "/reports/a.jpg");
    notANumber.put("size", "big");
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> policy.normalize(List.of(notANumber), ReportType.DAILY)).status());

    Map<String, Object> longName = image(DOMAIN + "/reports/a.jpg");
    longName.put("name", "x".repeat(121));
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> policy.normalize(List.of(longName), ReportType.DAILY)).status());
  }

  @Test
  void treatsMissingValueAsEmptyList() {
    assertEquals(List.of(), policy.normalize(null, ReportType.DAILY));
  }

  @Test
  void acceptsSiteRelativeUploadsAndLegacyCosUrlsTogether() {
    // 迁移过渡期：新图是 /uploads/...，老日报还是 COS 域名，两种都必须放行。
    var both = new ReportImagePolicy(List.of("/uploads", DOMAIN));

    var local =
        both.normalize(List.of(image("/uploads/reports/2026/11/7/a.jpg")), ReportType.DAILY);
    assertEquals("/uploads/reports/2026/11/7/a.jpg", local.get(0).get("url"));

    var legacy = both.normalize(List.of(image(DOMAIN + "/reports/2026/11/7/b.jpg")), ReportType.DAILY);
    assertEquals(DOMAIN + "/reports/2026/11/7/b.jpg", legacy.get(0).get("url"));

    // 站外地址仍然拒绝：不能因为多了个前缀就放宽成任意外链。
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () -> both.normalize(List.of(image("https://evil.example.com/a.jpg")), ReportType.DAILY))
            .status());
  }

  @Test
  void rejectsEverythingWhenDomainIsNotConfigured() {
    ReportImagePolicy unconfigured = new ReportImagePolicy("");
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () -> unconfigured.normalize(List.of(image(DOMAIN + "/reports/a.jpg")), ReportType.DAILY))
            .status());
  }
}
