package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;
import org.opentsx.retrieval.pattern.Sax;

public class TsxSaxWord extends UnaryToUnaryOperator<SpecificRecord, SpecificRecord> {

    private final int w;
    private final int a;
    private final boolean normalize;
    private final String specHash;

    public TsxSaxWord(int w, int a, String specHash) {
        this(w, a, specHash, true);
    }

    private TsxSaxWord(int w, int a, String specHash, boolean normalize) {
        super(DataSetType.createDefault(SpecificRecord.class), DataSetType.createDefault(SpecificRecord.class), false);
        if (w < 1) throw new IllegalArgumentException("SAX word length must be positive");
        Sax.breakpoints(a);
        if (specHash == null || !specHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SAX needs a 64-character lower-case hex specHash");
        }
        this.w = w;
        this.a = a;
        this.normalize = normalize;
        this.specHash = specHash;
    }

    public static TsxSaxWord normalized(int w, int a, String specHash) {
        return new TsxSaxWord(w, a, specHash, false);
    }

    protected TsxSaxWord(TsxSaxWord that) {
        super(that);
        this.w = that.w;
        this.a = that.a;
        this.normalize = that.normalize;
        this.specHash = that.specHash;
    }

    public int w() { return w; }
    public int a() { return a; }
    public boolean normalize() { return normalize; }
    public String specHash() { return specHash; }
}
