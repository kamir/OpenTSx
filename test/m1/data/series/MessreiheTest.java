package m1.data.series;

import org.apache.hadoopts.data.series.TimeSeriesObject;

import java.io.File;
import java.text.DecimalFormat;
import java.util.Vector;
import junit.framework.TestCase;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.jfree.data.xy.XYSeries;

/**
 *
 * @author napierala
 */
public class MessreiheTest extends TestCase {
    
    public MessreiheTest(String testName) {
        super(testName);
    }

    /**
     * Test of isNotEmpty method, of class TimeSeriesObject.
     */
    public void testIsNotEmpty() {
        System.out.println("isNotEmpty");
        TimeSeriesObject instance = new TimeSeriesObject();
        boolean expResult = false;
        boolean result = instance.isNotEmpty();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        // fail("The test case is a prototype.");
    }

    /**
     * Test of getDecimalFormat_STAT method, of class TimeSeriesObject.
     */
    public void testGetDecimalFormat_STAT() {
        System.out.println("getDecimalFormat_STAT");
        TimeSeriesObject instance = new TimeSeriesObject();
        DecimalFormat expResult = null;
        DecimalFormat result = instance.getDecimalFormat_STAT();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getDecimalFormat_X method, of class TimeSeriesObject.
     */
    public void testGetDecimalFormat_X() {
        System.out.println("getDecimalFormat_X");
        TimeSeriesObject instance = new TimeSeriesObject();
        DecimalFormat expResult = null;
        DecimalFormat result = instance.getDecimalFormat_X();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getDecimalFormat_Y method, of class TimeSeriesObject.
     */
    public void testGetDecimalFormat_Y() {
        System.out.println("getDecimalFormat_Y");
        TimeSeriesObject instance = new TimeSeriesObject();
        DecimalFormat expResult = null;
        DecimalFormat result = instance.getDecimalFormat_Y();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getStatus method, of class TimeSeriesObject.
     */
    public void testGetStatus() {
        System.out.println("getStatus");
        TimeSeriesObject instance = new TimeSeriesObject();
        StringBuffer expResult = null;
        StringBuffer result = instance.getStatus();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getGaussianDistribution method, of class TimeSeriesObject.
     */
    public void testGetGaussianDistribution() {
        System.out.println("getGaussianDistribution");
        int i = 0;
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = TimeSeriesObject.getGaussianDistribution(i);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getSize method, of class TimeSeriesObject.
     */
    public void testGetSize() {
        System.out.println("getSize");
        TimeSeriesObject instance = new TimeSeriesObject();
        int[] expResult = null;
        int[] result = instance.getSize();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of addValuePair method, of class TimeSeriesObject.
     */
    public void testAddValuePair() {
        System.out.println("addValuePair");
        double x = 0.0;
        double y = 0.0;
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.addValuePair(x, y);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getXValues method, of class TimeSeriesObject.
     */
    public void testGetXValues() {
        System.out.println("getXValues");
        TimeSeriesObject instance = new TimeSeriesObject();
        Vector expResult = null;
        Vector result = instance.getXValues();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getYValues method, of class TimeSeriesObject.
     */
    public void testGetYValues() {
        System.out.println("getYValues");
        TimeSeriesObject instance = new TimeSeriesObject();
        Vector expResult = null;
        Vector result = instance.getYValues();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of toString method, of class TimeSeriesObject.
     */
    public void testToString() {
        System.out.println("toString");
        TimeSeriesObject instance = new TimeSeriesObject();
        String expResult = "";
        String result = instance.toString();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of setLabel method, of class TimeSeriesObject.
     */
    public void testSetLabel() {
        System.out.println("setLabel");
        String label = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.setLabel(label);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of setAddinfo method, of class TimeSeriesObject.
     */
    public void testSetAddinfo() {
        System.out.println("setAddinfo");
        String addinfo = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.setAddinfo(addinfo);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getAddinfo method, of class TimeSeriesObject.
     */
    public void testGetAddinfo() {
        System.out.println("getAddinfo");
        TimeSeriesObject instance = new TimeSeriesObject();
        String expResult = "";
        String result = instance.getAddinfo();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getLabel method, of class TimeSeriesObject.
     */
    public void testGetLabel() {
        System.out.println("getLabel");
        TimeSeriesObject instance = new TimeSeriesObject();
        String expResult = "";
        String result = instance.getLabel();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getData method, of class TimeSeriesObject.
     */
    public void testGetData() {
        System.out.println("getData");
        TimeSeriesObject instance = new TimeSeriesObject();
        double[][] expResult = null;
        double[][] result = instance.getData();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of calcAverage method, of class TimeSeriesObject.
     */
    public void testCalcAverage() {
        System.out.println("calcAverage");
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.calcAverage();
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getAvarage method, of class TimeSeriesObject.
     */
    public void testGetAvarage() {
        System.out.println("getAvarage");
        TimeSeriesObject instance = new TimeSeriesObject();
        double expResult = 0.0;
        double result = instance.getAvarage();
        assertEquals(expResult, result, 0.0);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of setDecimalFomrmatX method, of class TimeSeriesObject.
     */
    public void testSetDecimalFomrmatX() {
        System.out.println("setDecimalFomrmatX");
        String format = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.setDecimalFomrmatX(format);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of setDecimalFomrmatY method, of class TimeSeriesObject.
     */
    public void testSetDecimalFomrmatY() {
        System.out.println("setDecimalFomrmatY");
        String format = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.setDecimalFomrmatY(format);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getMaxX method, of class TimeSeriesObject.
     */
    public void testGetMaxX() {
        System.out.println("getMaxX");
        TimeSeriesObject instance = new TimeSeriesObject();
        double expResult = 0.0;
        double result = instance.getMaxX();
        assertEquals(expResult, result, 0.0);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getMaxY method, of class TimeSeriesObject.
     */
    public void testGetMaxY() {
        System.out.println("getMaxY");
        TimeSeriesObject instance = new TimeSeriesObject();
        double expResult = 0.0;
        double result = instance.getMaxY();
        assertEquals(expResult, result, 0.0);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getStatisticData method, of class TimeSeriesObject.
     */
    public void testGetStatisticData() {
        System.out.println("getStatisticData");
        String pre = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        String expResult = "";
        String result = instance.getStatisticData(pre);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getYData method, of class TimeSeriesObject.
     */
    public void testGetYData() {
        System.out.println("getYData");
        TimeSeriesObject instance = new TimeSeriesObject();
        double[] expResult = null;
        double[] result = instance.getYData();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getXYSeries method, of class TimeSeriesObject.
     */
    public void testGetXYSeries() {
        System.out.println("getXYSeries");
        TimeSeriesObject instance = new TimeSeriesObject();
        XYSeries expResult = null;
        XYSeries result = instance.getXYSeries();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of writeToFile method, of class TimeSeriesObject.
     */
    public void testWriteToFile() {
        System.out.println("writeToFile");
        File f = null;
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.writeToFile(f);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of addValue method, of class TimeSeriesObject.
     */
    public void testAddValue() {
        System.out.println("addValue");
        double y = 0.0;
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.addValue(y);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getLabel_X method, of class TimeSeriesObject.
     */
    public void testGetLabel_X() {
        System.out.println("getLabel_X");
        TimeSeriesObject instance = new TimeSeriesObject();
        String expResult = "";
        String result = instance.getLabel_X();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of setLabel_X method, of class TimeSeriesObject.
     */
    public void testSetLabel_X() {
        System.out.println("setLabel_X");
        String label_X = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.setLabel_X(label_X);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of setLabel_Y method, of class TimeSeriesObject.
     */
    public void testSetLabel_Y() {
        System.out.println("setLabel_Y");
        String label_Y = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.setLabel_Y(label_Y);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getLabel_Y method, of class TimeSeriesObject.
     */
    public void testGetLabel_Y() {
        System.out.println("getLabel_Y");
        TimeSeriesObject instance = new TimeSeriesObject();
        String expResult = "";
        String result = instance.getLabel_Y();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getStddev method, of class TimeSeriesObject.
     */
    public void testGetStddev() {
        System.out.println("getStddev");
        TimeSeriesObject instance = new TimeSeriesObject();
        double expResult = 0.0;
        double result = instance.getStddev();
        assertEquals(expResult, result, 0.0);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of normalize method, of class TimeSeriesObject.
     */
    public void testNormalize() {
        System.out.println("normalize");
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.normalize();
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getYValueForX method, of class TimeSeriesObject.
     */
    public void testGetYValueForX() {
        System.out.println("getYValueForX");
        int i = 0;
        TimeSeriesObject instance = new TimeSeriesObject();
        double expResult = 0.0;
        double result = instance.getYValueForX(i);
        assertEquals(expResult, result, 0.0);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of setBinningX_sum method, of class TimeSeriesObject.
     */
    public void testSetBinningX_sum() {
        System.out.println("setBinningX_sum");
        int bin = 0;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.setBinningX_sum(bin);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of setBinningX_average method, of class TimeSeriesObject.
     */
    public void testSetBinningX_average() {
        System.out.println("setBinningX_average");
        int bin = 0;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.setBinningX_average(bin);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of divY method, of class TimeSeriesObject.
     */
    public void testDivY() {
        System.out.println("divY");
        double divBy = 0.0;
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.divide_Y_by(divBy);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of summeY method, of class TimeSeriesObject.
     */
    public void testSummeY() {
        System.out.println("summeY");
        TimeSeriesObject instance = new TimeSeriesObject();
        double expResult = 0.0;
        double result = instance.summeY();
        assertEquals(expResult, result, 0.0);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of diff method, of class TimeSeriesObject.
     */
    public void testDiff() {
        System.out.println("diff");
        TimeSeriesObject mr2 = null;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.diff(mr2);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of add method, of class TimeSeriesObject.
     */
    public void testAdd() {
        System.out.println("add");
        TimeSeriesObject mr2 = null;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.add(mr2);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of addValues method, of class TimeSeriesObject.
     */
    public void testAddValues() {
        System.out.println("addValues");
        TimeSeriesObject mr = null;
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.addValues(mr);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of scaleX method, of class TimeSeriesObject.
     */
    public void testScaleX() {
        System.out.println("scaleX");
        int maxX = 0;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.scaleX(maxX);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of addStatusInfo method, of class TimeSeriesObject.
     */
    public void testAddStatusInfo() {
        System.out.println("addStatusInfo");
        String string = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.addStatusInfo(string);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getStatusInfo method, of class TimeSeriesObject.
     */
    public void testGetStatusInfo() {
        System.out.println("getStatusInfo");
        TimeSeriesObject instance = new TimeSeriesObject();
        String expResult = "";
        String result = instance.getStatusInfo();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of cut method, of class TimeSeriesObject.
     */
    public void testCut() {
        System.out.println("cut");
        int nrOfValues = 0;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.cut(nrOfValues);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of shift method, of class TimeSeriesObject.
     */
    public void testShift() throws Exception {
        System.out.println("shift");
        int offset = 0;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.shift(offset);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of copy method, of class TimeSeriesObject.
     */
    public void testCopy() {
        System.out.println("copy");
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.copy();
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of scaleX_2 method, of class TimeSeriesObject.
     */
    public void testScaleX_2() {
        System.out.println("scaleX_2");
        double f = 0.0;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.scaleX_2(f);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of scaleY_2 method, of class TimeSeriesObject.
     */
    public void testScaleY_2() {
        System.out.println("scaleY_2");
        double f = 0.0;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.scaleY_2(f);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of setLabels method, of class TimeSeriesObject.
     */
    public void testSetLabels() {
        System.out.println("setLabels");
        String label = "";
        String xL = "";
        String yL = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.setLabels(label, xL, yL);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of averageForAll method, of class TimeSeriesObject.
     */
    public void testAverageForAll() {
        System.out.println("averageForAll");
        TimeSeriesObject[] mrs = null;
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = TimeSeriesObject.averageForAll(mrs);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of getX_for_Y method, of class TimeSeriesObject.
     */
    public void testGetX_for_Y() {
        System.out.println("getX_for_Y");
        double y = 0.0;
        TimeSeriesObject instance = new TimeSeriesObject();
        double expResult = 0.0;
        double result = instance.getX_for_Y(y);
        assertEquals(expResult, result, 0.0);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of linFit method, of class TimeSeriesObject.
     */
    public void testLinFit() throws Exception {
        System.out.println("linFit");
        double x_min = 0.0;
        double x_max = 0.0;
        TimeSeriesObject instance = new TimeSeriesObject();
        SimpleRegression expResult = null;
        SimpleRegression result = instance.linFit(x_min, x_max);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of split method, of class TimeSeriesObject.
     */
    public void testSplit() {
        System.out.println("split");
        int length = 0;
        int anzahl = 0;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject[] expResult = null;
        TimeSeriesObject[] result = instance.split(length, anzahl);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of shrinkX method, of class TimeSeriesObject.
     */
    public void testShrinkX() {
        System.out.println("shrinkX");
        double min = 0.0;
        double max = 0.0;
        TimeSeriesObject instance = new TimeSeriesObject();
        TimeSeriesObject expResult = null;
        TimeSeriesObject result = instance.shrinkX(min, max);
        assertEquals(expResult, result);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of addComment method, of class TimeSeriesObject.
     */
    public void testAddComment() {
        System.out.println("addComment");
        String string = "";
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.addComment(string);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of scaleXto method, of class TimeSeriesObject.
     */
    public void testScaleXto() {
        System.out.println("scaleXto");
        int i = 0;
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.scaleXto(i);
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of checkKonsistenz method, of class TimeSeriesObject.
     */
    public void testCheckKonsistenz() {
        System.out.println("checkKonsistenz");
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.checkKonsistenz();
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

    /**
     * Test of show method, of class TimeSeriesObject.
     */
    public void testShow() {
        System.out.println("show");
        TimeSeriesObject instance = new TimeSeriesObject();
        instance.show();
        // TODO review the generated test code and remove the default call to fail.
        fail("The test case is a prototype.");
    }

}
