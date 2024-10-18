@Singleton
public class SegmentAnalytics {
    private static final Logger log = LoggerFactory.getLogger(SegmentAnalytics.class);
    private final Analytics analytics;
    private final BlockingFlush blockingFlush;
    public SegmentAnalytics(
            @Value("${segment.write.key}") String writeKey,
            @Value("${segment.timeout:30}") int timeout,
            BlockingFlush blockingFlush) {
        this.blockingFlush = blockingFlush;
        this.analytics = Analytics.builder(writeKey)
                .plugin(new LoggingPlugin())
                .plugin(blockingFlush.plugin())
                .client(new OkHttpClient.Builder()
                        .connectTimeout(timeout, TimeUnit.SECONDS)
                        .readTimeout(timeout, TimeUnit.SECONDS)
                        .writeTimeout(timeout, TimeUnit.SECONDS)
                        .addInterceptor(new GzipRequestInterceptor())
                        .addInterceptor(new HttpLoggingInterceptor(log::debug).setLevel(HttpLoggingInterceptor.Level.BODY))
                        .build())
                .build();
    }
    public void createSegmentEvent(Purchase purchase) {
        try {
            prepareCommonIdentifyEvent(analytics, purchase);
            prepareOrderCompletedTrackEvent(analytics, purchase);
            log.info("Initiating flush for analytics...");
            flushWithRetry();
        } catch (Exception e) {
            log.error("Failed to create Segment event", e);
            // Consider how you want to handle this exception (e.g., rethrow, alert, etc.)
        }
    }
    private void flushWithRetry() {
        int maxRetries = 5;
        long initialBackoff = 1000; // 1 second
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                CompletableFuture<Void> flushFuture = CompletableFuture.runAsync(() -> {
                    try {
                        analytics.flush();
                        blockingFlush.block();
                    } catch (Exception e) {
                        throw new RuntimeException("Error during analytics flush", e);
                    }
                });
                flushFuture.get(60, TimeUnit.SECONDS);
                log.info("Flush completed successfully.");
                return;
            } catch (Exception e) {
                log.error("Flush operation failed on attempt {}. Error: {}", attempt + 1, e.getMessage());
                if (attempt == maxRetries - 1) {
                    throw new RuntimeException("Failed to flush after " + maxRetries + " retries.", e);
                }
                try {
                    long backoff = initialBackoff * (long) Math.pow(2, attempt);
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
    }
    @PreDestroy
    public void cleanup() {
        log.info("Shutting down analytics...");
        analytics.shutdown();
    }
}