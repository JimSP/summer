package com.github.jimsp.summer.annotations;

import java.lang.annotation.*;

/**
 * Marca uma interface *Api* para disparar geração de código.
 *
 *  – {@code basePackage} define a raiz; cada pacote pode sobrescrever.
 *  – Campos vazios ⇒ herdam {@code basePackage}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Summer {

    /* ---------- contrato ---------- */
    String value();                // caminho/URL OpenAPI
    String cluster()   default ""; // namespace lógico

    enum Mode { SYNC, ASYNC }
    Mode   mode()      default Mode.SYNC;

    /* ---------- resiliency ---------- */
    int  maxRetries()          default 3;
    boolean circuitBreaker()   default false;
    int  cbFailureThreshold()  default 5;
    int  cbDelaySeconds()      default 30;

    String dlq()               default "";
    int    batchSize()         default 1;
    String batchInterval()     default "";

    /* ---------- novos atributos (P1) ---------- */
    String basePackage()        default "com.github.jimsp.summer";
    String dtoPackage()         default "";   // vazio -> basePackage + ".dto"
    String apiPackage()         default "";   // idem
    String servicePackage()     default "";   // idem
    String handlerPackage()     default "";   // idem
    String channelPackage()     default "";   // idem
}
