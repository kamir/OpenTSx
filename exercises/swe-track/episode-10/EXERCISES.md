# Episode 10 Exercises: Production Configuration

**Learning Track:** SWE (Software Engineering - Production Focus)
**Difficulty:** Intermediate/Advanced
**Estimated Time:** 90 minutes
**Prerequisites:** Episodes 2-3, Java production experience

## Learning Objectives

By completing these exercises, you will:
- Implement production-ready configuration management
- Handle errors gracefully with retry logic
- Implement structured logging strategies
- Manage resources with proper cleanup
- Create thread-safe concurrent processing
- Implement monitoring and health checks

## Setup

Before starting:
1. Project built: `./bin/010_build.sh`
2. Episode 10 demo reviewed: `./bin/episode_10_production_config.sh`
3. Understand production deployment challenges

## Exercise 1: Configuration Hierarchy (20 minutes)

**Goal:** Implement multi-source configuration with proper precedence.

**Task:**
Create a configuration system that loads from:
1. Default values (hardcoded)
2. Configuration file (properties)
3. Environment variables (highest priority)

Test with different combinations to verify precedence.

**Starter Code:**
```java
package org.opentsx.exercises.production;

import java.io.*;
import java.util.Properties;

public class Exercise1_ConfigHierarchy {

    private static final String CONFIG_FILE = "app.properties";

    public static class AppConfig {
        // Configuration keys
        public static final String KAFKA_SERVERS = "kafka.bootstrap.servers";
        public static final String THREADS = "app.threads";
        public static final String TIMEOUT = "app.timeout.ms";

        // Default values
        private static final String DEFAULT_KAFKA = "localhost:9092";
        private static final int DEFAULT_THREADS = 4;
        private static final int DEFAULT_TIMEOUT = 5000;

        private Properties config;

        public AppConfig() throws IOException {
            // TODO: Load configuration with precedence:
            // 1. Start with defaults
            // 2. Override with file properties
            // 3. Override with environment variables
        }

        public String getString(String key, String defaultValue) {
            // TODO: Implement with precedence
            return null;
        }

        public int getInt(String key, int defaultValue) {
            // TODO: Implement with error handling
            return 0;
        }
    }

    public static void main(String[] args) {
        // Test configuration precedence
        // Create app.properties with test values
        // Set environment variables
        // Verify correct precedence
    }
}
```

**Verification:**
- Default < File < Environment
- Invalid values fall back gracefully
- Clear logging shows which source was used

---

## Exercise 2: Retry Logic with Exponential Backoff (20 minutes)

**Goal:** Implement production-grade retry logic for transient failures.

**Task:**
Create a retry utility that:
1. Retries failed operations up to N times
2. Uses exponential backoff (1s, 2s, 4s, 8s...)
3. Logs each attempt
4. Distinguishes permanent vs. transient failures

**Starter Code:**
```java
package org.opentsx.exercises.production;

import java.util.logging.Logger;

public class Exercise2_RetryLogic {

    private static final Logger logger = Logger.getLogger(Exercise2_RetryLogic.class.getName());

    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }

    /**
     * Execute operation with exponential backoff retry
     *
     * @param operation The operation to retry
     * @param maxAttempts Maximum number of attempts
     * @param baseDelay Base delay in milliseconds (doubles each retry)
     * @return Result of successful operation
     * @throws Exception if all retries exhausted
     */
    public static <T> T executeWithRetry(
            RetryableOperation<T> operation,
            int maxAttempts,
            long baseDelay) throws Exception {

        // TODO: Implement retry logic
        // 1. Try operation
        // 2. If fails, wait (baseDelay * 2^attempt)
        // 3. Retry up to maxAttempts
        // 4. Log each attempt

        return null;
    }

    public static void main(String[] args) {
        // Test 1: Operation succeeds on 3rd attempt
        RetryableOperation<String> flakeyOp = new RetryableOperation<String>() {
            private int attempts = 0;

            public String execute() throws Exception {
                attempts++;
                if (attempts < 3) {
                    throw new Exception("Transient failure #" + attempts);
                }
                return "Success!";
            }
        };

        // Test 2: Operation always fails (permanent failure)

        // Test 3: Network-like operation with realistic timeouts
    }
}
```

**Expected Behavior:**
- First failure: retry after 1s
- Second failure: retry after 2s
- Third failure: retry after 4s
- Clear logging of each attempt

---

## Exercise 3: Structured Logging (25 minutes)

**Goal:** Implement production logging with multiple handlers and levels.

**Task:**
Create a logging system that:
1. Logs to console (INFO and above)
2. Logs to file (ALL levels)
3. Uses different formatters for each
4. Implements log rotation
5. Supports contextual logging (MDC-like)

**Starter Code:**
```java
package org.opentsx.exercises.production;

import java.util.logging.*;
import java.io.IOException;

public class Exercise3_StructuredLogging {

    public static class LoggingConfig {

        public static void setup(String logFile, Level consoleLevel, Level fileLevel)
                throws IOException {

            Logger rootLogger = Logger.getLogger("");

            // TODO: Remove default handlers

            // TODO: Setup console handler

            // TODO: Setup file handler with rotation
            // Hint: Use FileHandler with limit and count parameters

            // TODO: Set custom formatters
        }

        /**
         * Custom formatter with timestamp, level, class, message
         */
        public static class CustomFormatter extends Formatter {
            @Override
            public String format(LogRecord record) {
                // TODO: Format as: [timestamp] [LEVEL] [ClassName.method] message
                return null;
            }
        }
    }

    public static void main(String[] args) {
        try {
            // Setup logging
            LoggingConfig.setup("app.log", Level.INFO, Level.ALL);

            Logger logger = Logger.getLogger(Exercise3_StructuredLogging.class.getName());

            // Test different log levels
            logger.finest("FINEST - detailed trace");
            logger.finer("FINER - trace");
            logger.fine("FINE - debug");
            logger.info("INFO - informational");
            logger.warning("WARNING - warning");
            logger.severe("SEVERE - error");

            // Verify:
            // - Console shows INFO, WARNING, SEVERE
            // - File shows ALL levels

        } catch (IOException e) {
            System.err.println("Failed to setup logging: " + e.getMessage());
        }
    }
}
```

