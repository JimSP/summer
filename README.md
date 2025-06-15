# Summer Framework 🌞

**Summer** é um framework Java que automatiza a geração de microserviços resilientes a partir de especificações OpenAPI, implementando padrões enterprise como Retry, Circuit Breaker, Batching e Dead Letter Queue de forma declarativa.

## 🚀 Características Principais

- ✅ **Geração Automática**: Transforma OpenAPI specs em implementações JAX-RS completas
- ✅ **Padrões Resilientes**: Retry, Circuit Breaker, Batching, DLQ out-of-the-box  
- ✅ **Multi-Framework**: Suporte para Spring Boot, Quarkus, Micronaut
- ✅ **Multi-Messaging**: Kafka, RabbitMQ, Hazelcast, JMS
- ✅ **Configuração Flexível**: Placeholders com fallbacks inteligentes
- ✅ **Type Safety**: Geração type-safe com validação em tempo de compilação

## 📦 Instalação

### Maven
```xml
<dependency>
    <groupId>com.github.jimsp</groupId>
    <artifactId>summer-processor</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>com.github.jimsp</groupId>
    <artifactId>summer-runtime</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle
```kotlin
dependencies {
    annotationProcessor("com.github.jimsp:summer-processor:1.0.0")
    implementation("com.github.jimsp:summer-runtime:1.0.0")
}
```

## 🎯 Uso Básico

### 1. Defina sua especificação OpenAPI

**users-api.yaml**
```yaml
openapi: 3.0.0
info:
  title: Users API
  version: 1.0.0
paths:
  /users:
    post:
      operationId: createUser
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/User'
      responses:
        '201':
          description: User created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/User'
components:
  schemas:
    User:
      type: object
      properties:
        id:
          type: string
        name:
          type: string
        email:
          type: string
      required: [name, email]
```

### 2. Anote sua interface

```java
package com.example.api;

import com.github.jimsp.summer.annotations.Summer;
import static com.github.jimsp.summer.annotations.Summer.Mode;

@Summer(
    value = "users-api.yaml",
    cluster = "users-service",
    mode = Mode.ASYNC,
    basePackage = "com.example"
)
public interface UsersApiMarker {
    // Interface marcadora - o processador gera tudo automaticamente
}
```

### 3. Implemente o Handler (apenas para Mode.SYNC)

```java
package com.example.handlers;

import com.example.dto.User;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UserHandler {
    
    public User handle(User user) {
        // Sua lógica de negócio
        user.setId(UUID.randomUUID().toString());
        return userRepository.save(user);
    }
}
```

### 4. Configure o Messaging (apenas para Mode.ASYNC)

**Spring Boot**
```java
@Bean
@Channel("channel.users-service.users.createUser")
public Channel<User, User> userChannel(KafkaTemplate<String, Object> kafka) {
    return new KafkaChannel<>(kafka, "users-topic");
}
```

**Quarkus**
```java
@ApplicationScoped
@Channel("channel.users-service.users.createUser")
public class UserKafkaChannel implements Channel<User, User> {
    
    @Inject
    @RestClient
    UserService userService;
    
    @Override
    public void send(User user) {
        // Implementação Kafka
    }
    
    @Override
    public User request(User user) {
        return userService.createUser(user);
    }
}
```

## 📋 Referência Completa da Anotação @Summer

| Atributo | Tipo | Padrão | Descrição |
|----------|------|---------|-----------|
| `value` | String | **obrigatório** | Caminho para o arquivo OpenAPI spec |
| `cluster` | String | `"default"` | Nome do cluster para namespacing de canais |
| `mode` | Mode | `ASYNC` | Modo de operação: `SYNC` ou `ASYNC` |
| `basePackage` | String | **obrigatório** | Package base para geração de código |
| `dtoPackage` | String | `{basePackage}.dto` | Package para DTOs gerados |
| `apiPackage` | String | `{basePackage}.api` | Package para interfaces da API |
| `servicePackage` | String | `{basePackage}.service` | Package para implementações de serviço |
| `handlerPackage` | String | `{basePackage}.handlers` | Package para handlers (modo SYNC) |
| `channelPackage` | String | `{basePackage}.channels.generated` | Package para wrappers de canal |
| `replyChannel` | String | `""` | Canal personalizado para respostas |
| `maxRetries` | int | `3` | Número máximo de tentativas |
| `circuitBreaker` | boolean | `false` | Habilitar Circuit Breaker |
| `cbFailureThreshold` | int | `5` | Threshold de falhas para CB |
| `cbDelaySeconds` | int | `10` | Delay de abertura do CB (segundos) |
| `batchSize` | int | `1` | Tamanho do batch (>1 habilita batching) |
| `batchInterval` | String | `""` | Intervalo de flush do batch |
| `dlq` | String | `""` | Nome da Dead Letter Queue |

## 🔧 Configuração por Framework

### Spring Boot

**application.yml**
```yaml
summer:
  messaging:
    kafka:
      bootstrap-servers: localhost:9092
      producer:
        key-serializer: org.apache.kafka.common.serialization.StringSerializer
        value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    rabbitmq:
      host: localhost
      port: 5672
      username: guest
      password: guest
