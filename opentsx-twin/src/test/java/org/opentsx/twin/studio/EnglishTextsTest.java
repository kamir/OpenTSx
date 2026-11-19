package org.opentsx.twin.studio;

import org.junit.jupiter.api.Test;
import org.opentsx.twin.GermanText;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the Studio, the run API and the bundled files show a person is English. */
class EnglishTextsTest {

    @Test
    void everyScenarioTextAndGuideIsEnglish() {
        List<String> hits = new ArrayList<>();
        for (StudioScenario s : Scenarios.all()) {
            List<String> texts = new ArrayList<>(List.of(s.title(), s.description(), s.decision()));
            s.alternatives().forEach(a -> texts.add(a.name()));
            s.forecasts().forEach(f -> texts.add(f.name()));
            s.kpis().forEach(k -> texts.add(k.label()));
            texts.addAll(s.labels().values());
            s.limits().values().forEach(l -> l.forEach(x -> texts.add(x.label())));
            strings(s.guide().content(), texts);
            for (String t : texts) {
                GermanText.hits(t).forEach(h -> hits.add(s.id() + " " + h));
            }
        }
        assertEquals(List.of(), hits);
    }

    @Test
    void theBundledReadmesTemplatesPlansAndGuidesAreEnglish() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String name : List.of("twin/beispiel/README.md", "twin/beispiel/README-boot.md",
                "twin/abrechnungen-vorlage.csv", "twin/bootsdaten-vorlage.csv", "twin/h2-chain.guide.yaml",
                "twin/haus-erzgebirge.guide.yaml", "twin/inventar-beispiel.yaml", "twin/h2-chain.yaml",
                "twin/haus-erzgebirge.yaml", "twin/boot-etap22.yaml")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                GermanText.hits(new String(in.readAllBytes(), StandardCharsets.UTF_8)).forEach(h -> hits.add(name + ":" + h));
            }
        }
        assertEquals(List.of(), hits);
    }

    private static void strings(Object node, List<String> out) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                out.add(String.valueOf(k));
                strings(v, out);
            });
        } else if (node instanceof List<?> l) {
            l.forEach(v -> strings(v, out));
        } else if (node instanceof String s) {
            out.add(s);
        }
    }
}
