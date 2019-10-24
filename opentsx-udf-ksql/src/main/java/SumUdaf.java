import io.confluent.ksql.function.udaf.TableUdaf;
import io.confluent.ksql.function.udaf.Udaf;
import io.confluent.ksql.function.udaf.UdafDescription;
import io.confluent.ksql.function.udaf.UdafFactory;

@UdafDescription(name = "my_sum", description = "sums")
public class SumUdaf {

    @UdafFactory(description = "sums longs")
    // Can be used with table aggregations
    public static TableUdaf<Long, Long> createSumLong() {
        return new TableUdaf<Long, Long>() {
            @Override
            public Long undo(final Long valueToUndo, final Long aggregateValue) {
                return aggregateValue - valueToUndo;
            }

            @Override
            public Long initialize() {
                return 0L;
            }

            @Override
            public Long aggregate(final Long value, final Long aggregate) {
                return aggregate + value;
            }

            @Override
            public Long merge(final Long aggOne, final Long aggTwo) {
                return aggOne + aggTwo;
            }
        };
    }

    @UdafFactory(description = "sums int")
    public static TableUdaf<Integer, Long> createSumInt() {
        return new TableUdaf<Integer, Long>() {
            @Override
            public Long undo(final Integer valueToUndo, final Long aggregateValue) {
                return aggregateValue - valueToUndo;
            }

            @Override
            public Long initialize() {
                return 0L;
            }

            @Override
            public Long aggregate(final Integer current, final Long aggregate) {
                return current + aggregate;
            }

            @Override
            public Long merge(final Long aggOne, final Long aggTwo) {
                return aggOne + aggTwo;
            }
        };
    }

    @UdafFactory(description = "sums double")
    public static Udaf<Double, Double> createSumDouble() {
        return new Udaf<Double, Double>() {
            @Override
            public Double initialize() {
                return 0.0;
            }

            @Override
            public Double aggregate(final Double val, final Double aggregate) {
                return aggregate + val;
            }

            @Override
            public Double merge(final Double aggOne, final Double aggTwo) {
                return aggOne + aggTwo;
            }
        };
    }

    // This method shows providing an initial value to an aggregated, i.e., it would be called
    // with my_sum(col1, 'some_initial_value')
    @UdafFactory(description = "sums the length of strings")
    public static Udaf<String, Long> createSumLengthString(final String initialString) {
        return new Udaf<String, Long>() {
            @Override
            public Long initialize() {
                return (long) initialString.length();
            }

            @Override
            public Long aggregate(final String s, final Long aggregate) {
                return aggregate + s.length();
            }

            @Override
            public Long merge(final Long aggOne, final Long aggTwo) {
                return aggOne + aggTwo;
            }
        };
    }

}