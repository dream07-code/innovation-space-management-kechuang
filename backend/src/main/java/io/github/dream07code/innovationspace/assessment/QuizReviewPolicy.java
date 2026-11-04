package io.github.dream07code.innovationspace.assessment;

/** 阅卷与成绩发布的状态判定，纯函数、无 IO。 */
public final class QuizReviewPolicy {

  private QuizReviewPolicy() {}

  /** 阅卷开放：考核已结束且成绩尚未发布。 */
  public static boolean canGrade(String status, boolean published) {
    if (published) return false;
    return "CLOSED".equals(status) || "ARCHIVED".equals(status);
  }

  /** 发布成绩：已关闭、未发布、且没有待人工评分的填空明细。 */
  public static boolean canPublish(String status, boolean published, int pendingItems) {
    return !published && "CLOSED".equals(status) && pendingItems == 0;
  }

  /** 学生是否可以看到分数与明细。 */
  public static boolean visibleToStudent(boolean published) {
    return published;
  }
}
