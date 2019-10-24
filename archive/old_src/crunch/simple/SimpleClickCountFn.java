package org.opentsx.crunch.simple;

import org.apache.crunch.DoFn;
import org.apache.crunch.Emitter;
import org.apache.crunch.Pair;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.Vector.Element;
import org.apache.mahout.math.VectorWritable;

import java.util.Iterator;
/**
 * Takes the time series and calculates the total sum.
 */
public class SimpleClickCountFn extends DoFn<Pair<Text,VectorWritable>, Double> {
	
	private static final long serialVersionUID = 1L;

	private static Logger logger = Logger.getLogger(SimpleClickCountFn.class);

	/**
	 * Process the incoming VectorWritables to calc a sum 
	 */
	@Override
	public void process(Pair<Text,VectorWritable> input, Emitter<Double> emitter) {

		Double sum = 0.0;
		VectorWritable v = input.second();

		Vector vv = v.get();

		Iterable<Element> a = vv.all();

		Iterator<Element> i = a.iterator();

		while( i.hasNext() )  {
			Element e = i.next();
			sum = sum + Math.abs( e.get() );
		}	
		emitter.emit( sum );

	}
}
