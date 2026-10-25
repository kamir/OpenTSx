package org.opentsx.app.bucketanalyser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.opentsx.core.config.ConfigManager;
import org.opentsx.core.pfd.PFDValidation;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records user actions as a Processing Flow Descriptor (PFD).
 * See: docs/devguide/schemas/processing-flow-descriptor-schema.json
 */
public class PFDRecorder {

    private static PFDRecorder instance;
    private ProcessingFlowDescriptor currentFlow;

    private PFDRecorder() {
        reset("New Flow");
    }

    public static PFDRecorder getInstance() {
        if (instance == null) {
            instance = new PFDRecorder();
        }
        return instance;
    }

    public void reset(String name) {
        currentFlow = new ProcessingFlowDescriptor();
        currentFlow.name = name;
        currentFlow.version = "1.0.0";
        currentFlow.steps = new ArrayList<>();
        currentFlow.input = new Input();
        currentFlow.input.type = "KAFKA";
        currentFlow.input.topic = ConfigManager.getInstance().getString("opentsx.pfd.input.topic", null);

        currentFlow.output = new Output();
        currentFlow.output.type = "LOG";
    }

    public void addStep(String operation, Map<String, Object> params) {
        Step step = new Step();
        step.id = UUID.randomUUID().toString();
        step.operation = operation;
        step.params = params != null ? params : new HashMap<>();
        currentFlow.steps.add(step);
        System.out.println("[PFDRecorder] Added step: " + operation);
    }

    public String toJSON() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(currentFlow);
    }

    public void saveToFile(String filepath) throws IOException {
        PFDValidation.requireText(currentFlow.name, "Flow name");
        PFDValidation.requireText(currentFlow.input.topic, "Kafka input topic");
        for (Step step : currentFlow.steps) {
            PFDValidation.validateOperation(step.operation, step.params);
        }
        try (FileWriter writer = new FileWriter(filepath)) {
            writer.write(toJSON());
        }
    }

    public static class ProcessingFlowDescriptor {
        public String name;
        public String version;
        public Input input;
        public List<Step> steps;
        public Output output;
    }

    public static class Input {
        public String type;
        public String topic;
        public String schema;
    }

    public static class Step {
        public String id;
        public String operation;
        public Map<String, Object> params;
    }

    public static class Output {
        public String type;
        public String topic;
        public String path;
    }
}
