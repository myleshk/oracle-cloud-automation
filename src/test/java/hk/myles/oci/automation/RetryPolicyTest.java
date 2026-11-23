package hk.myles.oci.automation;

import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static hk.myles.oci.automation.RetryPolicy.FailureKind.CAPACITY;
import static hk.myles.oci.automation.RetryPolicy.FailureKind.FATAL;
import static hk.myles.oci.automation.RetryPolicy.FailureKind.RATE_LIMIT;
import static hk.myles.oci.automation.RetryPolicy.FailureKind.TRANSIENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {
    private static final long BASE = 60_000;
    private static final long CAP = 1_800_000;

    @Test
    void capacityAlwaysWaitsTheBaseInterval() {
        RetryPolicy policy = new RetryPolicy(BASE, CAP, new Random(7));
        assertEquals(BASE, policy.delayMillis(CAPACITY, 0));
        assertEquals(BASE, policy.delayMillis(CAPACITY, 12));
    }

    @Test
    void rateLimitBacksOffExponentially() {
        RetryPolicy policy = new RetryPolicy(BASE, CAP, new Random(7));
        assertBetween(policy.delayMillis(RATE_LIMIT, 1), BASE, 72_000);
        assertBetween(policy.delayMillis(RATE_LIMIT, 2), 96_000, 144_000);
        assertBetween(policy.delayMillis(RATE_LIMIT, 3), 192_000, 288_000);
    }

    @Test
    void backoffIsCapped() {
        RetryPolicy policy = new RetryPolicy(BASE, CAP, new Random(7));
        long delay = policy.delayMillis(TRANSIENT, 20);
        assertTrue(delay <= CAP, "delay " + delay + " exceeded cap");
        assertTrue(delay >= (long) (CAP * 0.8), "delay " + delay + " below jitter floor");
    }

    @Test
    void rejectsInvalidWindow() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, CAP));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(BASE, BASE - 1));
    }

    @Test
    void classifiesCapacity() {
        assertEquals(CAPACITY, RetryPolicy.classify(exception(500, "InternalError", "Out of host capacity.")));
        assertEquals(CAPACITY, RetryPolicy.classify(exception(500, "InternalError", "OUT OF HOST CAPACITY")));
    }

    @Test
    void classifiesRateLimit() {
        assertEquals(RATE_LIMIT, RetryPolicy.classify(exception(429, "TooManyRequests", "Too many requests")));
        assertEquals(RATE_LIMIT, RetryPolicy.classify(
                exception(429, "TooManyRequests", "Rate limit exceeded, slow down")));
    }

    @Test
    void classifiesTransientServerErrors() {
        assertEquals(TRANSIENT, RetryPolicy.classify(exception(500, "InternalError", "Internal error")));
        assertEquals(TRANSIENT, RetryPolicy.classify(exception(503, "ServiceUnavailable", "Try later")));
        assertEquals(TRANSIENT, RetryPolicy.classify(exception(-1, null, "connect timed out")));
    }

    @Test
    void classifiesPermanentErrorsAsFatal() {
        assertEquals(FATAL, RetryPolicy.classify(exception(400, "InvalidParameter", "Bad vpus")));
        assertEquals(FATAL, RetryPolicy.classify(exception(401, "NotAuthenticated", "Bad key")));
        assertEquals(FATAL, RetryPolicy.classify(exception(404, "NotAuthorizedOrNotFound", "No subnet")));
    }

    @Test
    void treatsServiceLimitErrorsAsFatal() {
        assertEquals(FATAL, RetryPolicy.classify(
                exception(400, "LimitExceeded", "The following service limits were exceeded: standard-a1-core-count")));
    }

    private static BmcException exception(int status, String code, String message) {
        return new BmcException(status, code, message, "request-id");
    }

    private static void assertBetween(long actual, long min, long max) {
        assertTrue(actual >= min && actual <= max, actual + " not in [" + min + ", " + max + "]");
    }
}
