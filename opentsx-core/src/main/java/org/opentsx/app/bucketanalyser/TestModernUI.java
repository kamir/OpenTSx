package org.opentsx.app.bucketanalyser;

import com.formdev.flatlaf.FlatDarkLaf;
import org.opentsx.generators.TSGeneratorFINAL;
import org.opentsx.data.series.TimeSeriesObject;
import javax.swing.*;
import java.awt.Color;
import java.util.Vector;

/**
 * Test launcher for ModernMacroTrackerFrame with real data integration.
 */
public class TestModernUI {

    private static ModernMacroTrackerFrame modernFrame;

    public static void main(String[] args) {
        // Initialize FlatLaf
        try {
            FlatDarkLaf.setup();

            UIManager.put("Button.arc", 8);
            UIManager.put("Component.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("ScrollBar.width", 12);
            UIManager.put("TabbedPane.selectedBackground", new Color(60, 63, 65));
            UIManager.put("Tree.rowHeight", 24);

            // Fix text colors
            UIManager.put("TextField.foreground", new Color(200, 200, 200));
            UIManager.put("TextField.background", new Color(69, 73, 74));
            UIManager.put("TextArea.foreground", new Color(200, 200, 200));
            UIManager.put("TextArea.background", new Color(43, 43, 43));
            UIManager.put("Tree.foreground", new Color(200, 200, 200));
            UIManager.put("Tree.background", new Color(43, 43, 43));
            UIManager.put("Label.foreground", new Color(200, 200, 200));

        } catch (Exception ex) {
            System.err.println("Failed to initialize FlatLaf: " + ex.getMessage());
        }

        // Generate sample data
        Vector<TimeSeriesObject> components = TSGeneratorFINAL.getSampleA();

        // Launch modern UI
        SwingUtilities.invokeLater(() -> {
            modernFrame = new ModernMacroTrackerFrame();
            modernFrame.setTrackName("EXP4 - Modern Design");
            modernFrame.setVisible(true);

            // Simulate adding transformations to show in tree
            modernFrame.updateStatus("Loaded " + components.size() + " time series");

            // Add sample transformation to tree
            addSampleTransformations();

            // Initialize PFD
            PFDRecorder.getInstance().reset("Modern Flow Test");
            PFDRecorder.getInstance().addStep("LOAD_DATA",
                    java.util.Map.of("source", "TSGeneratorFINAL", "components", components.size()));
            PFDRecorder.getInstance().addStep("CACHE",
                    java.util.Map.of("operation", "SIMPLE per Record Operation"));
        });
    }

    private static void addSampleTransformations() {
        // Build a sample tree structure
        javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode(
                "TSB-Transformation-TRACK");

        javax.swing.tree.DefaultMutableTreeNode cache = new javax.swing.tree.DefaultMutableTreeNode(
                "CACHE: Collection → Components");
        root.add(cache);

        javax.swing.tree.DefaultMutableTreeNode normalize = new javax.swing.tree.DefaultMutableTreeNode(
                "NORMALIZE: z-score");
        root.add(normalize);

        javax.swing.tree.DefaultMutableTreeNode filter = new javax.swing.tree.DefaultMutableTreeNode(
                "FILTER: threshold > 2.0");
        root.add(filter);

        // Set the tree model
        javax.swing.tree.DefaultTreeModel model = new javax.swing.tree.DefaultTreeModel(root);
        modernFrame.getTransformationTree().setModel(model);

        // Expand all nodes
        for (int i = 0; i < modernFrame.getTransformationTree().getRowCount(); i++) {
            modernFrame.getTransformationTree().expandRow(i);
        }
    }
}
