import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class EchoService {
    private static final String DEFAULT_CONFIG_FILE_PATH = "../resources/kafka.properties";
    private static final String DEFAULT_APP_FILE_PATH = "../resources/app.properties";
    private static final String INPUT_TOPIC_PROP = "klatency.request_topic";
    private static final String OUTPUT_TOPIC_PROP = "klatency.reponse_topic";

    public static void main(String[] args) throws Exception{
        boolean loadDefaults = false;
        if(args.length == 1) {
            System.out.println("App config not specified, applying defaults =>" + DEFAULT_APP_FILE_PATH);
            loadDefaults = true;
        }else if(args.length == 0) {
            System.out.println("Kafka config not specified, applying defaults =>" + DEFAULT_CONFIG_FILE_PATH);
            System.out.println("App config not specified, applying defaults =>" + DEFAULT_APP_FILE_PATH);
            loadDefaults = true;
        }
        Properties kafkaConfig = new Properties();
        Properties appConfig = new Properties();

        if(loadDefaults) {
            kafkaConfig = loadConfig(DEFAULT_CONFIG_FILE_PATH);
            appConfig = loadConfig(DEFAULT_APP_FILE_PATH);
        } else {
            kafkaConfig = loadConfig(args[0]);
            appConfig = loadConfig(args[1]);
        }

        EchoServiceProducer echoProducer = new EchoServiceProducer(kafkaConfig, appConfig.getProperty(OUTPUT_TOPIC_PROP));
        EchoServiceConsumer echoConsumer = new EchoServiceConsumer(kafkaConfig);

        echoConsumer.create(appConfig.getProperty(INPUT_TOPIC_PROP));

        echoConsumer.consume(echoProducer);

        Runtime.getRuntime().addShutdownHook(new Thread(()-> {
            echoConsumer.stop();
            echoProducer.close();
        }));
    }

    private static Properties loadConfig(String filePath) throws IOException {
        if(!Files.exists(Paths.get(filePath))){
            throw new IOException("File" + filePath + "does not exists");
        }

        final Properties config = new Properties();
        try(InputStream inputStream = new FileInputStream(filePath)) {
           config.load(inputStream);
        }

        return config;
    }
}
