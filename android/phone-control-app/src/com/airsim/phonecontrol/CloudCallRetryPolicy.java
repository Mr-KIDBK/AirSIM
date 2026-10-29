package com.airsim.phonecontrol;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounds how long a cloud call event or media session keeps retrying the Relay. A 4xx other than
 * 408/429 answers the same request the same way every time (call ended or expired, illegal
 * lifecycle transition), so it is final at once. Anything else may clear up, but one event must
 * not hold the in-memory FIFO, and the "ended" queued behind it, for more than a minute.
 */
final class CloudCallRetryPolicy {
    static final long GIVE_UP_AFTER_MILLIS = 60_000L;
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    private String head;
    private long firstFailureAt;

    /** True when {@code event}, which just failed with {@code error}, must leave the queue now. */
    synchronized boolean shouldDrop(String event, Throwable error, long nowMillis) {
        if (!event.equals(head)) { head = event; firstFailureAt = nowMillis; }
        if (!eventRejected(error) && nowMillis - firstFailureAt < GIVE_UP_AFTER_MILLIS) return false;
        head = null;
        return true;
    }

    /** The Relay refused this call event and will refuse it again; 401 is left to re-registration. */
    static boolean eventRejected(Throwable error) {
        return error instanceof RelayHTTPException http && http.statusCode() != 401 && finalStatus(http.statusCode());
    }

    /** The Relay no longer has this call, so the per-call media secret will be refused again. */
    static boolean mediaRejected(Throwable error) {
        return error instanceof RelayHTTPException http && finalStatus(http.statusCode());
    }

    /** Log-safe: exception type, HTTP status and the Relay's short reason, never the body itself. */
    static String describe(Throwable error) {
        if (error == null) return "type=unknown";
        String result = "type=" + error.getClass().getSimpleName();
        if (!(error instanceof RelayHTTPException http)) return result;
        result += " status=" + http.statusCode();
        String reason = reason(http.detailBody());
        return reason.isEmpty() ? result : result + " reason=" + reason;
    }

    private static boolean finalStatus(int status) {
        return status >= 400 && status < 500 && status != 408 && status != 429;
    }

    private static String reason(String body) {
        Matcher match = REASON.matcher(body);
        if (!match.find()) {
            match = ERROR.matcher(body);
            if (!match.find()) return "";
        }
        // Letters only: whatever the Relay echoes, digits (numbers, ids) never reach the log.
        String value = match.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "_").replaceAll("^_+|_+$", "");
        return value.length() > 40 ? value.substring(0, 40) : value;
    }
}
