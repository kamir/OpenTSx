/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.cloudera.hts.utils.math;



import java.util.*;
import org.apache.commons.math3.analysis.ParametricUnivariateFunction;
import org.apache.commons.math3.fitting.AbstractCurveFitter;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.fitting.WeightedObservedPoint;
import org.apache.commons.math3.linear.DiagonalMatrix;

class MyFunc2 implements ParametricUnivariateFunction {
    
    public double value(double t, double... parameters) {

        final double a = parameters[0];
        final double b = parameters[1];
        final double c = parameters[2];
        
        return a * Math.pow(t, 2) + b * t + c ;
        
    }

    // Jacobian matrix of the above. In this case, this is just an array of
    // partial derivatives of the above function, with one element for each parameter.
    public double[] gradient(double t, double... parameters) {
        
        final double a = parameters[0];
        final double b = parameters[1];
        final double c = parameters[2];

        return new double[] {
            2 * a * t,
            b,
            0
        };
    }
}
 

class MyFunc implements ParametricUnivariateFunction {
    
    public double value(double t, double... parameters) {
        return parameters[0] * Math.pow(t, parameters[1]) * Math.exp(-parameters[2] * t);
    }

    // Jacobian matrix of the above. In this case, this is just an array of
    // partial derivatives of the above function, with one element for each parameter.
    public double[] gradient(double t, double... parameters) {
        
        final double a = parameters[0];
        final double b = parameters[1];
        final double c = parameters[2];

        return new double[] {
            Math.exp(-c*t) * Math.pow(t, b),
            a * Math.exp(-c*t) * Math.pow(t, b) * Math.log(t),
            a * (-Math.exp(-c*t)) * Math.pow(t, b+1)
        };
    }
}

public class FunctionFitTest extends AbstractCurveFitter {
    
    protected LeastSquaresProblem getProblem(Collection<WeightedObservedPoint> points) {
    
        final int len = points.size();
        
        final double[] target  = new double[len];
        final double[] weights = new double[len];
        
        final double[] initialGuess = { 1.0, 1.0, 1.0 };

        int i = 0;
        for(WeightedObservedPoint point : points) {
            target[i]  = point.getY();
            weights[i] = point.getWeight();
            i += 1;
        }

        final AbstractCurveFitter.TheoreticalValuesFunction model = new
            AbstractCurveFitter.TheoreticalValuesFunction(new MyFunc(), points);

        return new LeastSquaresBuilder().
            maxEvaluations(Integer.MAX_VALUE).
            maxIterations(Integer.MAX_VALUE).
            start(initialGuess).
            target(target).
            weight(new DiagonalMatrix(weights)).
            model(model.getModelFunction(), model.getModelFunctionJacobian()).
            build();
    }

    public static void main(String[] args) {

        double[] x = {-5.0,	-4.0,	-3.0,	-2.0,	0,	2,	3,	4};
        double[] y = {21,	13,	7,	3,	1,	7,	13,	21};
        
        FunctionFitTest fitter = new FunctionFitTest();
        
        ArrayList<WeightedObservedPoint> points = new ArrayList<WeightedObservedPoint>();

        // Add points here; for instance,
        int i = 0;
        for( double xc : x ) {
        
            if ( i < 1 ) {
                WeightedObservedPoint point = new WeightedObservedPoint( xc, y[i], 1.0);
                points.add(point);

                System.out.println( xc + " " + y[i] );
            }
            i++;
        }
        
        final double coeffs[] = fitter.fit(points);
        
        System.out.println(Arrays.toString(coeffs));
        
        
    }
}