package io.github.dream07code.innovationspace.points;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.member.MemberController;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class PointsController {
  private final PointLedgerService points;
  private final RankingService rankings;

  public PointsController(PointLedgerService points, RankingService rankings) {
    this.points = points;
    this.rankings = rankings;
  }

  @GetMapping("/api/v1/points/me")
  public ApiResponse<Map<String, Object>> me(@RequestParam(required = false) String month) {
    SessionPrincipal principal = MemberController.actor();
    return ApiResponse.success(points.summary(principal.memberId(), month));
  }

  @GetMapping("/api/v1/points/ledger")
  public ApiResponse<Map<String, Object>> ledger(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Long memberId,
      @RequestParam(required = false) String month) {
    return ApiResponse.success(points.ledger(MemberController.actor(), memberId, month, page, size));
  }

  @GetMapping("/api/v1/rankings/overall")
  public ApiResponse<Map<String, Object>> overall(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "ALL_TIME") String period,
      @RequestParam(required = false) String month) {
    return ApiResponse.success(rankings.overall(page, size, period, month));
  }

  @GetMapping("/api/v1/rankings/directions/{directionId}")
  public ApiResponse<Map<String, Object>> direction(
      @PathVariable long directionId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "ALL_TIME") String period,
      @RequestParam(required = false) String month) {
    return ApiResponse.success(rankings.direction(directionId, page, size, period, month));
  }

  @GetMapping("/api/v1/rankings/me")
  public ApiResponse<Map<String, Object>> rankingMe(
      @RequestParam(defaultValue = "ALL_TIME") String period,
      @RequestParam(required = false) String month) {
    return ApiResponse.success(rankings.me(MemberController.actor(), period, month));
  }
}
