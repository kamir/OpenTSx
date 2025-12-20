package org.opentsx.demo.onboarding;

import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.loader.MessreihenLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;

/**
 * Production Configuration Patterns for OpenTSx
 *
 * Demonstrates best practices for production deployments:
 * - Configuration management from files and environment
 * - Structured logging
 * - Resource pooling and cleanup
 * - Error handling and recovery
 * - Thread pool management
 *
 * Episode 10: Production Deployment
 * Target Audience: SWE Track (production engineering)
 *
 * Learning Objectives:
 * - Externalize configuration for different environments
 * - Implement proper resource cleanup
 * - Handle errors gracefully with retries
 * - Configure logging for production monitoring
 * - Manage thread pools for concurrent processing
 *
 * @author OpenTSx Team
 */
public class ProductionConfig {

    private static final Logger logger = Logger.getLogger(ProductionConfig.class.getName());

    // Configuration keys
    private static final String CONFIG_FILE = "opentsx.properties";
    private static final String DATA_DIR_KEY = "opentsx.data.directory";
    private static final String THREAD_POOL_SIZE_KEY = "opentsx.threads.pool.size";
    private static final String RETRY_ATTEMPTS_KEY = "opentsx.retry.attempts";
    private static final String LOG_LEVEL_KEY = "opentsx.log.level";

    // Default values (used if not configured)
    private static final String DEFAULT_DATA_DIR = "/var/lib/opentsx/data";
    private static final int DEFAULT_THREAD_POOL_SIZE = 4;
    private static final int DEFAULT_RETRY_ATTEMPTS = 3;
    private static final String DEFAULT_LOG_LEVEL = "INFO";

    private final Properties config;
    private final ExecutorService threadPool;

    /**
     * Initialize production configuration.
     * Loads from: config file → environment variables → defaults
     */
    public ProductionConfig() throws IOException {
        // Configure logging first
        setupLogging();

        // Load configuration
        this.config = loadConfiguration();

        // Initialize thread pool with configured size
        int poolSize = getIntProperty(THREAD_POOL_SIZE_KEY, DEFAULT_THREAD_POOL_SIZE);
        this.threadPool = Executors.newFixedThreadPool(poolSize);

        logger.info("ProductionConfig initialized with thread pool size: " + poolSize);
    }

    /**
     * Setup structured logging for production.
     * Logs to both console and file with different levels.
     */
    private void setupLogging() {
        try {
            // Get log level from environment or use default
            String logLevel = System.getenv(LOG_LEVEL_KEY);
            if (logLevel == null) {
                logLevel = DEFAULT_LOG_LEVEL;
            }
            Level level = Level.parse(logLevel);

            // Remove default handlers
            Logger rootLogger = Logger.getLogger("");
            for (Handler handler : rootLogger.getHandlers()) {
                rootLogger.removeHandler(handler);
            }

            // Console handler (INFO and above)
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.INFO);
            consoleHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(consoleHandler);

            // File handler (ALL levels for debugging)
            FileHandler fileHandler = new FileHandler("opentsx.log", true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(fileHandler);

            rootLogger.setLevel(level);

            logger.info("Logging configured with level: " + logLevel);

        } catch (IOException e) {
            System.err.println("Failed to setup file logging: " + e.getMessage());
        }
    }

