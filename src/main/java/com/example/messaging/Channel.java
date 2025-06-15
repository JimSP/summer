package com.example.messaging;

/** Dispatcher genérico. Implementações podem publicar em Kafka, Hazelcast, etc. */
@FunctionalInterface
public interface Channel<T> {
    void send(T message);
}
