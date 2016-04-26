/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.apache.hadoopts.app.bucketanalyser;

import org.apache.hadoopts.chart.simple.MultiChart;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import javax.swing.JDialog;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeSelectionModel;

/**
 *
 * @author kamir
 */
public class MacroTrackerFrame extends javax.swing.JFrame implements TreeSelectionListener {

    public static MacroTrackerFrame frame = null;

    public static void _registerDialogToNode(String string, javax.swing.JDialog dialog) {
        if ( frame != null )
            if( frame.track != null )
                frame.track._registerDialogToNode(string, dialog);
    }

    Track track = new Track();
    DefaultMutableTreeNode top = null;

    public static void init(String track) {

        frame = new MacroTrackerFrame();
        frame.jTextField1.setText(track);

        frame.setLocation(0, 0);
        frame.setVisible(true);

        frame.startRecording();
    }

    /**
     * Creates new form MacroTrackerFrame
     */
    public MacroTrackerFrame() {
        
        initComponents();

        top = new DefaultMutableTreeNode("TSB-Transformation-TRACK");
        DefaultTreeModel m = new DefaultTreeModel(top);
        this.jTree1.setModel(m);

        //Where the tree is initialized:
        this.jTree1.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        //Listen for when the selection changes.
        jTree1.addTreeSelectionListener(this);

        ToolTipManager.sharedInstance().registerComponent(jTree1);
        TreeCellRenderer renderer = new ToolTipTreeCellRenderer();
        jTree1.setCellRenderer(renderer);

    }

    public void refresh() {

        for (int i = 0; i < jTree1.getRowCount(); i++) {
            jTree1.expandRow(i);
        }

        frame.jTree1.updateUI();
        frame.repaint();

    }

    public static void addTransformation(TSBucketTransformation t) {
        System.out.println("ADDED A NEW TRANSFORMATION ... " + t.source + " ... " + t.target + " => " + t.operation + " Type: " + t.type);
        if ( frame != null ) {
            
            frame.track.addTransformation(t);
        
            frame.refresh();
        
        }
    }

