package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void classicPfdButtonDescribesItsDisplayAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MacroTrackerFrame frame = new MacroTrackerFrame();
            try {
                JButton button = findButton(frame.getContentPane(), "Show PFD");
                assertTrue(button.isEnabled());
                assertEquals(1, button.getActionListeners().length);
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void modernTrackerDoesNotDisplaySampleTreeItems() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ModernMacroTrackerFrame frame = new ModernMacroTrackerFrame();
            try {
                assertNull(frame.getTransformationTree().getModel().getRoot());
            } finally {
                frame.dispose();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistControlCannotDispatchActions(boolean populated) throws Exception {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            SwingUtilities.invokeAndWait(() -> {
                TimeSeriesObject series = new TimeSeriesObject();
                series.addValuePair(3.0, 7.0);
                Vector<TimeSeriesObject> rows = new Vector<>();
                rows.add(series);
                TSOperationControlerPanel panel = populated
                        ? new TSOperationControlerPanel(rows, null, "fixture", new JTextArea())
                        : new TSOperationControlerPanel();
                JButton button = findButton(panel, "persist TSB");
                AtomicInteger actions = new AtomicInteger();
                button.addActionListener(event -> actions.incrementAndGet());
                button.doClick(0);
                assertAll(
                        () -> assertFalse(button.isEnabled()),
                        () -> assertEquals("This operation is not implemented", button.getToolTipText()),
                        () -> assertEquals(0, actions.get()));
            });
        } finally {
            if (original == null) System.clearProperty("user.home");
            else System.setProperty("user.home", original);
        }
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
