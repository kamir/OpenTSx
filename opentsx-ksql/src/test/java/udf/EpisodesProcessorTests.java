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
        "hello, 123.456",
      })
      void processEpisodeMessageFromString(final String source, final String expectedResult) {
          final EpisodesProcessor episodeProcessor = new EpisodesProcessor();

          final Double actualResult = episodeProcessor.statsFromString(source);
          assertEquals(expectedResult, actualResult.toString(), source + " processed result should equal " + expectedResult);
      }

      /*
      @ParameterizedTest(name = "descriptiveStats({0})={1}")
      @CsvSource({
                "110 120 1, 10.0",
      })
      void extractEpisodeMD(final String source, final String expectedResult) {

          final EpisodesMetadataExtractor episodeProcessor = new EpisodesMetadataExtractor();

          String[] DATA = source.split(" ");

          final Double actualResult = episodeProcessor.extractMD_nr_of_values( Long.parseLong( DATA[0] ), Long.parseLong( DATA[1] ), Integer.parseInt( DATA[2] ) );

          assertEquals(expectedResult, actualResult.toString(), source + " processed result should equal " + expectedResult);

      }
      */


}