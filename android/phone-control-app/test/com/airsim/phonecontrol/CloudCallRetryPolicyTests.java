package com.airsim.phonecontrol;

import java.io.IOException;
import java.net.SocketTimeoutException;

public final class CloudCallRetryPolicyTests {
    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }
    private static void expect(String actual, String expected) {
        check(expected.equals(actual), "expected <" + expected + "> but was <" + actual + ">");
    }
    private static RelayHTTPException http(int status) { return new RelayHTTPException(status, ""); }
    private static RelayHTTPException http(int status, String body) { return new RelayHTTPException(status, body); }

    public static void main(String[] args) {
        for (int status : new int[]{400, 403, 404, 409, 410, 422}) {
            check(CloudCallRetryPolicy.eventRejected(http(status)), "call event " + status + " is final");
        }
        for (int status : new int[]{401, 408, 429, 500, 502, 503}) {
            check(!CloudCallRetryPolicy.eventRejected(http(status)), "call event " + status + " is retryable");
        }
        check(!CloudCallRetryPolicy.eventRejected(new IOException("offline")), "network failure is retryable");
        check(!CloudCallRetryPolicy.eventRejected(new SocketTimeoutException("Read timed out")), "timeout is retryable");
        check(!CloudCallRetryPolicy.eventRejected(null), "missing error is retryable");
        // The media handshake authenticates with the per-call secret, so 401 there means the call is gone.
        for (int status : new int[]{400, 401, 404, 410, 426}) {
            check(CloudCallRetryPolicy.mediaRejected(http(status)), "media handshake " + status + " is final");
        }
        for (int status : new int[]{408, 429, 500, 503}) {
            check(!CloudCallRetryPolicy.mediaRejected(http(status)), "media handshake " + status + " is retryable");
        }
        check(!CloudCallRetryPolicy.mediaRejected(new IOException("reset")), "media network failure is retryable");
        check(!CloudCallRetryPolicy.mediaRejected(null), "missing media error is retryable");

        var p = new CloudCallRetryPolicy();
        check(p.shouldDrop("a", http(409), 0), "a final rejection drops the event at once");
        check(!p.shouldDrop("b", http(500), 0), "a 5xx keeps the event");
        check(!p.shouldDrop("b", new IOException("offline"), 59_999), "still inside the 60 s window");
        check(p.shouldDrop("b", http(500), 60_000), "the event gives up 60 s after its first failure");
        check(!p.shouldDrop("c", http(500), 60_000), "the next event starts its own window");
        check(!p.shouldDrop("c", http(500), 119_999), "the next event is still inside its own window");
        check(p.shouldDrop("c", http(500), 120_000), "the next event gives up 60 s after its own first failure");

        var q = new CloudCallRetryPolicy();
        check(!q.shouldDrop("d", http(500), 0), "d is kept");
        // d is delivered on its next attempt; e then fails for the first time.
        check(!q.shouldDrop("e", http(500), 59_000), "the window belongs to the event, not to the policy");

        var r = new CloudCallRetryPolicy();
        check(!r.shouldDrop("f", http(500), 0), "f is kept");
        check(r.shouldDrop("f", http(404), 30_000), "a final rejection ends the window early");

        expect(CloudCallRetryPolicy.describe(http(409,
                        "{\"accepted\":false,\"reason\":\"illegal_transition\",\"lifecycle\":{\"call_id\":\"android-1\"}}")),
                "type=RelayHTTPException status=409 reason=illegal_transition");
        expect(CloudCallRetryPolicy.describe(http(404, "{\"error\":\"call not found\"}")),
                "type=RelayHTTPException status=404 reason=call_not_found");
        expect(CloudCallRetryPolicy.describe(http(409, "{\"error\":\"conflict\",\"reason\":\"stale_generation\"}")),
                "type=RelayHTTPException status=409 reason=stale_generation");
        expect(CloudCallRetryPolicy.describe(http(401, "WebSocket handshake rejected")),
                "type=RelayHTTPException status=401");
        expect(CloudCallRetryPolicy.describe(http(500, "")), "type=RelayHTTPException status=500");
        expect(CloudCallRetryPolicy.describe(http(400, "{\"error\":\"bad number 13800138000\"}")),
                "type=RelayHTTPException status=400 reason=bad_number");
        expect(CloudCallRetryPolicy.describe(http(400, "{\"error\":\"" + "x".repeat(80) + "\"}")),
                "type=RelayHTTPException status=400 reason=" + "x".repeat(40));
        expect(CloudCallRetryPolicy.describe(new SocketTimeoutException("Read timed out")),
                "type=SocketTimeoutException");
        expect(CloudCallRetryPolicy.describe(null), "type=unknown");
        System.out.println("CloudCallRetryPolicyTests passed");
    }
}
