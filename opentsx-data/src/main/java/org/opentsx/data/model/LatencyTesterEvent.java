package org.opentsx.data.model;

import com.google.gson.Gson;

import org.apache.commons.text.CharacterPredicates;
import org.apache.commons.text.RandomStringGenerator;

public class LatencyTesterEvent {

    public long creationTS = 0;
    public long sendTS = 0;
    public long receivedTS = 0;
    public long processStartedTS = 0;
    public long processFinishedTS = 0;
    public long resultShippedTS = 0;
    public long resultReceivedTS = 0;
    public String targetAgentID = "agent2";
    public String targetAgentDC = "DC2";
    public String PL = "1234567890";

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

    public static LatencyTesterEvent fromJson(String value) {
        return gson.fromJson( value, LatencyTesterEvent.class );
    }

    public void trackSendTS() { this.sendTS = System.currentTimeMillis(); };
    public void trackReceivedTS() { this.receivedTS = System.currentTimeMillis(); };
    public void trackProcessStartedTS() { this.processStartedTS = System.currentTimeMillis(); };
    public void trackProcessFinishedTS() { this.processFinishedTS = System.currentTimeMillis(); };
    public void trackResultShippedTS() { this.resultShippedTS = System.currentTimeMillis(); };
    public void trackResultReceivedTS() { this.resultReceivedTS = System.currentTimeMillis(); };

    public String getStats() {
        StringBuffer sb = new StringBuffer();
        long requestTravelTime = this.receivedTS - this.sendTS;
        long processingTime = this.processFinishedTS - this.processStartedTS;
        long responseTravelTime = this.resultReceivedTS - this.resultShippedTS;
        sb.append( "REQUEST_TT  :" + requestTravelTime + " ms; " );
        sb.append( "RESPONSE_TT :" + responseTravelTime + " ms; " );
        sb.append( "Processing  :" + processingTime + " ms; " );
        return sb.toString();
    }

    public long getEnd2EndLatency() {
        return this.resultReceivedTS - this.sendTS;
    };
}
