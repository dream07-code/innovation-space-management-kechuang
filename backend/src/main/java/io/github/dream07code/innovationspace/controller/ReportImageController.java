package io.github.dream07code.innovationspace.controller;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.service.ReportImageService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 日报/周报笔记图片上传。学生只传自己的图片，URL 由服务端拼好返回。 */
@RestController
public class ReportImageController {
  private final ReportImageService service;

  public ReportImageController(ReportImageService service) {
    this.service = service;
  }

  @PostMapping(value = "/api/v1/report-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<Map<String, Object>> upload(@RequestPart("file") MultipartFile file) {
    return ApiResponse.success(service.upload(file, MemberController.actor()));
  }
}
