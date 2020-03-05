package udf;


import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Example class that demonstrates how to unit test UDFs.
 */
public class EpisodesProcessorTests {

  @ParameterizedTest(name = "descriptiveStats({0})= {1}")
  @CsvSource({
    "hello, 111.222",
    "world, 111.222",
  })
  void processEpisodeMessageFromString(final String source, final String expectedResult) {
    final EpisodesProcessor episodeProcessor = new EpisodesProcessor();


    final Double actualResult = episodeProcessor.statsFromString(source);
    assertEquals(expectedResult, actualResult.toString(), source + " processed result should equal " + expectedResult);
  }

}