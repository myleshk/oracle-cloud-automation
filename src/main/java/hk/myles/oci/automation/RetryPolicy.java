package hk.myles.oci.automation;

import com.oracle.bmc.model.BmcException;

import java.util.Locale;
import java.util.Random;

/**
 * Failure classification and delay calculation.
 *
 * <p>Capacity failures ("out of host capacity") are expected on Always Free shapes and are retried
 * at the fixed base interval. Rate limits (HTTP 429) and transient server errors double the delay
 * each time, up to a cap. All delays carry +/-20% jitter so a fleet of retriers does not
 * synchronize.
 */
public final class RetryPolicy {
    public enum FailureKind {
        CAPACITY,
        RATE_LIMIT,
        TRANSIENT,
        FATAL
    }

    private final long baseMillis;
    private final long maxMillis;
    private final Random random;

    public RetryPolicy(long baseMillis, long maxMillis) {
        this(baseMillis, maxMillis, new Random());
    }

    RetryPolicy(long baseMillis, long maxMillis, Random random) {
        if (baseMillis <= 0 || maxMillis < baseMillis) {
            throw new IllegalArgumentException("Invalid retry window: " + baseMillis + ".." + maxMillis);
        }
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
        this.random = random;
    }

    /**
     * @param kind classification of the last failure
     * @param consecutiveTransientFailures number of consecutive rate-limit/transient failures,
     *     starting at 1 for the first one
     */
    public long delayMillis(FailureKind kind, int consecutiveTransientFailures) {
        if (kind == FailureKind.CAPACITY) {
            return baseMillis;
        }
        int exponent = Math.max(0, consecutiveTransientFailures - 1);
        long raw = baseMillis;
        for (int i = 0; i < exponent && raw < maxMillis; i++) {
            raw = Math.min(maxMillis, raw * 2);
        }
        double jitter = 0.8 + 0.4 * random.nextDouble();
        long jittered = (long) (raw * jitter);
        return Math.max(baseMillis, Math.min(maxMillis, jittered));
    }

    public static FailureKind classify(BmcException e) {
        String text = (safe(e.getServiceCode()) + " " + safe(e.getMessage())).toLowerCase(Locale.ROOT);
        if (text.contains("out of host capacity") || text.contains("outofhostcapacity")) {
            return FailureKind.CAPACITY;
        }
        if (e.isTimeout() || e.getStatusCode() <= 0) {
            return FailureKind.TRANSIENT;
        }
        // Check 429 before quota strings: "rate limit exceeded" also contains "limit exceeded".
        if (e.getStatusCode() == 429) {
            return FailureKind.RATE_LIMIT;
        }
        if (text.contains("limitexceeded")
                || text.contains("service limits were exceeded")
                || text.contains("quota exceeded")) {
            return FailureKind.FATAL;
        }
        if (e.getStatusCode() >= 500) {
            return FailureKind.TRANSIENT;
        }
        return FailureKind.FATAL;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
