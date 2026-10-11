package io.github.dream07code.innovationspace.points;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.report")
public class PointsPolicyProperties implements InitializingBean {
  private String pointsPolicyStatus = "PENDING_CONFIRMATION";
  private String pointsMode = "FINAL_SCORE";
  private int maxFinalScore = 3;

  public String getPointsPolicyStatus() { return pointsPolicyStatus; }
  public void setPointsPolicyStatus(String value) { pointsPolicyStatus = value; }
  public String getPointsMode() { return pointsMode; }
  public void setPointsMode(String value) { pointsMode = value; }
  public int getMaxFinalScore() { return maxFinalScore; }
  public void setMaxFinalScore(int value) { maxFinalScore = value; }

  public void validate() {
    if (!"PENDING_CONFIRMATION".equals(pointsPolicyStatus) && !"READY".equals(pointsPolicyStatus))
      throw new IllegalArgumentException("Invalid report points policy status");
    if (!"FINAL_SCORE".equals(pointsMode) && !"BASE_PLUS_AI".equals(pointsMode))
      throw new IllegalArgumentException("Unsupported report points mode");
    if (maxFinalScore < 1 || maxFinalScore > 4)
      throw new IllegalArgumentException("Invalid maximum final score");
  }

  @Override
  public void afterPropertiesSet() {
    validate();
  }
}
