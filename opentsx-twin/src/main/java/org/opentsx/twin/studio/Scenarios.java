package org.opentsx.twin.studio;

import java.util.List;

/** The scenarios the Studio export knows. */
public final class Scenarios {

    private Scenarios() {
    }

    public static List<StudioScenario> all() {
        return List.of(new H2ChainScenario());
    }
}
