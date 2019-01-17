package connectors.flume;

import org.apache.flume.Context;
import org.apache.flume.Event;

public interface MessagePreprocessor {

        /**
         * Extract a key from the message and/or Flume runtime.
         * @param event This is the Flume event that will be sent to Kafka
         * @param context The Flume runtime context.
         * @return Key extracted based on the implemented logic
         */
        public String extractKey(Event event, Context context);

        /**
         * Extract a topic for the message
         * @param event This is the Flume event that will be sent to Kafka
         * @param context The Flume runtime context.
         * @return topic extracted based on the implemented logic
         */
        public String extractTopic(Event event, Context context);

        /**
         * Prepare message for publishing. This allows users to modify the message body,
         * augment it with header information coming from Flume, etc.
         * @param event Flume event received by the sink.
         * @param context Flume context
         * @return message that will be published into Kafka
         */
        public String transformMessage(Event event, Context context);

}
