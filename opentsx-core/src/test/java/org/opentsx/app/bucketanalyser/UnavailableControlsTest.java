package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UnavailableControlsTest {

    @TempDir
    Path home;

    @ParameterizedTest
    @ValueSource(strings = {"AR", "MA", "ARMA", "ARIMA", "SARIMA", "CARIMA"})
    void unavailableAnalysisCannotBeClicked(String label) throws Exception {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            SwingUtilities.invokeAndWait(() -> {
                JButton button = findButton(new TSOperationControlerPanel(), label);
                assertFalse(button.isEnabled());
                assertEquals("This operation is not implemented", button.getToolTipText());
            });
        } finally {
            System.setProperty("user.home", original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Export Java", "Export Scala"})
    void unavailableExportsCannotBeClicked(String label) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ModernMacroTrackerFrame frame = new ModernMacroTrackerFrame();
            try {
                JButton button = findButton(frame.getContentPane(), label);
                assertFalse(button.isEnabled());
                assertEquals("This export is not implemented", button.getToolTipText());
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void pfdButtonDescribesItsDisplayAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ModernMacroTrackerFrame frame = new ModernMacroTrackerFrame();
            try {
                JButton button = findButton(frame.getContentPane(), "Show PFD");
                assertTrue(button.isEnabled());
                assertEquals(1, button.getActionListeners().length);
            } finally {
                frame.dispose();
            }
        });
    }

    private JButton findButton(Container parent, String label) {
        JButton button = searchButton(parent, label);
        assertNotNull(button, "Missing button: " + label);
        return button;
    }

    private JButton searchButton(Container parent, String label) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && label.equals(button.getText())) return button;
            if (child instanceof Container container) {
                JButton found = searchButton(container, label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
