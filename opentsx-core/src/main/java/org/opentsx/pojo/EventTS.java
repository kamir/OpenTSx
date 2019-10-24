package org.opentsx.pojo;

import org.opentsx.data.model.Event;
import org.opentsx.data.model.EventSeriesRecord;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;

public class EventTS extends AbstractTS {
	
	public EventTS() { };

	Hashtable<Date,Double> values;
	Hashtable<Date,URI> tags;
	
	public void setData( 
			Hashtable<Date,Double> v, 
			Hashtable<Date,URI> t, 
			String label, 
			Date s, 
			Date e ) {
		
		super.label = label;
		values = v;
		tags = t;

	    super.tStart = s.getTime();
	    super.tEnd = e.getTime();
	}
	
	/**
	 * Mapping of an Avro record to a POJO.
	 * 
	 * @param v
	 */
	public void setData( EventSeriesRecord record ) {
				
	};

	/** 
	 * If no record is available and testmode is on,
	 * we create a random record on the fly.
	 * 
	 * @return EventTSRecord
	 */
	public EventSeriesRecord getRecord() {
		if (values == null) return getDefaultRandomRecord( 10 );
		else return createRecord();
	}

	/**
	 * Mapping of POJO to an Avro record, generated from 
	 * Avro schema (eventts.avsc)
	 * 
	 * @return EventTSRecord
	 */
	private EventSeriesRecord createRecord() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * For tests, we need some random records. So we create those here.
	 * 
	 * @param z : number of events in the random record.
	 * 
	 * @return
	 */
	private EventSeriesRecord getDefaultRandomRecord(int z) {
		
		ArrayList<Event> eventArray = new ArrayList<Event>();
		for(int i = 0; i < z; i++ ) {
			Event e = new Event( System.currentTimeMillis() , "URI", Math.random() );
			eventArray.add(e);
		}
		
		label = "randomRecord " + System.currentTimeMillis();
		EventSeriesRecord rec = new EventSeriesRecord(eventArray, label, tStart, tEnd);
		return rec;
	}
	
//	public static org.apache.crunch.types.PType getWritablePType() {
//		 return Writables.derived(
//			      EventTS.class,
//			      new MapFn<EventTSWritable, EventTS>() {
//					private static final long serialVersionUID = 1L;
//					public EventTS map(EventTSWritable etsw) { return etsw.get(); }
//			      },
//			      new MapFn<EventTS, EventTSWritable>() {
//   				private static final long serialVersionUID = 1L;
//			        public EventTSWritable map(EventTS ets) { return new EventTSWritable(ets); }
//			      },
//			      Writables.writables(EventTSWritable.class));		
//   }

}












