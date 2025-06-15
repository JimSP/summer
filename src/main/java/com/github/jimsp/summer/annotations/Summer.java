package com.github.jimsp.summer.annotations;

import java.lang.annotation.*;

/** Drives code generation for DTOs + JAX-RS + messaging pipelines. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Summer {

    /* ---------- contract / basics ---------- */
    String value();                     // OpenAPI YAML/JSON
    String cluster()        default "";
    enum Mode { SYNC, ASYNC }
    Mode   mode()           default Mode.SYNC;

    /* ---------- resiliency ---------- */
    int     maxRetries()          default 3;
    boolean circuitBreaker()      default false;
    int     cbFailureThreshold()  default 5;
    int     cbDelaySeconds()      default 30;
    String  dlq()                 default "";
    int     batchSize()           default 1;
    String  batchInterval()       default "";

    /* ---------- package config (v0.2) ---------- */
    String  basePackage()         default "com.github.jimsp.summer";
    String  dtoPackage()          default "";
    String  apiPackage()          default "";
    String  servicePackage()      default "";
    String  handlerPackage()      default "";
    String  channelPackage()      default "";

    /* ---------- NEW (v0.3) – request/reply ---------- */
    /** Channel to listen for replies. Empty ⇒ auto-derive “&lt;cluster&gt;.&lt;resource&gt;.reply”. */
    String  replyChannel()        default "";
}
