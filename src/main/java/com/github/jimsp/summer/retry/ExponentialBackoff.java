package com.github.jimsp.summer.retry;

import jakarta.enterprise.context.ApplicationScoped;

/** 200 ms, 400 ms, 800 ms … limitado a 5 s. */
@ApplicationScoped
public class ExponentialBackoff implements RetryPolicy {
    private static final long BASE = 200L;
    private static final long CAP = 5_000L;

    @Override
    public long nextDelay(int attempt) {
        long d = (long)(BASE * Math.pow(2, attempt-1));
        return Math.min(d, CAP);
    }
}
