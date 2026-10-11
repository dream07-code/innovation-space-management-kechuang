package io.github.dream07code.innovationspace.direction;
import io.github.dream07code.innovationspace.common.api.*;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/directions")
public class DirectionController {
 private final JdbcTemplate jdbc;public DirectionController(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private Map<String,Object> map(Map<String,Object> row){var m=new LinkedHashMap<String,Object>();m.put("id",row.get("id").toString());m.put("code",row.get("code"));m.put("name",row.get("name"));m.put("enabled",row.get("enabled"));return m;}
 @GetMapping public ApiResponse<List<Map<String,Object>>> list(){return ApiResponse.success(jdbc.queryForList("SELECT * FROM direction ORDER BY id").stream().map(this::map).toList());}
 @GetMapping("/{directionId}") public ApiResponse<Map<String,Object>> get(@PathVariable long directionId){var rows=jdbc.queryForList("SELECT * FROM direction WHERE id=?",directionId);if(rows.isEmpty())throw new ApiException(404,ErrorCode.NOT_FOUND);return ApiResponse.success(map(rows.getFirst()));}
}
