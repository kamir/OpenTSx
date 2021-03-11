import com.google.gson.Gson;

import org.apache.commons.text.CharacterPredicates;
import org.apache.commons.text.RandomStringGenerator;

public class LatencyTesterEvent {

    long creationTS = 0;
    long sendTS = 0;
    long receivedTS = 0;
    long processStartedTS = 0;
    long processFinishedTS = 0;
    long resultShippedTS = 0;
    long resultReceivedTS = 0;
    String targetAgentID = "agent2";
    String targetAgentDC = "DC2";
    String PL = "1234567890";

    public static LatencyTesterEvent getTestEvent(String targetAgentDC, String targetAgentID, int size  ) {
        LatencyTesterEvent e = new LatencyTesterEvent();
        e.creationTS = System.currentTimeMillis();
        e.targetAgentDC = targetAgentDC;
        e.targetAgentID = targetAgentID;
        e.PL = randomStringGenerator.generate( size );
        return e;
    }

    static Gson gson = new Gson();
    public static String asJson(LatencyTesterEvent EVENT) {
        return gson.toJson( EVENT );
    }

    static RandomStringGenerator randomStringGenerator =
            new RandomStringGenerator.Builder()
                    .withinRange('0', 'z')
                    .filteredBy(CharacterPredicates.LETTERS, CharacterPredicates.DIGITS)
                    .build();

    public void trackSendTS() { this.sendTS = System.currentTimeMillis(); };
    public void trackReceivedTS() { this.receivedTS = System.currentTimeMillis(); };
    public void trackProcessStartedTS() { this.processStartedTS = System.currentTimeMillis(); };
    public void trackProcessFinishedTS() { this.processFinishedTS = System.currentTimeMillis(); };
    public void trackResultShippedTS() { this.resultShippedTS = System.currentTimeMillis(); };
    public void trackResultReceivedTS() { this.resultReceivedTS = System.currentTimeMillis(); };

}
