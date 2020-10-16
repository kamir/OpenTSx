public class InfoDynamicsFunctionWrapper {

    /*
    public static double calcTransferEntropy(TimeSeriesObject mra, TimeSeriesObject mrb, int le) {

// Prepare to generate some random normalised data.
        int numObservations = mra.getYData().length;

        int numDiscreteLevels = le;

        double[] sourceArray = mra.getYData();
        double[] destArray = mrb.getYData();

// Discretize or bin the data -- one could also call:
//  MatrixUtils.discretiseMaxEntropy for a maximum entropy binning
        int[] binnedSource = MatrixUtils.discretise(sourceArray, numDiscreteLevels);
        int[] binnedDest = MatrixUtils.discretise(destArray, numDiscreteLevels);

// Create a TE calculator and run it:
        TransferEntropyCalculatorDiscrete teCalc
                = new TransferEntropyCalculatorDiscrete(numDiscreteLevels, 1);
        teCalc.initialise();
        teCalc.addObservations(binnedSource, binnedDest);
        double result = teCalc.computeAverageLocalOfObservations();
// Calculation will be heavily biased because of the binning,
//  and the small number of samples
//        System.out.printf("TE result %.4f bits;\n",
//                result);
//
        return result;
    }


    public static double calcMI_JIDT(double[] univariateSeries1,double[] univariateSeries2) throws Exception {



//                //  c. Pull out the columns from the data set which
//		//     correspond to the univariate and joint variables we will work with:
//		//     First the univariate series to compute standard MI between:
//		int univariateSeries1Column = props.getIntProperty("univariateSeries1Column");
//		int univariateSeries2Column = props.getIntProperty("univariateSeries2Column");
//		double[] univariateSeries1 = MatrixUtils.selectColumn(data, univariateSeries1Column);
//		double[] univariateSeries2 = MatrixUtils.selectColumn(data, univariateSeries2Column);
//		//     Next the multivariate series to compute joint or multivariate MI between:
//		int[] jointVariable1Columns = props.getIntArrayProperty("jointVariable1Columns");
//		int[] jointVariable2Columns = props.getIntArrayProperty("jointVariable2Columns");
//		double[][] jointVariable1 = MatrixUtils.selectColumns(data, jointVariable1Columns);
//		double[][] jointVariable2 = MatrixUtils.selectColumns(data, jointVariable2Columns);
//
        // 1. Create a reference for our calculator as
        //  an object implementing the interface type:
        MutualInfoCalculatorMultiVariate miCalc;

        // 2. Define the name of the class to be instantiated here:
        String implementingClass = "infodynamics.measures.continuous.kraskov.MutualInfoCalculatorMultiVariateKraskov1";

//# Note that one could use any of the following calculators (try them all!):
//#  implementingClass = infodynamics.measures.continuous.kraskov.MutualInfoCalculatorMultiVariateKraskov1
//#  implementingClass = infodynamics.measures.continuous.kernel.MutualInfoCalculatorMultiVariateKernel
//#  implementingClass = infodynamics.measures.continuous.gaussian.MutualInfoCalculatorMultiVariateGaussian

        // 3. Dynamically instantiate an object of the given class:
        //  Part 1: Class.forName(implementingClass) grabs a reference to
        //   the class named by implementingClass.
        //  Part 2: .newInstance() creates an object instance of that class.
        //  Part 3: (MutualInfoCalculatorMultiVariate) casts the return
        //   object into an instance of our generic interface type.
        miCalc = (MutualInfoCalculatorMultiVariate)
                Class.forName(implementingClass).newInstance();

        // 4. Start using our MI calculator, paying attention to only
        //  call common methods defined in the interface type, not methods
        //  only defined in a given implementation class.
        // a. Initialise the calculator for a univariate calculation:
        miCalc.initialise(1, 1);
        // b. Supply the observations to compute the PDFs from:
        miCalc.setObservations(univariateSeries1, univariateSeries2);
        // c. Make the MI calculation:
        double miUnivariateValue = miCalc.computeAverageLocalOfObservations();

//		System.out.printf("MI calculator %s computed the univariate MI() as %.5f \n",
//				implementingClass,
//				miUnivariateValue);

        return miUnivariateValue;
    }



    public static double calcTransferEntropy(TimeSeriesObject mra, TimeSeriesObject mrb, int le) {

// Prepare to generate some random normalised data.
        int numObservations = mra.getYData().length;

        int numDiscreteLevels = le;

        double[] sourceArray = mra.getYData();
        double[] destArray = mrb.getYData();

// Discretize or bin the data -- one could also call:
//  MatrixUtils.discretiseMaxEntropy for a maximum entropy binning
        int[] binnedSource = MatrixUtils.discretise(sourceArray, numDiscreteLevels);
        int[] binnedDest = MatrixUtils.discretise(destArray, numDiscreteLevels);

// Create a TE calculator and run it:
        TransferEntropyCalculatorDiscrete teCalc
                = new TransferEntropyCalculatorDiscrete(numDiscreteLevels, 1);
        teCalc.initialise();
        teCalc.addObservations(binnedSource, binnedDest);
        double result = teCalc.computeAverageLocalOfObservations();
// Calculation will be heavily biased because of the binning,
//  and the small number of samples
//        System.out.printf("TE result %.4f bits;\n",
//                result);
//
        return result;
    }




    public static double calcTransferEntropy(TimeSeriesObject mra, TimeSeriesObject mrb, int le) {

// Prepare to generate some random normalised data.
        int numObservations = mra.getYData().length;

        int numDiscreteLevels = le;

        double[] sourceArray = mra.getYData();
        double[] destArray = mrb.getYData();

// Discretize or bin the data -- one could also call:
//  MatrixUtils.discretiseMaxEntropy for a maximum entropy binning
        int[] binnedSource = MatrixUtils.discretise(sourceArray, numDiscreteLevels);
        int[] binnedDest = MatrixUtils.discretise(destArray, numDiscreteLevels);

// Create a TE calculator and run it:
        TransferEntropyCalculatorDiscrete teCalc
                = new TransferEntropyCalculatorDiscrete(numDiscreteLevels, 1);
        teCalc.initialise();
        teCalc.addObservations(binnedSource, binnedDest);
        double result = teCalc.computeAverageLocalOfObservations();
// Calculation will be heavily biased because of the binning,
//  and the small number of samples
//        System.out.printf("TE result %.4f bits;\n",
//                result);
//
        return result;
    }



     */



}
