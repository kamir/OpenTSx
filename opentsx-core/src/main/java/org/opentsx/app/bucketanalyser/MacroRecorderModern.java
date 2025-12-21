package org.opentsx.app.bucketanalyser;

import com.formdev.flatlaf.FlatDarkLaf;
import org.opentsx.generators.TSGeneratorFINAL;
import org.opentsx.data.series.TimeSeriesObject;
import javax.swing.*;
import java.awt.Color;
import java.util.Vector;

/**
 * Launcher for the modern MacroRecorder UI with fixed text colors.
 */
public class MacroRecorderModern {

    public static void main(String[] args) {
        // Initialize FlatLaf with custom properties
        try {
            FlatDarkLaf.setup();

            // Enhanced UI properties for modern look
            UIManager.put("Button.arc", 8);
            UIManager.put("Component.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("ScrollBar.width", 12);
            UIManager.put("TabbedPane.selectedBackground", new Color(60, 63, 65));
            UIManager.put("Tree.rowHeight", 24);

            // CRITICAL FIX: Set explicit colors for ALL text components
            UIManager.put("TextField.foreground", new Color(200, 200, 200));
            UIManager.put("TextField.background", new Color(69, 73, 74));
            UIManager.put("TextField.caretForeground", new Color(200, 200, 200));
            UIManager.put("TextArea.foreground", new Color(200, 200, 200));
            UIManager.put("TextArea.background", new Color(43, 43, 43));
            UIManager.put("Tree.foreground", new Color(200, 200, 200));
            UIManager.put("Tree.background", new Color(43, 43, 43));
            UIManager.put("Label.foreground", new Color(200, 200, 200));
            UIManager.put("TabbedPane.foreground", new Color(200, 200, 200));
            UIManager.put("Button.foreground", new Color(255, 255, 255));

        } catch (Exception ex) {
            System.err.println("Failed to initialize FlatLaf: " + ex.getMessage());
        }

        // Generate sample data
        Vector<TimeSeriesObject> components = TSGeneratorFINAL.getSampleA();

        // Launch modern UI
        SwingUtilities.invokeLater(() -> {
            ModernMacroTrackerFrame frame = new ModernMacroTrackerFrame();
            frame.setTrackName("EXP4");
            frame.setVisible(true);
            frame.updateStatus("Sample data loaded: " + components.size() + " time series components");

            // Initialize PFD recorder
            PFDRecorder.getInstance().reset("Sample Flow");
            PFDRecorder.getInstance().addStep("LOAD_DATA",
                    java.util.Map.of("source", "TSGeneratorFINAL", "components", components.size()));
        });
    }
}
