/**
 * Autogenerated by Avro
 *
 * DO NOT EDIT DIRECTLY
 */
package org.opentsx.data.model;

import org.apache.avro.specific.SpecificData;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.SchemaStore;

@SuppressWarnings("all")
@org.apache.avro.specific.AvroGenerated
public class EventSeriesRecord extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  private static final long serialVersionUID = 2072106958858245680L;
  public static final org.apache.avro.Schema SCHEMA$ = new org.apache.avro.Schema.Parser().parse("{\"type\":\"record\",\"name\":\"EventSeriesRecord\",\"namespace\":\"org.opentsx.data.model\",\"fields\":[{\"name\":\"eventArray\",\"type\":{\"type\":\"array\",\"items\":{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"timestamp\",\"type\":\"long\"},{\"name\":\"uri\",\"type\":\"string\"},{\"name\":\"value\",\"type\":\"double\"}]}}},{\"name\":\"labels\",\"type\":\"string\"},{\"name\":\"tStart\",\"type\":\"long\"},{\"name\":\"tEnd\",\"type\":\"long\"}]}");
  public static org.apache.avro.Schema getClassSchema() { return SCHEMA$; }

  private static SpecificData MODEL$ = new SpecificData();

  private static final BinaryMessageEncoder<EventSeriesRecord> ENCODER =
      new BinaryMessageEncoder<EventSeriesRecord>(MODEL$, SCHEMA$);

  private static final BinaryMessageDecoder<EventSeriesRecord> DECODER =
      new BinaryMessageDecoder<EventSeriesRecord>(MODEL$, SCHEMA$);

  /**
   * Return the BinaryMessageDecoder instance used by this class.
   */
  public static BinaryMessageDecoder<EventSeriesRecord> getDecoder() {
    return DECODER;
  }

  /**
   * Create a new BinaryMessageDecoder instance for this class that uses the specified {@link SchemaStore}.
   * @param resolver a {@link SchemaStore} used to find schemas by fingerprint
   */
  public static BinaryMessageDecoder<EventSeriesRecord> createDecoder(SchemaStore resolver) {
    return new BinaryMessageDecoder<EventSeriesRecord>(MODEL$, SCHEMA$, resolver);
  }

  /** Serializes this EventSeriesRecord to a ByteBuffer. */
  public java.nio.ByteBuffer toByteBuffer() throws java.io.IOException {
    return ENCODER.encode(this);
  }

  /** Deserializes a EventSeriesRecord from a ByteBuffer. */
  public static EventSeriesRecord fromByteBuffer(
      java.nio.ByteBuffer b) throws java.io.IOException {
    return DECODER.decode(b);
  }

   private java.util.List<org.opentsx.data.model.Event> eventArray;
   private java.lang.CharSequence labels;
   private long tStart;
   private long tEnd;

  /**
   * Default constructor.  Note that this does not initialize fields
   * to their default values from the schema.  If that is desired then
   * one should use <code>newBuilder()</code>.
   */
  public EventSeriesRecord() {}

  /**
   * All-args constructor.
   * @param eventArray The new value for eventArray
   * @param labels The new value for labels
   * @param tStart The new value for tStart
   * @param tEnd The new value for tEnd
   */
  public EventSeriesRecord(java.util.List<org.opentsx.data.model.Event> eventArray, java.lang.CharSequence labels, java.lang.Long tStart, java.lang.Long tEnd) {
    this.eventArray = eventArray;
    this.labels = labels;
    this.tStart = tStart;
    this.tEnd = tEnd;
  }

  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call.
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return eventArray;
    case 1: return labels;
    case 2: return tStart;
    case 3: return tEnd;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }

  // Used by DatumReader.  Applications should not call.
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: eventArray = (java.util.List<org.opentsx.data.model.Event>)value$; break;
    case 1: labels = (java.lang.CharSequence)value$; break;
    case 2: tStart = (java.lang.Long)value$; break;
    case 3: tEnd = (java.lang.Long)value$; break;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }

  /**
   * Gets the value of the 'eventArray' field.
   * @return The value of the 'eventArray' field.
   */
  public java.util.List<org.opentsx.data.model.Event> getEventArray() {
    return eventArray;
  }

  /**
   * Sets the value of the 'eventArray' field.
   * @param value the value to set.
   */
  public void setEventArray(java.util.List<org.opentsx.data.model.Event> value) {
    this.eventArray = value;
  }

  /**
   * Gets the value of the 'labels' field.
   * @return The value of the 'labels' field.
   */
  public java.lang.CharSequence getLabels() {
    return labels;
  }

  /**
   * Sets the value of the 'labels' field.
   * @param value the value to set.
   */
  public void setLabels(java.lang.CharSequence value) {
    this.labels = value;
  }

  /**
   * Gets the value of the 'tStart' field.
   * @return The value of the 'tStart' field.
   */
  public java.lang.Long getTStart() {
    return tStart;
  }

  /**
   * Sets the value of the 'tStart' field.
   * @param value the value to set.
   */
  public void setTStart(java.lang.Long value) {
    this.tStart = value;
  }

  /**
   * Gets the value of the 'tEnd' field.
   * @return The value of the 'tEnd' field.
   */
  public java.lang.Long getTEnd() {
    return tEnd;
  }

  /**
   * Sets the value of the 'tEnd' field.
   * @param value the value to set.
   */
  public void setTEnd(java.lang.Long value) {
    this.tEnd = value;
  }

  /**
   * Creates a new EventSeriesRecord RecordBuilder.
   * @return A new EventSeriesRecord RecordBuilder
   */
  public static org.opentsx.data.model.EventSeriesRecord.Builder newBuilder() {
    return new org.opentsx.data.model.EventSeriesRecord.Builder();
  }

  /**
   * Creates a new EventSeriesRecord RecordBuilder by copying an existing Builder.
   * @param other The existing builder to copy.
   * @return A new EventSeriesRecord RecordBuilder
   */
  public static org.opentsx.data.model.EventSeriesRecord.Builder newBuilder(org.opentsx.data.model.EventSeriesRecord.Builder other) {
    return new org.opentsx.data.model.EventSeriesRecord.Builder(other);
  }

  /**
   * Creates a new EventSeriesRecord RecordBuilder by copying an existing EventSeriesRecord instance.
   * @param other The existing instance to copy.
   * @return A new EventSeriesRecord RecordBuilder
   */
  public static org.opentsx.data.model.EventSeriesRecord.Builder newBuilder(org.opentsx.data.model.EventSeriesRecord other) {
    return new org.opentsx.data.model.EventSeriesRecord.Builder(other);
  }

  /**
   * RecordBuilder for EventSeriesRecord instances.
   */
  public static class Builder extends org.apache.avro.specific.SpecificRecordBuilderBase<EventSeriesRecord>
    implements org.apache.avro.data.RecordBuilder<EventSeriesRecord> {

    private java.util.List<org.opentsx.data.model.Event> eventArray;
    private java.lang.CharSequence labels;
    private long tStart;
    private long tEnd;

    /** Creates a new Builder */
    private Builder() {
      super(SCHEMA$);
    }

    /**
     * Creates a Builder by copying an existing Builder.
     * @param other The existing Builder to copy.
     */
    private Builder(org.opentsx.data.model.EventSeriesRecord.Builder other) {
      super(other);
      if (isValidValue(fields()[0], other.eventArray)) {
        this.eventArray = data().deepCopy(fields()[0].schema(), other.eventArray);
        fieldSetFlags()[0] = true;
      }
      if (isValidValue(fields()[1], other.labels)) {
        this.labels = data().deepCopy(fields()[1].schema(), other.labels);
        fieldSetFlags()[1] = true;
      }
      if (isValidValue(fields()[2], other.tStart)) {
        this.tStart = data().deepCopy(fields()[2].schema(), other.tStart);
        fieldSetFlags()[2] = true;
      }
      if (isValidValue(fields()[3], other.tEnd)) {
        this.tEnd = data().deepCopy(fields()[3].schema(), other.tEnd);
        fieldSetFlags()[3] = true;
      }
    }

    /**
     * Creates a Builder by copying an existing EventSeriesRecord instance
     * @param other The existing instance to copy.
     */
    private Builder(org.opentsx.data.model.EventSeriesRecord other) {
            super(SCHEMA$);
      if (isValidValue(fields()[0], other.eventArray)) {
        this.eventArray = data().deepCopy(fields()[0].schema(), other.eventArray);
        fieldSetFlags()[0] = true;
      }
      if (isValidValue(fields()[1], other.labels)) {
        this.labels = data().deepCopy(fields()[1].schema(), other.labels);
        fieldSetFlags()[1] = true;
      }
      if (isValidValue(fields()[2], other.tStart)) {
        this.tStart = data().deepCopy(fields()[2].schema(), other.tStart);
        fieldSetFlags()[2] = true;
      }
      if (isValidValue(fields()[3], other.tEnd)) {
        this.tEnd = data().deepCopy(fields()[3].schema(), other.tEnd);
        fieldSetFlags()[3] = true;
      }
    }

    /**
      * Gets the value of the 'eventArray' field.
      * @return The value.
      */
    public java.util.List<org.opentsx.data.model.Event> getEventArray() {
      return eventArray;
    }

    /**
      * Sets the value of the 'eventArray' field.
      * @param value The value of 'eventArray'.
      * @return This builder.
      */
    public org.opentsx.data.model.EventSeriesRecord.Builder setEventArray(java.util.List<org.opentsx.data.model.Event> value) {
      validate(fields()[0], value);
      this.eventArray = value;
      fieldSetFlags()[0] = true;
      return this;
    }

    /**
      * Checks whether the 'eventArray' field has been set.
      * @return True if the 'eventArray' field has been set, false otherwise.
      */
    public boolean hasEventArray() {
      return fieldSetFlags()[0];
    }


    /**
      * Clears the value of the 'eventArray' field.
      * @return This builder.
      */
    public org.opentsx.data.model.EventSeriesRecord.Builder clearEventArray() {
      eventArray = null;
      fieldSetFlags()[0] = false;
      return this;
    }

    /**
      * Gets the value of the 'labels' field.
      * @return The value.
      */
    public java.lang.CharSequence getLabels() {
      return labels;
    }

    /**
      * Sets the value of the 'labels' field.
      * @param value The value of 'labels'.
      * @return This builder.
      */
    public org.opentsx.data.model.EventSeriesRecord.Builder setLabels(java.lang.CharSequence value) {
      validate(fields()[1], value);
      this.labels = value;
      fieldSetFlags()[1] = true;
      return this;
    }

    /**
      * Checks whether the 'labels' field has been set.
      * @return True if the 'labels' field has been set, false otherwise.
      */
    public boolean hasLabels() {
      return fieldSetFlags()[1];
    }


    /**
      * Clears the value of the 'labels' field.
      * @return This builder.
      */
    public org.opentsx.data.model.EventSeriesRecord.Builder clearLabels() {
      labels = null;
      fieldSetFlags()[1] = false;
      return this;
    }

    /**
      * Gets the value of the 'tStart' field.
      * @return The value.
      */
    public java.lang.Long getTStart() {
      return tStart;
    }

    /**
      * Sets the value of the 'tStart' field.
      * @param value The value of 'tStart'.
      * @return This builder.
      */
    public org.opentsx.data.model.EventSeriesRecord.Builder setTStart(long value) {
      validate(fields()[2], value);
      this.tStart = value;
      fieldSetFlags()[2] = true;
      return this;
    }

    /**
      * Checks whether the 'tStart' field has been set.
      * @return True if the 'tStart' field has been set, false otherwise.
      */
    public boolean hasTStart() {
      return fieldSetFlags()[2];
    }


    /**
      * Clears the value of the 'tStart' field.
      * @return This builder.
      */
    public org.opentsx.data.model.EventSeriesRecord.Builder clearTStart() {
      fieldSetFlags()[2] = false;
      return this;
    }

    /**
      * Gets the value of the 'tEnd' field.
      * @return The value.
      */
    public java.lang.Long getTEnd() {
      return tEnd;
    }

    /**
      * Sets the value of the 'tEnd' field.
      * @param value The value of 'tEnd'.
      * @return This builder.
      */
    public org.opentsx.data.model.EventSeriesRecord.Builder setTEnd(long value) {
      validate(fields()[3], value);
      this.tEnd = value;
      fieldSetFlags()[3] = true;
      return this;
    }

    /**
      * Checks whether the 'tEnd' field has been set.
      * @return True if the 'tEnd' field has been set, false otherwise.
      */
    public boolean hasTEnd() {
      return fieldSetFlags()[3];
    }


    /**
      * Clears the value of the 'tEnd' field.
      * @return This builder.
      */
    public org.opentsx.data.model.EventSeriesRecord.Builder clearTEnd() {
      fieldSetFlags()[3] = false;
      return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public EventSeriesRecord build() {
      try {
        EventSeriesRecord record = new EventSeriesRecord();
        record.eventArray = fieldSetFlags()[0] ? this.eventArray : (java.util.List<org.opentsx.data.model.Event>) defaultValue(fields()[0]);
        record.labels = fieldSetFlags()[1] ? this.labels : (java.lang.CharSequence) defaultValue(fields()[1]);
        record.tStart = fieldSetFlags()[2] ? this.tStart : (java.lang.Long) defaultValue(fields()[2]);
        record.tEnd = fieldSetFlags()[3] ? this.tEnd : (java.lang.Long) defaultValue(fields()[3]);
        return record;
      } catch (java.lang.Exception e) {
        throw new org.apache.avro.AvroRuntimeException(e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static final org.apache.avro.io.DatumWriter<EventSeriesRecord>
    WRITER$ = (org.apache.avro.io.DatumWriter<EventSeriesRecord>)MODEL$.createDatumWriter(SCHEMA$);

  @Override public void writeExternal(java.io.ObjectOutput out)
    throws java.io.IOException {
    WRITER$.write(this, SpecificData.getEncoder(out));
  }

  @SuppressWarnings("unchecked")
  private static final org.apache.avro.io.DatumReader<EventSeriesRecord>
    READER$ = (org.apache.avro.io.DatumReader<EventSeriesRecord>)MODEL$.createDatumReader(SCHEMA$);

  @Override public void readExternal(java.io.ObjectInput in)
    throws java.io.IOException {
    READER$.read(this, SpecificData.getDecoder(in));
  }

}
