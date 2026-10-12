package org.opentsx.dwd;

/**
 * A DWD station. Coordinates in decimal degrees (WGS84), elevation in metres.
 *
 * @param id        MOSMIX id (5 characters, e.g. {@code 10578}, {@code P182}) or CDC id ({@code 01358})
 * @param name      station name as published
 * @param latitude  decimal degrees north
 * @param longitude decimal degrees east
 * @param elevation metres above sea level
 * @param catalog   {@code mosmix} or {@code cdc}
 */
public record Station(String id, String name, double latitude, double longitude, double elevation, String catalog) {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** Great-circle distance in km (haversine, mean earth radius). */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public double distanceKm(double lat, double lon) {
        return distanceKm(latitude, longitude, lat, lon);
    }
}
