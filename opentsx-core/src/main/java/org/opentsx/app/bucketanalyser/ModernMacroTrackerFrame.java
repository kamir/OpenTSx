package org.opentsx.app.bucketanalyser;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Modern styled frame for the Macro Tracker with enhanced UI.
 */
public class ModernMacroTrackerFrame extends JFrame {

    private JTree transformationTree;
    private JTextArea propertiesArea;
    private JTextArea codeArea;
    private JTextArea pfdArea;
    private JTextField trackNameField;
    private JLabel statusLabel;

    public ModernMacroTrackerFrame() {
        initializeModernUI();
    }

    private void initializeModernUI() {
        setTitle("OpenTSx - Processing Flow Designer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 900);
        setLocationRelativeTo(null);

        // Main container with padding
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        mainPanel.setBackground(new Color(43, 43, 43));

        // Top toolbar
        mainPanel.add(createToolbar(), BorderLayout.NORTH);

        // Center: Split pane
        JSplitPane splitPane = createMainSplitPane();
        mainPanel.add(splitPane, BorderLayout.CENTER);

        // Bottom status bar
        mainPanel.add(createStatusBar(), BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(10, 0));
        toolbar.setBackground(new Color(60, 63, 65));
        toolbar.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Left side - Track info
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftPanel.setOpaque(false);

        JLabel trackLabel = new JLabel("Track:");
        trackLabel.setForeground(Color.WHITE);
        trackLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));

        trackNameField = new JTextField(20);
        trackNameField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));

        leftPanel.add(trackLabel);
        leftPanel.add(trackNameField);

        // Right side - Action buttons
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightPanel.setOpaque(false);

        JButton exportJavaBtn = createStyledButton("Export Java", new Color(75, 110, 175));
        JButton exportScalaBtn = createStyledButton("Export Scala", new Color(220, 50, 47));
        JButton exportPFDBtn = createStyledButton("Export PFD", new Color(38, 139, 210));

        exportPFDBtn.addActionListener(e -> refreshPFD());

        rightPanel.add(exportJavaBtn);
        rightPanel.add(exportScalaBtn);
        rightPanel.add(exportPFDBtn);

        toolbar.add(leftPanel, BorderLayout.WEST);
        toolbar.add(rightPanel, BorderLayout.EAST);

        return toolbar;
    }

    private JButton createStyledButton(String text, Color accentColor) {
        JButton button = new JButton(text);
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        button.setForeground(Color.WHITE);
        button.setBackground(accentColor);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setBorder(new EmptyBorder(8, 16, 8, 16));

        // Hover effect
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setBackground(accentColor.brighter());
            }

            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setBackground(accentColor);
            }
        });

        return button;
    }

    private JSplitPane createMainSplitPane() {
        // Left: Tree view
        JPanel treePanel = createTreePanel();

        // Right: Tabbed pane
        JTabbedPane tabbedPane = createTabbedPane();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, tabbedPane);
        splitPane.setDividerLocation(350);
        splitPane.setDividerSize(2);
        splitPane.setBorder(null);

        return splitPane;
    }

    private JPanel createTreePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(new Color(43, 43, 43));
        panel.setBorder(new EmptyBorder(0, 0, 0, 10));

        JLabel header = new JLabel("Transformation Flow");
        header.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        header.setForeground(new Color(200, 200, 200)); // Lighter
        header.setBorder(new EmptyBorder(0, 0, 10, 0));

        transformationTree = new JTree();
        transformationTree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        transformationTree.setRowHeight(24);

        JScrollPane scrollPane = new JScrollPane(transformationTree);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(60, 63, 65)));

        panel.add(header, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JTabbedPane createTabbedPane() {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        // Properties tab
        propertiesArea = createStyledTextArea();
        tabbedPane.addTab("Properties", createScrollablePanel(propertiesArea));

        // Code tab
        codeArea = createStyledTextArea();
        codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        tabbedPane.addTab("Code", createScrollablePanel(codeArea));

        // PFD tab
        pfdArea = createStyledTextArea();
        pfdArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JPanel pfdPanel = createPFDPanel();
        tabbedPane.addTab("Flow (JSON)", pfdPanel);

        // Preview tab
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBackground(new Color(43, 43, 43));
        JLabel previewLabel = new JLabel("Chart preview will appear here", SwingConstants.CENTER);
        previewLabel.setForeground(new Color(128, 128, 128));
        previewPanel.add(previewLabel, BorderLayout.CENTER);
        tabbedPane.addTab("Preview", previewPanel);

        return tabbedPane;
    }

    private JTextArea createStyledTextArea() {
        JTextArea textArea = new JTextArea();
        // Use standard monospaced font that works everywhere
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textArea.setTabSize(2);
        textArea.setLineWrap(false);
        textArea.setEditable(false);

        // Fix colors for dark theme
        textArea.setBackground(new Color(43, 43, 43));
        textArea.setForeground(new Color(187, 187, 187)); // Light gray text
        textArea.setCaretColor(new Color(187, 187, 187));
        textArea.setSelectionColor(new Color(75, 110, 175));

        return textArea;
    }

    private JScrollPane createScrollablePanel(JTextArea textArea) {
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(new EmptyBorder(10, 10, 10, 10));
        return scrollPane;
    }

    private JPanel createPFDPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JButton refreshBtn = createStyledButton("Refresh JSON", new Color(38, 139, 210));
        refreshBtn.addActionListener(e -> refreshPFD());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setOpaque(false);
        buttonPanel.add(refreshBtn);

        JScrollPane scrollPane = new JScrollPane(pfdArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(60, 63, 65)));

        panel.add(buttonPanel, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(new Color(60, 63, 65));
        statusBar.setBorder(new EmptyBorder(8, 10, 8, 10));

        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(new Color(187, 187, 187));
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

        JLabel versionLabel = new JLabel("OpenTSx v3.0.0 | Task-005");
        versionLabel.setForeground(new Color(128, 128, 128));
        versionLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(versionLabel, BorderLayout.EAST);

        return statusBar;
    }

    private void refreshPFD() {
        String json = PFDRecorder.getInstance().toJSON();
        pfdArea.setText(json);
        statusLabel.setText("PFD refreshed at " + new java.util.Date());
    }

    public void setTrackName(String name) {
        trackNameField.setText(name);
    }

    public void updateStatus(String message) {
        statusLabel.setText(message);
    }

    public JTree getTransformationTree() {
        return transformationTree;
    }

    public JTextArea getPropertiesArea() {
        return propertiesArea;
    }

    public JTextArea getCodeArea() {
        return codeArea;
    }

    public static void main(String[] args) {
        // Set FlatLaf theme
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            ModernMacroTrackerFrame frame = new ModernMacroTrackerFrame();
            frame.setVisible(true);
        });
    }
}
