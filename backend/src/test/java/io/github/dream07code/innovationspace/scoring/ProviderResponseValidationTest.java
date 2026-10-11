package io.github.dream07code.innovationspace.scoring;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ProviderResponseValidationTest { @Test void providerScoreRejectsInvalidRangesAndReasons(){assertDoesNotThrow(()->new ProviderScore(1,"ok","mock","m").validate());assertThrows(IllegalArgumentException.class,()->new ProviderScore(4,"bad","mock","m").validate());assertThrows(IllegalArgumentException.class,()->new ProviderScore(2," ","mock","m").validate());}}
