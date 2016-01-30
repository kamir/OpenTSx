#!/usr/bin/gawk -f  
#
# Kolmogorow-Smirnow-Test (KS test) for link strength distributions
# (C) 10/2013 Jan Kantelhardt
#
# Usage:
# ./kstest.awk -v parameter1=x -v parameter2=y ... file
# Example: 
# ./kstest.awk test.dat
# Output: KS test with N1=   42, N2=   42 data points yields p-value= 9.33e-02
#
# Parameters:
# col1 = number of first column for comparison (optional, default is 1)
# col2 = number of second column for comparison (optional, default is 2)
# min  = minimum link strength (optional, default is -100)
# max  = maximum link strength (optional, default is +100)
# typ  = typ (A,B,C ...);
#
# Output:  p value of Kolmogorow-Smirnow test comparison
#
function abs(value)
{  return (value<0?-value:value);	# calculate absolute value
}

function KStest(di1, di2, sides)	# KS Test
{  F1=F2=dist=0;
   j1=j2=1;
   for (j=1; j<=length(di1); j++)	# select part of data
      if (di1[j]>=min && di1[j]<=max)
         di1n[j1++]=di1[j];
   for (j=1; j<=length(di2); j++)
      if (di2[j]>=min && di2[j]<=max)
         di2n[j2++]=di2[j];

   # changed asort to sort
   N1 = asort(di1n);		# sort and calculate number of data
   N2 = asort(di2n);
 
   if ( N1==0 || N2==0 )
      pval=1;
   else { 
        j1=j2=1;	
        while (j1<=N1 && j2<=N2)	# calculate KS test difference
        {  if (di1n[j1] <= di2n[j2])
              F1 = j1++/N1;
           if (di1n[j1] >= di2n[j2])
              F2 = j2++/N2;
           if (abs(F1-F2) > dist)
              dist = abs(F1-F2);
        }
        delete di1n;
        delete di2n;
        N = sqrt(N1*N2/(N1+N2));	# calculate KS test p-value
        a = (N+0.12+0.11/N)*dist;
        a = -2*a*a;
        b = 2;
        pval=termbf=0;
        for (i=1; i<=100; i++)
        {  if (a*i*i > -500)
              term = b*exp(a*i*i);
           else
              term = 0;
           pval += term;
           if (abs(term) <= 0.001*termbf || abs(term) <= 1e-8*pval)
              i=102;
           b = -b;
           termbf = abs(term);
        }
   } 
   if (sides==1)  pval/=2;
   if (i<101)
      pval = 1;
#   printf("KS test with N1=%5d, N2=%5d data points yields p-value=%9.2e\n", 
#      N1, N2, pval);
   
   q = "[-]";

   type = "-";
   type = typ;
   
   if ( pval < 0.03 ) q = "[o]";
   if ( pval < 0.01 ) q = "[+]";
   if ( pval < 0.001 ) q = "[#]";
       
   printf("%s\t%5d\t%5d\t%9.2e\t%s\t%5f\t%5f\t%s\n", typ, N1, N2, pval,q ,min, max,typ);
   printf("%5f\n", pval);
 

   return pval;
}

BEGIN{ 		

   FS = "\t"
   

   # initialization
   if (min == "")  min = -100;
   if (max == "")  max = 100;
   if (col1 == "")  col1 = 2;
   if (col2 == "")  col2 = 3;
   ind1 = ind2 = 1;
}


NR>9{  
   
   data1[ind1++] = strtonum( $(col1) ); 	# read file
   data2[ind2++] = strtonum( $(col2) );
   
   # printf("\t%5f\t%5f \n", strtonum($(col1)), strtonum($(col2)) );
   
}

END{
   #printf("N1\tN2\tp-value\tmin\tmax\ttyp\n");
   KStest(data1, data2, 2);
}

