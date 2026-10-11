package io.github.dream07code.innovationspace.scoring;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ScoringWorkerTest {@Test void providerFailuresAreClassifiedForRetry(){var e=new OpenAiCompatibleReportScoringProvider.ProviderScoreException("timeout",new RuntimeException());assertTrue(e.getMessage().contains("timeout"));}}
