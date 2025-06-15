package com.example.messaging;

import java.util.concurrent.CompletableFuture;

/**
 * Canal genérico entre serviços/agents.
 *
 * IN  – tipo da mensagem enviada
 * OUT – tipo esperado na resposta (use Void quando não houver retorno)
 */
public interface Channel<IN, OUT> {

    /** Fire-and-forget: envio sem bloqueio nem confirmação. */
    void send(IN message);

    /** Fire-and-forget com confirmação assíncrona (ack/Nack). */
    CompletableFuture<Void> sendAsync(IN message);

    /** Request/response síncrono (bloca a thread até receber OUT). */
    OUT request(IN message);

    /** Request/response assíncrono (CompletableFuture de OUT). */
    CompletableFuture<OUT> requestAsync(IN message);
}
