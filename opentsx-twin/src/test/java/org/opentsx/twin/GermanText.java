package org.opentsx.twin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds German in a text: umlauts and sharp s, and words that are German and not English. Identifiers that keep their
 * German names until the rename wave (file names such as {@code haus-erzgebirge.yaml}, atom and appliance ids) use none
 * of these words.
 */
public final class GermanText {

    private static final Set<String> WORDS = Set.of("und", "der", "das", "nicht", "mit", "ist", "ein", "eine", "einen",
            "einem", "einer", "auf", "bei", "wird", "werden", "wurde", "oder", "zum", "zur", "dem", "des", "sich",
            "auch", "nach", "wie", "von", "aus", "sind", "wenn", "dass", "kein", "keine", "noch", "nur", "unter", "bis",
            "jeweils", "bitte", "ohne", "gegen", "zwischen", "uhr", "wieder", "mehr", "weniger", "schon", "hier",
            "dann", "jede", "jeder", "jedes", "alle", "sowie", "im", "vom", "beim", "sie", "wir", "zeit", "modus",
            "landstrom", "notiz", "zeitraum", "strom", "holz", "kosten", "ende", "erzeugt", "siehe", "verbraucht",
            "leistung", "druck", "puffer", "ventil", "wasserstoff", "verdichter", "elektrolyseur", "leitung",
            "abnehmer", "fahrweise", "hebel", "verbrauch", "bedarf", "solaranteil", "vollzyklen", "ladezustand",
            "autonomie", "simuliert", "erfundene", "beispielwerte", "vorlage", "verdeckte", "lieferung", "abrechnung",
            "peilstab", "abgas", "heizung", "kessel", "zwilling", "windpark", "netz", "markt");

    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    /** Umlauts and sharp s. */
    private static final String UMLAUTS = "\u00e4\u00f6\u00fc\u00c4\u00d6\u00dc\u00df";

    private GermanText() {
    }

    /**
     * The German found in {@code text}, one entry per hit with its line, e.g. {@code "3: und"}. File names that keep
     * their German name are skipped: a word joined to its neighbour by '-', '/', '.' or '_' is part of a name or
     * path.
     */
    public static List<String> hits(String text) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (char c : UMLAUTS.toCharArray()) {
                if (line.indexOf(c) >= 0) {
                    out.add((i + 1) + ": " + c + " in " + line.strip());
                }
            }
            Matcher m = WORD.matcher(line);
            while (m.find()) {
                if (WORDS.contains(m.group().toLowerCase(Locale.ROOT)) && !joined(line, m.start(), m.end())) {
                    out.add((i + 1) + ": " + m.group() + " in " + line.strip());
                }
            }
        }
        return out;
    }

    private static boolean joined(String line, int start, int end) {
        return start > 0 && "-/._".indexOf(line.charAt(start - 1)) >= 0
                || end < line.length() && "-/._".indexOf(line.charAt(end)) >= 0;
    }
}
