package com.example.retry;

/** Devolve atraso (ms) antes da próxima tentativa; ≤0 encerra o retry. */
public interface RetryPolicy {
    long nextDelay(int attempt);
}
