package connectors.opentsdb;

import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Created by kamir on 29.06.17.
 */
public class OpenTSDBEvent implements Cloneable {

    public String timestamp; // ZEIT
    public String value;  // WERT
    public String metric; // SIGNALNAME  // TEMP
    // SENSOR: SENS_ID
    // CAR: CAR_ID
    public Map<String, String> tags = new HashMap<String, String>();
    transient Gson gson = new Gson();

    public OpenTSDBEvent(String eventBody) {
        super();

        System.out.println("> Parse flume-event body to generate an OpenTSDBEvent body ... ");
    }

    public OpenTSDBEvent() {
        super();
    }

    public String toJSON() {
        return gson.toJson(this, OpenTSDBEvent.class);
    }

    public OpenTSDBEvent clone() throws CloneNotSupportedException {
        return (OpenTSDBEvent) super.clone();
    }

    public String asTelnetPutLoad() {
        return metric + " " + timestamp + " " + value + " " + tagList();
    }

    private String tagList() {
        Set<String> l = tags.keySet();

        StringBuffer sb = new StringBuffer();
        for (String s : l) {

            sb.append(s + "=" + tags.get(s) + " ");

        }

        return sb.toString();
    }
}
