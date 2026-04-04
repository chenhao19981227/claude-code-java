package com.claude.code.api;

import java.util.Random;
import java.util.concurrent.TimeUnit;

public class RetryHandler {
    private final int maxRetries;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterFactor;
    private final Random random = new Random();

    public RetryHandler() {
        this(3, 1000, 30000, 0.5);
    }

    public RetryHandler(int maxRetries, long baseDelayMs, long maxDelayMs, double jitterFactor) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitterFactor = jitterFactor;
    }

    public <T> T execute(CallableWithException<T> action) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (AnthropicApiException e) {
                lastException = e;
                if (!isRetryable(e)) {
                    throw e;
                }
                if (attempt == maxRetries) {
                    throw e;
                }
                long delay = calculateDelay(attempt);
                sleep(delay);
            } catch (java.io.IOException e) {
                lastException = e;
                if (attempt == maxRetries) {
                    throw e;
                }
                long delay = calculateDelay(attempt);
                sleep(delay);
            }
        }
        throw lastException;
    }

    public void executeVoid(CallableWithException<Void> action) throws Exception {
        execute(action);
    }

    public boolean isRetryable(AnthropicApiException e) {
        return e.isRateLimit() || e.isOverloaded() || e.isPromptTooLong()
            || e.getStatusCode() >= 500;
    }

    private long calculateDelay(int attempt) {
        long delay = (long) (baseDelayMs * Math.pow(2, attempt));
        if (jitterFactor > 0) {
            long jitter = (long) (delay * jitterFactor * random.nextDouble());
            delay = delay + jitter;
        }
        return Math.min(delay, maxDelayMs);
    }

    private void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public interface CallableWithException<T> {
        T call() throws Exception;
    }
}