---

## Exercise 4: Thread-Safe Resource Pool (25 minutes)

**Goal:** Implement a thread-safe resource pool with proper lifecycle management.

**Task:**
Create a generic resource pool that:
1. Pre-creates resources up to pool size
2. Thread-safe borrow/return
3. Resource validation before use
4. Proper cleanup on shutdown
5. Metrics (active, idle, total)

**Starter Code:**
```java
package org.opentsx.exercises.production;

import java.util.concurrent.*;
import java.util.logging.Logger;

public class Exercise4_ResourcePool<T> {

    private static final Logger logger = Logger.getLogger(Exercise4_ResourcePool.class.getName());

    public interface ResourceFactory<T> {
        T create() throws Exception;
        boolean validate(T resource);
        void destroy(T resource);
    }

    private final BlockingQueue<T> availableResources;
    private final Set<T> activeResources;
    private final ResourceFactory<T> factory;
    private final int maxSize;

    public Exercise4_ResourcePool(ResourceFactory<T> factory, int poolSize) {
        // TODO: Initialize pool
        this.factory = factory;
        this.maxSize = poolSize;
        this.availableResources = new LinkedBlockingQueue<>(poolSize);
        this.activeResources = ConcurrentHashMap.newKeySet();

        // TODO: Pre-create resources
    }

    /**
     * Borrow resource from pool (blocking if none available)
     */
    public T borrow() throws InterruptedException {
        // TODO: Implement thread-safe borrow
        // 1. Take from available queue
        // 2. Validate resource
        // 3. If invalid, create new one
        // 4. Track as active
        return null;
    }

    /**
     * Return resource to pool
     */
    public void returnResource(T resource) {
        // TODO: Implement thread-safe return
        // 1. Remove from active
        // 2. Validate resource
        // 3. Return to available queue or destroy
    }

    /**
     * Shutdown pool and cleanup all resources
     */
    public void shutdown() {
        // TODO: Cleanup all resources
    }

    /**
     * Get pool statistics
     */
    public PoolStats getStats() {
        // TODO: Return active, idle, total counts
        return null;
    }

    public static class PoolStats {
        public int active;
        public int idle;
        public int total;
    }

    // Example usage
    public static void main(String[] args) throws Exception {
        // Create pool of database connections (simulated)
        ResourceFactory<Connection> connFactory = new ResourceFactory<Connection>() {
            public Connection create() {
                return new Connection();
            }

            public boolean validate(Connection conn) {
                return conn.isValid();
            }

            public void destroy(Connection conn) {
                conn.close();
            }
        };

        Exercise4_ResourcePool<Connection> pool =
            new Exercise4_ResourcePool<>(connFactory, 10);

        // Test concurrent access
        ExecutorService executor = Executors.newFixedThreadPool(20);

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    Connection conn = pool.borrow();
                    // Use connection
                    Thread.sleep(100);
                    pool.returnResource(conn);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        System.out.println("Pool stats: " + pool.getStats());
        pool.shutdown();
    }

    // Mock connection class
    static class Connection {
        private boolean valid = true;
        boolean isValid() { return valid; }
        void close() { valid = false; }
    }
}
```

---

## Bonus Exercise: Health Check Endpoint (Optional, 25 minutes)

**Goal:** Implement application health checks for monitoring.

**Task:**
Create a health check system that monitors:
1. JVM metrics (memory, threads)
2. External dependencies (Kafka, database)
3. Application-specific metrics
4. Returns JSON health status

**Starter Code:**
```java
public class BonusExercise_HealthCheck {
    // Implement health check aggregator
    // Check: memory usage, thread count, disk space
    // Check: Kafka connectivity, DB connectivity
    // Return: { "status": "UP/DOWN", "checks": [...] }
}
```

---

## Validation Checklist

- [ ] Configuration loads from multiple sources with correct precedence
- [ ] Retry logic implements exponential backoff correctly
- [ ] Logging writes to both console and file with appropriate levels
- [ ] Resource pool handles concurrent access safely
- [ ] All resources cleaned up on shutdown
- [ ] No thread safety issues under load

## Production Checklist

When deploying to production, ensure:
- [ ] Configuration externalized (no hardcoded values)
- [ ] Secrets not in configuration files
- [ ] Structured logging with correlation IDs
- [ ] Graceful shutdown implemented
- [ ] Resource limits configured
- [ ] Health checks exposed
- [ ] Metrics collected
- [ ] Error handling comprehensive

## Next Steps

1. Review solutions
2. Apply patterns to real OpenTSx deployment
3. Add monitoring and alerting
4. Implement CI/CD pipeline
5. Load test configuration

## Resources

- [Best Practices](../../docs/manual/best-practices/README.md)
- [Production Deployment](../../DEPLOYMENT.md)
- [Local Development Guide](../../docs/infrastructure/local-development.md)

---

**Production Tips:**
- Always test retry logic with real failure scenarios
- Log aggregation is critical (ELK stack, Splunk)
- Monitor pool exhaustion and tune sizes
- Implement circuit breakers for external dependencies
