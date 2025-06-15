
# Summer: Geração de Pipelines Assíncronos a partir de Contratos OpenAPI

Versão mínima: Java 17  
Build: Maven ou Gradle  
Frameworks utilizados: Jakarta EE (CDI), MicroProfile FT (opcional), JavaPoet, OpenAPI Generator

## Visão Geral

Summer é uma anotação de tempo de compilação (@Summer) que dispara um annotation processor para:

- Gerar automaticamente DTOs, APIs JAX-RS e stubs a partir de contratos OpenAPI (YAML ou JSON)
- Criar wrappers para envio assíncrono com suporte a:
  - Retry com backoff
  - Circuit Breaker (MicroProfile FT)
  - Batch com flush programado
  - Dead Letter Queue (DLQ)

## Exemplo de Uso

import static com.example.annotations.Summer.Mode;

@Summer(
    value = "${PWD}/petstore.yaml",
    cluster = "pets",
    mode = Mode.ASYNC,
    maxRetries = 10,
    circuitBreaker = true,
    cbFailureThreshold = 4,
    cbDelaySeconds = 20,
    dlq = "channel.pets.dlq",
    batchSize = 50,
    batchInterval = "5s"
)
public interface PetsApi {}

Resultado:

- com.example.dto.* → DTOs gerados
- com.example.api.PetsApiService → Interface REST
- com.example.service.PetsApiServiceImpl → Pipeline com Retry → CB → Batch → DLQ
- Beans CDI com @Channel("channel.pets.*") para injeção

## Estrutura do Projeto

src/main/java/
├── com/example/annotations/
│   ├── Summer.java
│   └── Channel.java
├── com/example/messaging/
│   └── Channel.java
├── com/example/retry/
│   ├── RetryPolicy.java
│   ├── FixedBackoff.java
│   └── ExponentialBackoff.java
└── com/example/processor/
    └── OpenApiProcessor.java

## Modos de operação

Modo    | Comportamento
--------|----------------------------
SYNC    | Handler síncrono via CDI
ASYNC   | Geração de wrappers encadeados com Channel: Retry → CB → Batch → DLQ

## Parâmetros da anotação @Summer

value: caminho ou URL do contrato  
cluster: namespace lógico  
mode: SYNC ou ASYNC  
maxRetries: tentativas máximas  
circuitBreaker: habilita CB  
cbFailureThreshold: threshold de falhas  
cbDelaySeconds: tempo de espera antes de reabrir  
dlq: nome lógico do canal de Dead Letter Queue  
batchSize: quantidade de mensagens no buffer  
batchInterval: intervalo para flush ("5s", "1000ms")

## Retry e Circuit Breaker

- RetryPolicy pode ser implementada como CDI Bean (@ApplicationScoped)
- Circuit Breaker utiliza MicroProfile Fault Tolerance se disponível

## Dead Letter Queue (DLQ)

- Quando "dlq" está presente, é gerado um canal secundário Channel<T> para fallback
- Se não existir implementação CDI anotada com @Channel(dlq), o processador sugere um stub padrão

## Batch Processing

- batchSize > 1 ou batchInterval ≠ "" ativa a lógica de batching
- Timer é utilizado para flush automático com base no intervalo definido

## Dependências Maven

org.openapitools openapi-generator 7.4.0  
com.google.jimfs jimfs 1.3.0  
com.squareup javapoet 1.13.0  
com.google.auto.service auto-service 1.1.1 provided  
org.eclipse.microprofile.fault-tolerance microprofile-fault-tolerance-api 4.0  
org.eclipse.microprofile.metrics microprofile-metrics-api 5.0

## Benefícios

- Geração automática de APIs e DTOs OpenAPI
- Pipelines assíncronos configuráveis e resilientes
- Extensível por CDI
- Independente de broker (Kafka, Hazelcast, etc.)

## Requisitos

- Java 17+
- Maven ou Gradle
- CDI habilitado (Weld, Quarkus, Helidon, etc.)

## Contribuições Futuras

- Suporte a múltiplos métodos
- Observabilidade (Micrometer, OpenTelemetry)
- Estratégias avançadas de fallback e tracing

## Licença

MIT © Seu Nome ou Empresa