    public static void addSource(TSBucketSource t) {
        System.out.println("ADDED A NEW SOURCE ... " + t.techSource + " ... " + t.hint + " => " + t.cacheFolder );
        frame.track.addSource(t);
        frame.refresh();
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jPanel1 = new javax.swing.JPanel();
        jSplitPane1 = new javax.swing.JSplitPane();
        jScrollPane1 = new javax.swing.JScrollPane();
        jTree1 = new javax.swing.JTree();
        jTabbedPane1 = new javax.swing.JTabbedPane();
        jPanel3 = new javax.swing.JPanel();
        jScrollPane2 = new javax.swing.JScrollPane();
        jTextArea1 = new javax.swing.JTextArea();
        jPanel4 = new javax.swing.JPanel();
        jScrollPane3 = new javax.swing.JScrollPane();
        jTextArea2 = new javax.swing.JTextArea();
        jPanel5 = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        jButton2 = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        jTextField1 = new javax.swing.JTextField();
        jLabel2 = new javax.swing.JLabel();
        jButton3 = new javax.swing.JButton();
        jButton1 = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        jPanel1.setLayout(new java.awt.BorderLayout());

        jSplitPane1.setDividerLocation(300);
        jSplitPane1.setDividerSize(5);

        jScrollPane1.setViewportView(jTree1);

        jSplitPane1.setLeftComponent(jScrollPane1);

        jTabbedPane1.setToolTipText("");

        jPanel3.setLayout(new java.awt.BorderLayout());

        jTextArea1.setColumns(20);
        jTextArea1.setRows(5);
        jScrollPane2.setViewportView(jTextArea1);

        jPanel3.add(jScrollPane2, java.awt.BorderLayout.CENTER);

        jTabbedPane1.addTab("Properties", jPanel3);

        jPanel4.setLayout(new java.awt.BorderLayout());

        jTextArea2.setColumns(20);
        jTextArea2.setRows(5);
        jTextArea2.setToolTipText("Scala code for loading this TSBucket ...");
        jScrollPane3.setViewportView(jTextArea2);

        jPanel4.add(jScrollPane3, java.awt.BorderLayout.CENTER);

        jTabbedPane1.addTab("Code", jPanel4);

        jPanel5.setToolTipText("Result based on Sample data ...");

        javax.swing.GroupLayout jPanel5Layout = new javax.swing.GroupLayout(jPanel5);
        jPanel5.setLayout(jPanel5Layout);
        jPanel5Layout.setHorizontalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 490, Short.MAX_VALUE)
        );
        jPanel5Layout.setVerticalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 348, Short.MAX_VALUE)
        );

        jTabbedPane1.addTab("Preview", jPanel5);

        jSplitPane1.setRightComponent(jTabbedPane1);

        jPanel1.add(jSplitPane1, java.awt.BorderLayout.CENTER);

        getContentPane().add(jPanel1, java.awt.BorderLayout.CENTER);

        jButton2.setText("Export code (Java)");
        jButton2.setToolTipText("Use generated code in you Java Program.");
        jButton2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton2ActionPerformed(evt);
            }
        });

        jLabel1.setText("Track:");

        jTextField1.setText("jTextField1");

        jLabel2.setBackground(new java.awt.Color(255, 255, 204));
        jLabel2.setForeground(new java.awt.Color(0, 153, 153));
        jLabel2.setText("...");

        jButton3.setText("show Chart");
        jButton3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton3ActionPerformed(evt);
            }
        });

        jButton1.setText("Export code (Scala)");
        jButton1.setToolTipText("Execute generated code in a Notepad ...");
        jButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addGroup(jPanel2Layout.createSequentialGroup()
                        .addGap(15, 15, 15)
                        .addComponent(jLabel1)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jTextField1))
                    .addGroup(jPanel2Layout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(jButton2)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jButton1)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel2Layout.createSequentialGroup()
                        .addComponent(jLabel2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addContainerGap())
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel2Layout.createSequentialGroup()
                        .addGap(0, 366, Short.MAX_VALUE)
                        .addComponent(jButton3))))
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jButton2)
                    .addComponent(jLabel2, javax.swing.GroupLayout.PREFERRED_SIZE, 29, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButton1))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(jLabel1)
                        .addComponent(jTextField1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(jButton3))
                .addContainerGap(71, Short.MAX_VALUE))
        );

        getContentPane().add(jPanel2, java.awt.BorderLayout.PAGE_START);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void jButton3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton3ActionPerformed
        String dialogKey = this.jLabel2.getText();
        frame.track.showDialog(dialogKey);
    }//GEN-LAST:event_jButton3ActionPerformed


    private void jButton2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton2ActionPerformed

        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) jTree1.getModel().getRoot();
        
        StringBuffer sb = new StringBuffer();
        
        Enumeration e = rootNode.preorderEnumeration();
        while (e.hasMoreElements()) {
            
            System.out.println();

            Object node = ((DefaultMutableTreeNode)e.nextElement()).getUserObject();

            boolean t = node instanceof ProcessComponent;
            // System.out.println(node + " is of type {ProcessComponent} " + t);

            if (t) {
                
                ProcessComponent c = (ProcessComponent) node;
                System.out.println( c.getCode() );
                sb.append( c.getCode() + "\n");
                
            }    
            
        }
        
        StringSelection selection = new StringSelection(sb.toString());
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(selection, selection);
       
         
//        e = rootNode.postorderEnumeration();
//        while (e.hasMoreElements()) {
//            System.out.println(e.nextElement());
//        }
//
//        e = rootNode.depthFirstEnumeration();
//        while (e.hasMoreElements()) {
//            System.out.println(e.nextElement());
//        }
 


    }//GEN-LAST:event_jButton2ActionPerformed

    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton1ActionPerformed

                DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) jTree1.getModel().getRoot();
        
        StringBuffer sb = new StringBuffer();
        
        Enumeration e = rootNode.preorderEnumeration();
        while (e.hasMoreElements()) {
            
            System.out.println();

            Object node = ((DefaultMutableTreeNode)e.nextElement()).getUserObject();

            boolean t = node instanceof ProcessComponent;
            // System.out.println(node + " is of type {ProcessComponent} " + t);

            if (t) {
                
                ProcessComponent c = (ProcessComponent) node;
                System.out.println( c.getCodeSCALA() );
                sb.append( c.getCode() + "\n");
                
            }    
            
        }
        
        StringSelection selection = new StringSelection(sb.toString());
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(selection, selection);
       

        
    }//GEN-LAST:event_jButton1ActionPerformed

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(MacroTrackerFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(MacroTrackerFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(MacroTrackerFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(MacroTrackerFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>
//
//        /* Create and display the form */
//        java.awt.EventQueue.invokeLater(new Runnable() {
//            public void run() {
        new MacroTrackerFrame().setVisible(true);
//            }
//        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton jButton1;
    private javax.swing.JButton jButton2;
    private javax.swing.JButton jButton3;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JScrollPane jScrollPane3;
    private javax.swing.JSplitPane jSplitPane1;
    private javax.swing.JTabbedPane jTabbedPane1;
    private javax.swing.JTextArea jTextArea1;
    private javax.swing.JTextArea jTextArea2;
    private javax.swing.JTextField jTextField1;
    private javax.swing.JTree jTree1;
    // End of variables declaration//GEN-END:variables

    private void startRecording() {
        track = new Track(this.jTextField1.getText(), top);
    }

    @Override
    public void valueChanged(TreeSelectionEvent e) {

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) jTree1.getLastSelectedPathComponent();

        if (node == null) //Nothing is selected.     
        {
            return;
        }

        Object nodeInfo = node.getUserObject();

        boolean t = nodeInfo instanceof ProcessComponent;
        System.out.println(nodeInfo + " is of type {ProcessComponent} " + t);

        if (t) {
            ProcessComponent c = (ProcessComponent) nodeInfo;
            showInfo(c.inspect());
            showCodeSnippet(c.getCode());

            this.jLabel2.setText(c.getDialogKey());

        }

    }

    private void showInfo(String i) {
        jTextArea1.setText(i);
    }

    private void showCodeSnippet(String c) {
        jTextArea2.setText(c);
    }

}

class Track {

    /**
     * The base folder for all tracks ...
     */
    public static String TSBASE = "/TSBASE";

    // this tracks default tree noode, to hook it into the tool.
    public DefaultMutableTreeNode top = null;

    // handle the items beside the tree in a separate structure.
    Hashtable<String, TSBucketTransformation> transforms = new Hashtable<String, TSBucketTransformation>();
    Hashtable<String, DefaultMutableTreeNode> nodes = new Hashtable<String, DefaultMutableTreeNode>();

    // this tracks name => maps to its directory in the TSBASE
    String label = null;

    // this tracke node which represents it in the tree 
    DefaultMutableTreeNode trackNode = null;

    public Track() {
    }

    public Track(String s, DefaultMutableTreeNode t) {
        label = s;
        top = t;
        createNodes();
    }

    void addTransformation(TSBucketTransformation tsbtf) {

        String sourceNode = tsbtf.source;
        addNode(sourceNode, tsbtf);

    }

    private void addNode(String source, TSBucketTransformation t) {

        Transform tr = new Transform(this, t.source, t.target, t.operation, t.type);

        DefaultMutableTreeNode trans = new DefaultMutableTreeNode(tr);

        DefaultMutableTreeNode s = nodes.get(source);
        if (s != null) {
            s.add(trans);
            System.out.println("[OK] " + s + " :: Node added to tree. (KEY:" + t.target + ")");
        } else {

            trackNode.add(trans);

            System.out.println(trackNode);
            System.out.println("{PROBLEM} " + trans);

        }

        nodes.put(t.target, trans);

    }

    /**
     * Initial node is "Collection" in track
     */
    private void createNodes() {

        DefaultMutableTreeNode source = null;

        trackNode = new DefaultMutableTreeNode("Track: " + label);
        top.add(trackNode);

//        source = new DefaultMutableTreeNode(new SourceTSB(this, "TRACK", "Collection"));
//        trackNode.add(source);
//
//        nodes.put("Collection", source);
    }

    void addSource(TSBucketSource t) {

        DefaultMutableTreeNode source = null;

        source = new DefaultMutableTreeNode(new SourceTSB(this, t.hint, t.techSource));
        trackNode.add(source);

        nodes.put(t.techSource, source);
    }

    Hashtable<String, JDialog> dialogs = new Hashtable<String, JDialog>();

    void _registerDialogToNode(String s, JDialog dialog) {

        dialogs.put(s, dialog);

    }

    void showDialog(String dialogKey) {
        
        Enumeration en = this.dialogs.keys();
        while( en.hasMoreElements() ) {
            System.out.println(">>> DIALOG: " + en.nextElement() );
        }
        
        System.out.println("OPEN WINDOW: " + dialogKey);
        dialogs.get(dialogKey).setVisible(true);
    }

}

interface ProcessComponent {

    public String inspect();

    public String getCode();
    
    public String getCodeSCALA();

    public String getDialogKey();

}

/**
 * Here we represent a node which is a source for a TSBucket.
 *
 * @author kamir
 */
class SourceTSB implements ProcessComponent {

    String type = null;
    
    String name = null;
    
    Track track = null;

    public SourceTSB(Track mytrack, String t, String n) {
        type = t;
        track = mytrack;
        name = n;
    }

    @Override
    public String toString() {
        return "SourceTSB{" + "type=" + type + "; name=" + name + '}';
    }

    @Override
    public String inspect() {
        File f = getTSBFile();
        return f.getAbsolutePath() + " => exists: " + f.exists();
    }

    @Override
    public String getCode() {
        File f = getTSBFile();
        return "TSBucket tsb" + name + " = TSBucket.loadViaContext( sc, \"" + f.getAbsolutePath() + "\" );";
    }
    
    @Override
    public String getCodeSCALA() {
        File f = getTSBFile();
        return "val rdd" + name + " = TSBucket.loadViaContext( sc, \"" + f.getAbsolutePath() + "\" );";
    }

    private File getTrackFolder() {
        File f = new File(Track.TSBASE + "/" + track.label );
        return f;
    }

    private File getTSBFile() {
        File f = new File(Track.TSBASE + "/" + track.label + "/" + name + ".tsb.vec.seq");
        return f;
    }

    @Override
    public String getDialogKey() {
        return "NO DIALOG for TSBSources available ...";
    }

}

/**
 * Here we represent a node which is a source for a TSBucket.
 *
 * @author kamir
 */
class Transform implements ProcessComponent {

    String type = null;
    String source = null;
    String target = null;
    String operation = null;

    String parameter = null;
    
    Track track = null;

    public Transform(Track mytrack, String s, String t, String op, String typ) {
        type = typ;
        target = t;
        source = s;
        operation = op;
        track = mytrack;
    }

    @Override
    public String toString() {
        return "Transform{" + type + ": " + operation + '}';
    }

    @Override
    public String inspect() {
        return "Transform: " + target + " = " + operation + "( " + source + " ) ";
    }

    @Override
    public String getCode() {
        return "TSBucket tsb" + clean(target) + " = tsb" + clean(source) + ".processBucket( \"" + clean(operation).toUpperCase() + "\", " + parameter + " );";
    }
    
    @Override
    public String getCodeSCALA() {
        return "val rdd" + clean(target) + " = rdd" + clean(source) + ".processBucket( \"" + clean(operation).toUpperCase() + "\", " + parameter + " );";
    }

    
    public String clean(String s) {
        return s.replace( ' ', '_' );
    }

    @Override
    public String getDialogKey() {
        return target;
    }

}

class ToolTipTreeCellRenderer implements TreeCellRenderer {

    DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();

    public ToolTipTreeCellRenderer() {
    }

    public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean selected, boolean expanded, boolean leaf, int row,
            boolean hasFocus) {
        renderer.getTreeCellRendererComponent(tree, value, selected, expanded,
                leaf, row, hasFocus);
        if (value != null) {
            Object tip;
            if (value instanceof DefaultMutableTreeNode) {
                tip = ((DefaultMutableTreeNode) value).getUserObject();

            } else {
                tip = tree.convertValueToText(value, selected, expanded,
                        leaf, row, hasFocus);
            }

            if (tip != null) {
                renderer.setToolTipText(tip.toString());
            } else {
                renderer.setToolTipText(null);
            }
        }
        return renderer;
    }
    
    
    ICorrelator corr = null;
    
    public void addCorrelationTool( ICorrelator correlator ) {
        corr = correlator;
    }
}
