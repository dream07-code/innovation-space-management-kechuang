package io.github.dream07code.innovationspace.assessment;
public record OjSubmission(String language, String code) {
  public OjSubmission {
    if (language == null || language.isBlank() || code == null || code.isBlank())
      throw new IllegalArgumentException("language and code are required");
    if (code.length() > 200000) throw new IllegalArgumentException("code is too large");
  }
}
