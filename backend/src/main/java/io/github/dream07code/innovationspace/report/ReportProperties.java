package io.github.dream07code.innovationspace.report;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("app.report")
public class ReportProperties { private String pointsPolicyStatus="PENDING_CONFIRMATION"; private int dailyBasePoints=1; private int weeklyBasePoints=1;
 public String getPointsPolicyStatus(){return pointsPolicyStatus;} public void setPointsPolicyStatus(String v){pointsPolicyStatus=v;}
 public int getDailyBasePoints(){return dailyBasePoints;} public void setDailyBasePoints(int v){dailyBasePoints=v;}
 public int getWeeklyBasePoints(){return weeklyBasePoints;} public void setWeeklyBasePoints(int v){weeklyBasePoints=v;}
}
