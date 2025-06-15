package com.example.retry;

import jakarta.enterprise.context.ApplicationScoped;

/** 500 ms fixos entre tentativas. */
@ApplicationScoped
public class FixedBackoff implements RetryPolicy {
    @Override
    public long nextDelay(int attempt) {
        return 500L;
    }
}
