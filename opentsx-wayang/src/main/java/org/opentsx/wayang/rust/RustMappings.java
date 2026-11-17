package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.UnionAllOperator;
import org.apache.wayang.core.mapping.Mapping;
import org.apache.wayang.core.mapping.OperatorPattern;
import org.apache.wayang.core.mapping.PlanTransformation;
import org.apache.wayang.core.mapping.ReplacementSubplanFactory;
import org.apache.wayang.core.mapping.SubplanPattern;
import org.apache.wayang.core.types.DataSetType;

import java.util.Collection;
import java.util.List;

final class RustMappings implements Mapping {
    @Override
    public Collection<PlanTransformation> getTransformations() {
        RustStageAction prototypeAction = () -> JsonNodeFactory.instance.objectNode();
        RustTopicSource source = new RustTopicSource("tsx.pattern.episodes.v2", List.of("decode"), prototypeAction);
        RustSeriesChain chain = new RustSeriesChain(List.of(JsonNodeFactory.instance.objectNode()
                .put("id", "clean").put("op", "convert.unit")), prototypeAction);
        RustTopicSink sink = new RustTopicSink("tsx.pattern.clean.v2", "clean", prototypeAction);
        return List.of(
                new PlanTransformation(SubplanPattern.createSingleton(new OperatorPattern<>("source", source, false)),
                        new ReplacementSubplanFactory.OfSingleOperators<RustTopicSource>(
                                (matched, epoch) -> new RustExecutionOperators.Source(matched).at(epoch)), RustPlatform.getInstance()),
                new PlanTransformation(SubplanPattern.createSingleton(new OperatorPattern<>("chain", chain, false)),
                        new ReplacementSubplanFactory.OfSingleOperators<RustSeriesChain>(
                                (matched, epoch) -> new RustExecutionOperators.Chain(matched).at(epoch)), RustPlatform.getInstance()),
                new PlanTransformation(SubplanPattern.createSingleton(new OperatorPattern<>("sink", sink, false)),
                        new ReplacementSubplanFactory.OfSingleOperators<RustTopicSink>(
                                (matched, epoch) -> new RustExecutionOperators.Sink(matched).at(epoch)), RustPlatform.getInstance()),
                new PlanTransformation(SubplanPattern.createSingleton(new OperatorPattern<>("union",
                        new UnionAllOperator<SpecificRecord>(DataSetType.createDefault(SpecificRecord.class)), false)),
                        new ReplacementSubplanFactory.OfSingleOperators<UnionAllOperator<SpecificRecord>>(
                                (matched, epoch) -> new RustExecutionOperators.Union(matched).at(epoch)), RustPlatform.getInstance()));
    }
}
