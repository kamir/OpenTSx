/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.cloudera.hts.utils.math;

import java.util.ArrayList;
import java.util.Arrays;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoint;
import org.apache.commons.math3.fitting.WeightedObservedPoints;

/**
 *
 * @author kamir
 */
public class PolyFit {
    
    public static void main(String[] args) {

        double[] x = {-5.0,	-4.0,	-3.0,	-2.0,	0,	2,	3,	4};
        double[] y = {21,	13,	7,	3,	1,	7,	13,	21};
        
        WeightedObservedPoints obs = new WeightedObservedPoints();

        // Add points here; for instance,
        int i = 0;
        for( double xc : x ) {
        
            WeightedObservedPoint point = new WeightedObservedPoint( xc, y[i], 1.0);
            obs.add( xc, y[i] );

            System.out.println( xc + " " + y[i] );

            i++;
            
        }
        
        // Instantiate a third-degree polynomial fitter.
        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(2);

        // Retrieve fitted parameters (coefficients of the polynomial function).
        final double[] coeff = fitter.fit(obs.toList());

        System.out.println(Arrays.toString(coeff));
        
        
    }
    
 

    
}
