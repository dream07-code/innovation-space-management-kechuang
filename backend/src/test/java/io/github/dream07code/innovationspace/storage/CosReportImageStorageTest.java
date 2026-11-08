package io.github.dream07code.innovationspace.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * COS V5 签名回归测试。
 *
 * <p>期望值由一份独立实现（已对真实 COS 上传验证通过）算出，用来钉住两道容易写错的地方：
 * 一级 HMAC 的结果要以十六进制文本参与二级签名；header 值需要 RFC 3986 编码（斜杠要转义）。
 */
class CosReportImageStorageTest {
  private static CosReportImageStorage storage() {
    var props = new ReportImageStorageProperties();
    props.setSecretId("AKIDtestexample0000000000000000000");
    props.setSecretKey("secretkey-for-cos-signature-unit-test");
    props.setBucket("examplebucket-1250000000");
    props.setRegion("ap-beijing");
    props.setDomain("https://examplebucket-1250000000.cos.ap-beijing.myqcloud.com");
    return new CosReportImageStorage(props);
  }

  @Test
  @DisplayName("PUT Object 的 Authorization 与 COS 规范一致")
  void authorizationMatchesCosSpec() {
    String authorization = storage().authorization("/reports/2026/11/7/abc.jpg", "image/jpeg", 1794134935L);

    assertThat(authorization)
        .isEqualTo(
            "q-sign-algorithm=sha1"
                + "&q-ak=AKIDtestexample0000000000000000000"
                + "&q-sign-time=1794134935;1794135835"
                + "&q-key-time=1794134935;1794135835"
                + "&q-header-list=content-type;host"
                + "&q-url-param-list="
                + "&q-signature=72da2a1a615f4b9e76041fa95196550f5bbb6e7c");
  }

  @Test
  @DisplayName("header 值按 RFC 3986 编码，斜杠转成 %2F")
  void urlEncodeFollowsRfc3986() {
    assertThat(CosReportImageStorage.urlEncode("image/jpeg")).isEqualTo("image%2Fjpeg");
    assertThat(CosReportImageStorage.urlEncode("a b+c")).isEqualTo("a%20b%2Bc");
    assertThat(CosReportImageStorage.urlEncode("A-z_0.9~")).isEqualTo("A-z_0.9~");
  }

  @Test
  @DisplayName("缺任一配置项即视为未启用")
  void unavailableWithoutSecret() {
    var props = new ReportImageStorageProperties();
    props.setSecretId("id");
    props.setBucket("bucket");
    props.setRegion("ap-beijing");
    props.setDomain("https://bucket.cos.ap-beijing.myqcloud.com");

    assertThat(new CosReportImageStorage(props).available()).isFalse();
  }
}
