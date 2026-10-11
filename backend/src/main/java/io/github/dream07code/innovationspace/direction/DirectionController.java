package io.github.dream07code.innovationspace.direction;

import io.github.dream07code.innovationspace.common.api.*;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/directions")
public class DirectionController {
  private final DirectionMapper mapper;

  public DirectionController(DirectionMapper mapper) {
    this.mapper = mapper;
  }

  private Map<String, Object> map(Map<String, Object> row) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", row.get("id").toString());
    m.put("code", row.get("code"));
    m.put("name", row.get("name"));
    m.put("enabled", row.get("enabled"));
    return m;
  }

  @GetMapping
  public ApiResponse<List<Map<String, Object>>> list() {
    return ApiResponse.success(mapper.list().stream().map(this::map).toList());
  }

  @GetMapping("/{directionId}")
  public ApiResponse<Map<String, Object>> get(@PathVariable long directionId) {
    var row = mapper.find(directionId);
    if (row == null) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return ApiResponse.success(map(row));
  }
}