```

**Configuração**
```java
@Configuration
@EnableSummer
public class SummerConfig {
    
    @Bean
    public SummerChannelFactory channelFactory() {
        return SummerChannelFactory.builder()
            .kafka(kafkaTemplate)
            .rabbitmq(rabbitTemplate)
            .build();
    }
}
```

### Quarkus

**application.properties**
```properties
summer.messaging.kafka.bootstrap.servers=localhost:9092
summer.messaging.rabbitmq.host=localhost
summer.messaging.rabbitmq.port=5672

# Configuração específica Quarkus
mp.messaging.outgoing.users-topic.connector=smallrye-kafka
mp.messaging.outgoing.users-topic.topic=users
```

**Producer**
```java
@ApplicationScoped
public class SummerChannelProducer {
    
    @Produces
    @Channel("channel.users-service.users.createUser")
    public Channel<User, User> userChannel(@Channel("users-topic") Emitter<User> emitter) {
        return new QuarkusKafkaChannel<>(emitter);
    }
}
```

### Micronaut

**application.yml**
```yaml
micronaut:
  application:
    name: users-service
kafka:
  bootstrap:
    servers: localhost:9092
rabbitmq:
  host: localhost
  port: 5672
```

**Factory**
```java
@Factory
public class ChannelFactory {
    
    @Bean
    @Named("channel.users-service.users.createUser")
    public Channel<User, User> userChannel(KafkaClient kafka) {
        return new MicronautKafkaChannel<>(kafka, "users-topic");
    }
}
```

## 🔌 Integrações de Messaging

### Kafka

**Implementação Base**
```java
public class KafkaChannel<IN, OUT> implements Channel<IN, OUT> {
    
    private final KafkaTemplate<String, Object> kafka;
    private final String topic;
    private final String replyTopic;
    
    public KafkaChannel(KafkaTemplate<String, Object> kafka, String topic) {
        this.kafka = kafka;
        this.topic = topic;
        this.replyTopic = topic + "-reply";
    }
    
    @Override
    public void send(IN message) {
        kafka.send(topic, message);
    }
    
    @Override
    public CompletableFuture<Void> sendAsync(IN message) {
        return kafka.send(topic, message).toCompletableFuture().thenApply(r -> null);
    }
    
    @Override
    public OUT request(IN message) {
        String correlationId = UUID.randomUUID().toString();
        kafka.send(topic, message, correlationId);
        return waitForReply(correlationId);
    }
    
    @Override
    public CompletableFuture<OUT> requestAsync(IN message) {
        return CompletableFuture.supplyAsync(() -> request(message));
    }
    
    @SuppressWarnings("unchecked")
    private OUT waitForReply(String correlationId) {
        // Implementação com Consumer temporário ou cache de correlação
        // ...
    }
}
```

**Spring Boot + Kafka**
```java
@Component
public class UserKafkaChannelImpl extends KafkaChannel<User, User> {
    
    public UserKafkaChannelImpl(KafkaTemplate<String, Object> kafka) {
        super(kafka, "users-topic");
    }
}
```

### RabbitMQ

**Implementação Base**
```java
public class RabbitChannel<IN, OUT> implements Channel<IN, OUT> {
    
    private final RabbitTemplate rabbit;
    private final String queue;
    private final String replyQueue;
    
