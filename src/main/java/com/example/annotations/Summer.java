package com.example.annotations;

import java.lang.annotation.*;

/**
 * Dispara a geração de DTOs + adaptadores JAX-RS a partir de um contrato OpenAPI.
 * Suporta placeholders ${ENV:default}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Summer { // Renamed from ContractFrom
    /** Caminho ou URL do contrato YAML/JSON */
    String value();

    /** Namespace lógico que compõe o nome do canal */
    String cluster() default "";

    /**
     * SYNC → delega a Handler
     * ASYNC → delega a Channel (+ wrappers retry/CB/batch/DLQ)
     */
    Mode mode() default Mode.SYNC;

    // ---------- Retry ----------
    /** maxRetries() default 3; // ≤0 = ilimitado (apenas ASYNC) */
    int maxRetries() default 3;

    // ---------- Circuit Breaker (MicroProfile FT) ----------
    /** circuitBreaker() default false; */
    boolean circuitBreaker() default false;
    /** cbFailureThreshold() default 5; */
    int cbFailureThreshold() default 5;
    /** cbDelaySeconds() default 30; */
    int cbDelaySeconds() default 30;

    // ---------- Dead-Letter Queue ----------
    /** dlq() default ""; // canal DLQ lógico; "" desativa */
    String dlq() default "";

    // ---------- Batch ----------
    /** batchSize() default 1; // ≤1 desativa batch */
    int batchSize() default 1;
    /** batchInterval() default ""; // "" desativa; ex: "5s", "1000ms" */
    String batchInterval() default "";

    enum Mode {
        SYNC, ASYNC
    }
}
