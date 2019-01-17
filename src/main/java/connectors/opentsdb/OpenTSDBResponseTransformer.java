package connectors.opentsdb;

import com.jayway.jsonpath.JsonPath;
import org.apache.hadoopts.data.series.TimeSeriesObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

/**
 * Created by kamir on 07.08.17.
 */
public class OpenTSDBResponseTransformer {

    /**
     * https://github.com/json-path/JsonPath
     *
     * @param data
     * @return
     */
    public static TimeSeriesObject getMessreiheForJSONResponse(String data) {

        java.util.LinkedHashMap map = (java.util.LinkedHashMap) JsonPath.read(data, "$[0].dps");
        String metric = (String) JsonPath.read(data, "$[0].metric");


        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(metric);


        Set s = map.keySet();

        ArrayList al = new ArrayList(s);

        Collections.sort(al);

        Iterator i = al.iterator();

        while (i.hasNext()) {
            String keyS = (String) i.next();

            Object value = map.get(keyS);
            Double v = null;

            if (value instanceof java.lang.Double) {
                v = (Double) value;
            } else {
                BigDecimal bdv = (BigDecimal) value;
                v = bdv.doubleValue();

            }

            // System.out.println( keyS + "=>" + value.getClass() );


            /////// PROBLEM WITH MULTIPLE POINTS AT THE SAME TIME !!!!
            mr.addValuePair(Double.parseDouble(keyS), v);
        }


        return mr;
    }
}