    public RabbitChannel(RabbitTemplate rabbit, String queue) {
        this.rabbit = rabbit;
        this.queue = queue;
        this.replyQueue = queue + ".reply";
    }
    
    @Override
    public void send(IN message) {
        rabbit.convertAndSend(queue, message);
    }
    
    @Override
    public OUT request(IN message) {
        return (OUT) rabbit.convertSendAndReceive(queue, message);
    }
    
    // Implementações async...
}
```

### Hazelcast

**Implementação**
```java
public class HazelcastChannel<IN, OUT> implements Channel<IN, OUT> {
    
    private final ITopic<IN> topic;
    private final IMap<String, OUT> replyMap;
    
    public HazelcastChannel(HazelcastInstance hz, String topicName) {
        this.topic = hz.getTopic(topicName);
        this.replyMap = hz.getMap(topicName + "-replies");
    }
    
    @Override
    public void send(IN message) {
        topic.publish(message);
    }
    
    @Override
    public OUT request(IN message) {
        String correlationId = UUID.randomUUID().toString();
        topic.publish(new CorrelatedMessage<>(correlationId, message));
        
        // Polling com timeout
        return pollForReply(correlationId, Duration.ofSeconds(30));
    }
}
```

## 🔧 Power dos Placeholders

O Summer suporta placeholders avançados com fallbacks em cadeia:

### Sintaxe
```
${propriedade:valor_padrao}
${ENV_VAR:${SYSTEM_PROP:valor_final}}
```

### Exemplos Práticos

**Configuração Flexível**
```java
@Summer(
    value = "${api.spec.path:src/main/resources/api.yaml}",
    cluster = "${service.cluster:${HOSTNAME:default}}",
    basePackage = "${app.package:com.example}",
    maxRetries = "${retry.max:3}",
    batchInterval = "${batch.interval:${BATCH_TIME:5s}}"
)
```

**Por Ambiente**
```bash
# Development
export API_SPEC_PATH="/dev/specs/users-api.yaml"
export SERVICE_CLUSTER="dev-cluster"
export RETRY_MAX="1"

# Production  
export API_SPEC_PATH="/prod/configs/users-api.yaml"
export SERVICE_CLUSTER="prod-cluster"
export RETRY_MAX="5"
export BATCH_INTERVAL="10s"
```

**application.properties**
```properties
# Configuração por perfil
api.spec.path=${API_SPEC_PATH:classpath:openapi/users.yaml}
service.cluster=${CLUSTER_NAME:users-service}
retry.max=${MAX_RETRIES:3}
batch.interval=${BATCH_TIME:5s}

# Configuração específica de ambiente
summer.${spring.profiles.active}.cluster=prod-cluster
```

## 📚 Exemplos de Implementação Completa

### Exemplo 1: E-commerce com Múltiplos Padrões

```java
@Summer(
    value = "${ecommerce.api.spec:ecommerce-api.yaml}",
    cluster = "${service.name:ecommerce}",
    mode = Mode.ASYNC,
    basePackage = "com.example.ecommerce",
    
    // Retry configuration
    maxRetries = 5,
    
    // Circuit Breaker
    circuitBreaker = true,
    cbFailureThreshold = 10,
    cbDelaySeconds = 30,
    
    // Batching
    batchSize = 50,
    batchInterval = "${batch.flush.interval:10s}",
    
    // Dead Letter Queue
    dlq = "ecommerce.orders.dlq"
)
public interface EcommerceApiMarker {}
```

**Channel Implementation**
```java
@Component
@Primary
@Channel("channel.ecommerce.orders.createOrder")  
public class OrderProcessingChannel implements Channel<Order, OrderResult> {
    
    @Autowired private KafkaTemplate<String, Object> kafka;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private InventoryService inventoryService;
    
    @Override
    @Transactional
    public OrderResult request(Order order) {
        try {
            // 1. Validate inventory
            inventoryService.reserve(order.getItems());
            
            // 2. Process payment  
            PaymentResult payment = paymentService.charge(order.getPayment());
            
            // 3. Create order
            Order savedOrder = orderService.create(order, payment);
            
            // 4. Send notifications
            kafka.send("order-notifications", new OrderCreatedEvent(savedOrder));
            
            return new OrderResult(savedOrder.getId(), "SUCCESS");
            
        } catch (InventoryException e) {
            return new OrderResult(null, "OUT_OF_STOCK");
        } catch (PaymentException e) {
            return new OrderResult(null, "PAYMENT_FAILED");
        }
    }
    