    /**
     * Load configuration from multiple sources with precedence:
     * 1. Environment variables (highest priority)
     * 2. Configuration file (opentsx.properties)
     * 3. Default values (lowest priority)
     */
    private Properties loadConfiguration() throws IOException {
        Properties props = new Properties();

        // Load from file if exists
        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
                logger.info("Loaded configuration from: " + configFile.getAbsolutePath());
            }
        } else {
            logger.warning("Configuration file not found: " + CONFIG_FILE +
                         " (using defaults and environment variables)");
        }

        // Override with environment variables
        for (String key : props.stringPropertyNames()) {
            String envValue = System.getenv(key.replace('.', '_').toUpperCase());
            if (envValue != null) {
                props.setProperty(key, envValue);
                logger.info("Overriding " + key + " from environment variable");
            }
        }

        // Set defaults for missing keys
        props.putIfAbsent(DATA_DIR_KEY, DEFAULT_DATA_DIR);
        props.putIfAbsent(THREAD_POOL_SIZE_KEY, String.valueOf(DEFAULT_THREAD_POOL_SIZE));
        props.putIfAbsent(RETRY_ATTEMPTS_KEY, String.valueOf(DEFAULT_RETRY_ATTEMPTS));
        props.putIfAbsent(LOG_LEVEL_KEY, DEFAULT_LOG_LEVEL);

        return props;
    }

    /**
     * Get string property with fallback to default.
     */
    public String getProperty(String key, String defaultValue) {
        return config.getProperty(key, defaultValue);
    }

    /**
     * Get integer property with fallback to default.
     */
    public int getIntProperty(String key, int defaultValue) {
        String value = config.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer for " + key + ": " + value +
                         " (using default: " + defaultValue + ")");
            return defaultValue;
        }
    }

    /**
     * Load time series data with retry logic and error handling.
     * Production pattern: fail gracefully, log errors, retry transient failures.
     */
    public TimeSeriesObject loadTimeSeriesWithRetry(File file) {
        int maxAttempts = getIntProperty(RETRY_ATTEMPTS_KEY, DEFAULT_RETRY_ATTEMPTS);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                logger.info("Loading time series from: " + file.getName() +
                          " (attempt " + attempt + "/" + maxAttempts + ")");

                MessreihenLoader loader = MessreihenLoader.getLoader();
                loader.delim = ",";

                TimeSeriesObject ts = loader.loadMessreihe_2(file, 1, 2);

                if (ts == null || ts.yValues.size() == 0) {
                    logger.warning("Loaded empty time series from: " + file.getName());
                    return null;
                }

                logger.info("Successfully loaded " + ts.yValues.size() +
                          " points from: " + file.getName());
                return ts;

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to load time series (attempt " +
                         attempt + "/" + maxAttempts + "): " + e.getMessage(), e);

                if (attempt < maxAttempts) {
                    // Exponential backoff: 1s, 2s, 4s
                    int backoffMs = 1000 * (int)Math.pow(2, attempt - 1);
                    logger.info("Retrying in " + backoffMs + "ms...");
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warning("Retry interrupted");
                        return null;
                    }
                } else {
                    logger.severe("All retry attempts exhausted for: " + file.getName());
                }
            }
        }

        return null;
    }

    /**
     * Process multiple time series files concurrently using thread pool.
     * Production pattern: parallel processing with controlled concurrency.
     */
    public void processFilesInParallel(File[] files) {
        logger.info("Processing " + files.length + " files in parallel");

        for (File file : files) {
            threadPool.submit(() -> {
                try {
                    TimeSeriesObject ts = loadTimeSeriesWithRetry(file);

                    if (ts != null) {
                        // Process the time series
                        double mean = ts.getAvarage();
                        double stddev = ts.getStddev();

                        logger.info(String.format(
                            "Processed %s: points=%d, mean=%.2f, stddev=%.2f",
                            file.getName(), ts.yValues.size(), mean, stddev
                        ));

                        // Save normalized version
                        TimeSeriesObject normalized = ts.normalizeToStdevIsOne();
                        File outputFile = new File("processed_" + file.getName());
                        normalized.writeToFile(outputFile, ',');

                        logger.info("Saved normalized data to: " + outputFile.getName());
                    }

                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error processing file: " +
                             file.getName(), e);
                }
            });
        }
    }

    /**
     * Graceful shutdown with timeout.
     * Production pattern: ensure all tasks complete or timeout gracefully.
     */
    public void shutdown() {
        logger.info("Initiating graceful shutdown...");

        threadPool.shutdown();

        try {
            // Wait up to 30 seconds for tasks to complete
            if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warning("Thread pool did not terminate in time, forcing shutdown");
                threadPool.shutdownNow();

                // Wait a bit more for forced shutdown
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.severe("Thread pool did not terminate after forced shutdown");
                }
            }

            logger.info("Shutdown complete");

        } catch (InterruptedException e) {
            logger.warning("Shutdown interrupted, forcing immediate termination");
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Main demonstration of production configuration patterns.
     */
    public static void main(String[] args) {
        ProductionConfig config = null;

        try {
            // Initialize production configuration
            config = new ProductionConfig();

            // Display configuration
            System.out.println("Production Configuration:");
            System.out.println("  Data Directory: " +
                config.getProperty(DATA_DIR_KEY, DEFAULT_DATA_DIR));
            System.out.println("  Thread Pool Size: " +
                config.getIntProperty(THREAD_POOL_SIZE_KEY, DEFAULT_THREAD_POOL_SIZE));
            System.out.println("  Retry Attempts: " +
                config.getIntProperty(RETRY_ATTEMPTS_KEY, DEFAULT_RETRY_ATTEMPTS));
            System.out.println();

            // Example: Load a single file with retry
            File sampleFile = new File("sample_data/sensor_data.csv");
            if (sampleFile.exists()) {
                TimeSeriesObject ts = config.loadTimeSeriesWithRetry(sampleFile);
                if (ts != null) {
                    System.out.println("Loaded time series with " +
                        ts.yValues.size() + " data points");
                    System.out.println("  Mean: " + ts.getAvarage());
                    System.out.println("  Std Dev: " + ts.getStddev());
                }
            } else {
                logger.warning("Sample file not found: " + sampleFile.getPath());
                System.out.println("To test with real data, place CSV files in sample_data/");
            }

            // Example: Process multiple files in parallel
            File dataDir = new File("sample_data");
            if (dataDir.exists() && dataDir.isDirectory()) {
                File[] csvFiles = dataDir.listFiles((dir, name) ->
                    name.toLowerCase().endsWith(".csv"));

                if (csvFiles != null && csvFiles.length > 0) {
                    System.out.println("\nProcessing " + csvFiles.length +
                        " files in parallel...");
                    config.processFilesInParallel(csvFiles);

                    // Wait a bit for processing to show results
                    Thread.sleep(2000);
                }
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Fatal error in production configuration demo", e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();

        } finally {
            // Always cleanup resources
            if (config != null) {
                config.shutdown();
            }
        }
    }
}
