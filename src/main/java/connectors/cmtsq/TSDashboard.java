package connectors.cmtsq;

import com.google.gson.Gson;

/**
 * Created by kamir on 24.07.17.
 */
public class TSDashboard {

    String name = null;
    String owner = null;

    public TSDashboard() {
    }

    public static TSDashboard createTSDashboard_from_JSON(String json) {
        Gson gson = new Gson();
        TSDashboard q = gson.fromJson( json, TSDashboard.class );
        return q;
    }

    public String toString() {
        return "TS Dashboard:" + name + " [" + owner + "]";
    }



    public TSDashboard(String n) {
        name = n;
    }

}