    @Override
    public void send(Order order) {
        // Fire-and-forget processing
        CompletableFuture.runAsync(() -> request(order));
    }
}
```

### Exemplo 2: Sistema de Notificações com Batching

```java
@Summer(
    value = "notifications-api.yaml",
    cluster = "notifications",
    mode = Mode.ASYNC,
    basePackage = "com.example.notifications",
    
    // Aggressive batching for efficiency
    batchSize = 100,
    batchInterval = "30s",
    
    // Retry with backoff
    maxRetries = 3,
    
    // DLQ for failed notifications
    dlq = "notifications.failed"
)
public interface NotificationsApiMarker {}
```

**Batch-Optimized Channel**
```java
@Service
@Channel("channel.notifications.notifications.sendNotification")
public class NotificationBatchChannel implements Channel<Notification, Void> {
    
    @Autowired private EmailService emailService;
    @Autowired private SmsService smsService;
    @Autowired private PushService pushService;
    
    // O framework já implementa batching, nós processamos o lote
    @Override
    public void send(Notification notification) {
        switch (notification.getType()) {
            case EMAIL -> emailService.send(notification);
            case SMS -> smsService.send(notification);  
            case PUSH -> pushService.send(notification);
        }
    }
    
    // Implementação específica para lotes (opcional)
    public void sendBatch(List<Notification> notifications) {
        Map<NotificationType, List<Notification>> grouped = 
            notifications.stream().collect(groupingBy(Notification::getType));
            
        grouped.forEach((type, batch) -> {
            switch (type) {
                case EMAIL -> emailService.sendBatch(batch);
                case SMS -> smsService.sendBatch(batch);
                case PUSH -> pushService.sendBatch(batch);
            }
        });
    }
}
```

### Exemplo 3: Sistema de Audit com DLQ

```java
@Summer(
    value = "audit-api.yaml", 
    cluster = "audit-service",
    mode = Mode.ASYNC,
    basePackage = "com.example.audit",
    
    // Conservative retry for audit reliability  
    maxRetries = 10,
    
    // Circuit breaker for external dependencies
    circuitBreaker = true,
    cbFailureThreshold = 3,
    cbDelaySeconds = 60,
    
    // DLQ is critical for audit
    dlq = "audit.critical.dlq",
    replyChannel = "audit.responses"
)
public interface AuditApiMarker {}
```

**Reliable Audit Channel**
```java
@Service
@Channel("channel.audit-service.audit.logEvent")
public class AuditChannel implements Channel<AuditEvent, AuditResult> {
    
    @Autowired private AuditRepository repository;
    @Autowired private ExternalAuditService externalService;
    @Autowired private ComplianceService complianceService;
    
    @Override
    @Retryable(maxAttempts = 3)
    public AuditResult request(AuditEvent event) {
        try {
            // 1. Local storage (always succeeds)
            AuditRecord local = repository.save(toRecord(event));
            
            // 2. External compliance system (may fail)
            if (event.isComplianceRequired()) {
                ComplianceResult compliance = complianceService.submit(event);
                local.setComplianceId(compliance.getId());
                repository.save(local);
            }
            
            // 3. External audit system (may fail) 
            ExternalAuditResult external = externalService.log(event);
            local.setExternalId(external.getId());
            repository.save(local);
            
            return new AuditResult(local.getId(), external.getId(), "SUCCESS");
            
        } catch (Exception e) {
            log.error("Audit failed for event: " + event.getId(), e);
            throw new AuditException("Failed to process audit event", e);
        }
    }
}
```

## 🔄 Padrões de Channel Implementation

### 1. Request-Reply Pattern

```java
@Service
@Channel("channel.service.resource.operation")
public class RequestReplyChannel implements Channel<Request, Response> {
    
    @Override
    public Response request(Request req) {
        // Processamento síncrono
        return processRequest(req);
    }
    
    @Override
    public CompletableFuture<Response> requestAsync(Request req) {
        return CompletableFuture.supplyAsync(() -> processRequest(req));
    }
}
```

### 2. Fire-and-Forget Pattern

```java
@Service  
@Channel("channel.service.resource.operation")
public class FireAndForgetChannel implements Channel<Command, Void> {
    
