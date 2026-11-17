package org.opentsx.wayang.rust;

import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.UnionAllOperator;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;

import java.util.List;

final class RustExecutionOperators {
    private RustExecutionOperators() {
    }

    static final class Source extends RustTopicSource implements RustExecutionOperator {
        Source(RustTopicSource that) {
            super(that);
        }

        @Override
        public Role role() {
            return Role.SOURCE;
        }

        @Override
        protected ExecutionOperator createCopy() {
            return new Source(this);
        }
    }

    static final class Chain extends RustSeriesChain implements RustExecutionOperator {
        Chain(RustSeriesChain that) {
            super(that);
        }

        @Override
        public Role role() {
            return Role.CHAIN;
        }

        @Override
        protected ExecutionOperator createCopy() {
            return new Chain(this);
        }
    }

    static final class Sink extends RustTopicSink implements RustExecutionOperator {
        Sink(RustTopicSink that) {
            super(that);
        }

        @Override
        public Role role() {
            return Role.SINK;
        }

        @Override
        protected ExecutionOperator createCopy() {
            return new Sink(this);
        }
    }

    static final class Union extends UnionAllOperator<SpecificRecord> implements RustExecutionOperator {
        Union(UnionAllOperator<SpecificRecord> that) {
            super(that);
        }

        @Override
        public Role role() {
            return Role.UNION;
        }

        @Override
        public RustStageAction action() {
            return null;
        }

        @Override
        public List<String> originalNodeIds() {
            return List.of();
        }

        @Override
        protected ExecutionOperator createCopy() {
            return new Union(this);
        }
    }
}
