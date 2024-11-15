package org.opentsx.data.generator;

import org.opentsx.data.series.TimeSeriesObject;
import java.util.Random;

/**
 *  The   T e s t D a t a F a c t o r y  
 */ 

public class TestDataFactory {

    static int  mult=16807, iadd=2147483647;               /* random number generator */
    static int[]  mz = new int[250];
    static int idens, itoz;
    static double  dimax;

    static void StartZufallszahlen( int la )                     /* start random number generator */
    {  
       int i, lm;
       lm = 2*la+1;
       itoz = 0;
       dimax = 1. / iadd;
       for (i=0; i<250; i++)
       {  lm *= mult;
          if (lm < 0) lm += iadd + 1;
          mz[i] = lm;
       }
    } 
    
    
    /* generate model data using binomial multifractal model */
    /**

int  Binomial_Multifractal_Model(x, a)          
double  *x, a;                                   
{  int  i, j, s, anz;
   
   anz = 1<<((int) (log((double) N) /log(2.)));
   for (i=0; i<anz; i++)
      x[i] = 1.0;
   for (s=anz; s>1; s/=2)
      for (i=0; i<anz/s; i++)
      {  itoz = (itoz+1) % 250;
         mz[itoz] ^= mz[(itoz+103) % 250];
         if (mz[itoz]*dimax < 0.5)
          {  for (j=i*s; j<i*s+s/2; j++)
               x[j] *= a;
            for (j=i*s+s/2; j<i*s+s; j++)
               x[j] *= 1.-a;
         }
         else
         {  for (j=i*s; j<i*s+s/2; j++)
               x[j] *= 1.-a;
            for (j=i*s+s/2; j<i*s+s; j++)
               x[j] *= a;
         }
      }
   return(anz);
}


     *
     * @param z
     * @return
     */
    public static double[] getRandomValues_Binomial_Multifractal_Model( int z, double a ) {

        int konf = (int)(Math.random() * 100);
        
        StartZufallszahlen( 95473 + konf );

        int i = 0; 
        int anz = z;
        int s = 0;
        double x[] = new double[z];
        
        for (i=0; i<anz; i++) {
            x[i] = 1.0;
        }

        int itoz = 0;
        double mz[] = new double[z];
        int j = 0;
        
        for (s=anz; s>1; s/=2) {
           for (i=0; i<anz/s; i++) {
               
//              itoz = (itoz+1) % 250;
//              mz[itoz] = Math.pow( mz[itoz], mz[(itoz+103) % 250] );
//              double d = mz[itoz]*dimax;
//              boolean crit = d < 0.5;

              boolean crit = RNGWrapper.getStdRandomBernoulli();
              
              if ( crit )
              {  for (j=i*s; j<i*s+s/2; j++)
                    x[j] *= a;
                 for (j=i*s+s/2; j<i*s+s; j++)
                    x[j] *= (1.0-a);
              }
              else
              {  for (j=i*s; j<i*s+s/2; j++)
                    x[j] *= (1.0-a);
                 for (j=i*s+s/2; j<i*s+s; j++)
                    x[j] *= a;
              }
           }
        }  
        return x;
    };
    
        /**
     * Random Walk
     */
    public static TimeSeriesObject getDataSeriesBinomialMultifractalValues(int z, double a ) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("BinomialMultifractal N=" + z + ", a=" + a);
        double y=0;
        
        double x[] = getRandomValues_Binomial_Multifractal_Model( z,a );
        
        for( int i = 0; i < z; i++ ) {
            mr.addValuePair( i * 1.0, x[i] );
        }
        return mr;
    };

    
    /**
     * Gleichverteilte ZZ zw. 0 und 1
     *
     * @param z
     * @return
     */
    public static double[] getRandomValues( int z ) {
        double[] data = new double[z];
        for( int i = 0; i < z; i++ ) {
            data[i] = RNGWrapper.getStdRandomUniform( 0,1);
        }
        return data;
    };
    
    /**
     * Multifractal - Cauchy
     */
    public static TimeSeriesObject getDataSeriesRandomValues_Cauchy(int z ) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("Std. Cauchy");
        double y=0;
         for( int i = 0; i < z; i++ ) {
            y=RNGWrapper.getStdRandomCauchy(); // multifractal
            mr.addValuePair( i * 1.0, y );
        }
        return mr;
    };

    /**
     * Random Walk
     */
    public static TimeSeriesObject getDataSeriesRandomValues_RW(int z ) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("Random Walk");
        double y=0;
         for( int i = 0; i < z; i++ ) {
            y = y + RNGWrapper.getStdRandomUniform( 1,1 ); // random walk => alpha ~1,5
            mr.addValuePair( i * 1.0, y );
        }
        return mr;
    };

    /**
     * Gleichverteilt
     *
     * StdRandom.uniform();
     *
     */
    public static TimeSeriesObject getDataSeriesRandomValues2(int z ) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("Uniform Distribution");
        double y=0;
         for( int i = 0; i < z; i++ ) {
            y = RNGWrapper.getStdRandomUniform( -1,1); // ???   => alpha ~???
            mr.addValuePair( i * 1.0, y );
        }
        return mr;
    };

   
    
    /**
     * Gaussverteilt
     *
     * StdRandom.gaussian();
     *
     */
    public static TimeSeriesObject getDataSeriesRandomValues3(int z ) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("Gauss");
        double y=0;
         for( int i = 0; i < z; i++ ) {
            y= RNGWrapper.getStdRandomGaussian( 0.0, 2 ); // random  => alpha ~0,5
            mr.addValuePair( i * 1.0, y );
        }
        return mr;
    };

    /**
     * Java-Standard Zufallszahlen ...
     *
     * StdRandom.gaussian();
     *
     */
    public static TimeSeriesObject getDataSeriesRandomValues_JAVA_CORE(int z ) {
        Random rand = new Random();

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("JAVA");
        double y=0;
         for( int i = 0; i < z; i++ ) {
            y = rand.nextGaussian(); // random  => alpha ~0,5
            mr.addValuePair( i * 1.0, y );
        }
        return mr;
    };
}