    @Override
    public void send(Command cmd) {
        // Processamento assíncrono
        processCommand(cmd);
    }
    
    @Override
    public CompletableFuture<Void> sendAsync(Command cmd) {
        return CompletableFuture.runAsync(() -> processCommand(cmd));
    }
}
```

### 3. Event Sourcing Pattern

```java
@Service
@Channel("channel.events.aggregate.command") 
public class EventSourcingChannel implements Channel<Command, EventResult> {
    
    @Autowired private EventStore eventStore;
    @Autowired private AggregateRepository aggregateRepo;
    
    @Override
    public EventResult request(Command command) {
        // 1. Load aggregate
        Aggregate aggregate = aggregateRepo.findById(command.getAggregateId());
        
        // 2. Apply command
        List<Event> events = aggregate.handle(command);
        
        // 3. Store events
        eventStore.saveEvents(command.getAggregateId(), events);
        
        // 4. Update aggregate
        events.forEach(aggregate::apply);
        aggregateRepo.save(aggregate);
        
        return new EventResult(events);
    }
}
```

## 🧪 Testing

### Unit Testing

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    
    @Mock private Channel<User, User> userChannel;
    @InjectMocks private UserApiServiceImpl userService;
    
    @Test
    void shouldCreateUser() {
        // Given
        User input = new User("John", "john@example.com");
        User expected = new User("123", "John", "john@example.com");
        when(userChannel.request(input)).thenReturn(expected);
        
        // When  
        Response response = userService.createUser(input);
        
        // Then
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(expected);
    }
}
```

### Integration Testing

```java
@SpringBootTest
@TestPropertySource(properties = {
    "summer.messaging.mock=true",
    "summer.circuit-breaker.enabled=false"
})
class UserServiceIntegrationTest {
    
    @Autowired private UserApiServiceImpl userService;
    @MockBean private UserRepository userRepository;
    
    @Test
    void shouldHandleUserCreation() {
        // Test com mocking do messaging layer
    }
}
```

## 🔍 Troubleshooting

### Problemas Comuns

**1. "Interface XXX não gerada"**
```
Erro: Interface com.example.api.UserApiService não gerada

Solução: Verificar se o arquivo OpenAPI é válido e acessível no classpath
```

**2. "Channel não encontrado"**  
```
Erro: No qualifying bean of type 'Channel<User, User>' available

Solução: Implementar ou configurar o Channel correspondente
```

**3. "Circuit Breaker não funciona"**
```
Erro: MicroProfile Fault Tolerance não encontrado

Solução: Adicionar dependência do MP Fault Tolerance
```

### Debug e Logging

```properties
# Habilitar logs detalhados
logging.level.com.github.jimsp.summer=DEBUG
logging.level.org.eclipse.microprofile.faulttolerance=DEBUG

# Metrics para monitoring
management.endpoints.web.exposure.include=health,metrics,circuitbreakers
management.metrics.export.prometheus.enabled=true
```

## 📈 Performance e Monitoring

### Métricas Automáticas

O Summer automaticamente expõe métricas para:
- Contadores de retry
- Status do Circuit Breaker  
- Tamanhos de batch
- Latência de mensagens
- Taxa de erros do DLQ

### Configuração de Monitoring

```java
@Configuration
public class SummerMonitoringConfig {
    
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> summerMetrics() {
        return registry -> registry.config()
            .commonTags("service", "users-service")
            .meterFilter(MeterFilter.deny(id -> id.getName().startsWith("jvm")));
    }
}
```

---

## 🤝 Contribuição

Contribuições são bem-vindas! Por favor, leia nosso [CONTRIBUTING.md](CONTRIBUTING.md) antes de submeter PRs.

## 📄 Licença

Este projeto está licenciado sob a MIT License - veja o arquivo [LICENSE](LICENSE) para detalhes.

## 🆘 Suporte

- 📚 [Documentação Completa](https://summer-framework.dev)
- 🐛 [Issues](https://github.com/jimsp/summer/issues)  
- 💬 [Discussões](https://github.com/jimsp/summer/discussions)
- 📧 [Email](mailto:support@summer-framework.dev)

---

**Summer Framework** - Transformando especificações em microserviços resilientes ☀️
