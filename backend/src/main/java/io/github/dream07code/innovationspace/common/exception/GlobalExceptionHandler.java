package io.github.dream07code.innovationspace.common.exception;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(io.github.dream07code.innovationspace.config.SchemaUnavailableException.class)
  public ResponseEntity<ApiResponse<Void>> schemaUnavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.failure(ErrorCode.SERVICE_UNAVAILABLE));
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiResponse<Void>> apiException(ApiException exception) {
    var builder =
        ResponseEntity.status(exception.status())
            .cacheControl(org.springframework.http.CacheControl.noStore());
    if (exception.status() == 429) builder.header("Retry-After", "60");
    return builder.body(ApiResponse.failure(exception.code()));
  }

  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  public ResponseEntity<ApiResponse<Void>> duplicateKey(
      org.springframework.dao.DuplicateKeyException exception) {
    String cause = String.valueOf(exception.getMostSpecificCause()).toLowerCase(java.util.Locale.ROOT);
    ErrorCode error =
        cause.contains("uk_app_user") || cause.contains("member")
            ? ErrorCode.ACCOUNT_ALREADY_EXISTS
            : cause.contains("uk_report") || cause.contains("report")
                ? ErrorCode.REPORT_ALREADY_EXISTS
                : ErrorCode.INVALID_STATE;
    return ResponseEntity.status(409).body(ApiResponse.failure(error));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ErrorCode error =
        switch (statusCode.value()) {
          case 404 -> ErrorCode.NOT_FOUND;
          case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
          default ->
              statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
    return super.handleExceptionInternal(
        exception, ApiResponse.failure(error), headers, statusCode, request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
    // Exception messages can contain input values; keep the type and frames only.
    LOG.error(
        "Unhandled exception type={} stack={}",
        exception.getClass().getName(),
        Arrays.toString(exception.getStackTrace()));
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.failure(ErrorCode.INTERNAL_ERROR));
  }
}
