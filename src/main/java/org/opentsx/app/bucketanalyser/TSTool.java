/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.app.bucketanalyser;

import javax.swing.JButton;

/**
 *
 * @author kamir
 */
interface TSTool {
    
    public JButton getButton();
    public void showParameters();
    public void execute();
    
}
