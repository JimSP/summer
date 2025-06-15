# Summer Framework

🌟 **Framework de geração automática de APIs e pipelines de mensageria baseado em contratos OpenAPI**

O Summer é um framework inovador que automatiza a criação de APIs JAX-RS e pipelines de mensageria resilientes a partir de especificações OpenAPI. Com uma única anotação `@Summer`, você obtém DTOs, endpoints REST, retry policies, circuit breakers, batching e dead letter queues.

## 🚀 Características Principais

- **Geração Automática**: DTOs e endpoints JAX-RS gerados a partir de contratos OpenAPI
- **Pipeline de Resiliência**: Retry automático com backoff configurável
- **Circuit Breaker**: Proteção contra falhas em cascata usando MicroProfile Fault Tolerance
- **Batching Inteligente**: Agrupamento de mensagens por tamanho ou tempo
- **Dead Letter Queue**: Tratamento automático de mensagens com falha
- **Placeholders**: Suporte a variáveis de ambiente e propriedades do sistema
- **CDI Ready**: Integração nativa com Jakarta EE/MicroProfile

## 📋 Requisitos

- **JDK 17+**
- **Maven 3.8+** ou **Gradle 7+**
- **Jakarta EE 9+** ou **MicroProfile 5+**

## 📦 Instalação

### Maven

```xml
<dependencies>
    <!-- Summer Framework Core -->
    <dependency>
        <groupId>com.example</groupId>
        <artifactId>summer-framework</artifactId>
        <version>1.0.0</version>
    </dependency>
    
    <!-- OpenAPI Generator -->
    <dependency>
        <groupId>org.openapitools</groupId>
        <artifactId>openapi-generator</artifactId>
        <version>7.4.0</version>
    </dependency>
    
    <!-- Jimfs (File System in-memory) -->
    <dependency>
        <groupId>com.google.jimfs</groupId>
        <artifactId>jimfs</artifactId>
        <version>1.3.0</version>
    </dependency>
    
    <!-- JavaPoet (Geração de código) -->
    <dependency>
        <groupId>com.squareup</groupId>
        <artifactId>javapoet</artifactId>
        <version>1.13.0</version>
    </dependency>
    
    <!-- AutoService -->
    <dependency>
        <groupId>com.google.auto.service</groupId>
        <artifactId>auto-service</artifactId>
        <version>1.1.1</version>
        <scope>provided</scope>
    </dependency>
    
    <!-- MicroProfile Fault Tolerance (opcional) -->
    <dependency>
        <groupId>org.eclipse.microprofile.fault-tolerance</groupId>
        <artifactId>microprofile-fault-tolerance-api</artifactId>
        <version>4.0</version>
    </dependency>
    
    <!-- MicroProfile Metrics (opcional) -->
    <dependency>
        <groupId>org.eclipse.microprofile.metrics</groupId>
        <artifactId>microprofile-metrics-api</artifactId>
        <version>5.0</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.11.0</version>
            <configuration>
                <release>17</release>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.example</groupId>
                        <artifactId>summer-framework</artifactId>
                        <version>1.0.0</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Gradle

```kotlin
dependencies {
    implementation("com.example:summer-framework:1.0.0")
    implementation("org.openapitools:openapi-generator:7.4.0")
    implementation("com.google.jimfs:jimfs:1.3.0")
    implementation("com.squareup:javapoet:1.13.0")
    
    compileOnly("com.google.auto.service:auto-service:1.1.1")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")
    
    // Opcional: MicroProfile
    implementation("org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api:4.0")
    implementation("org.eclipse.microprofile.metrics:microprofile-metrics-api:5.0")
}
```

## 🏗️ Estrutura do Projeto

```
src/main/java/
├── com/example/annotations/
│   ├── Summer.java                 # Anotação principal
│   └── Channel.java               # Qualifier CDI para canais
├── com/example/messaging/
│   └── Channel.java               # Interface de mensageria
├── com/example/retry/
│   ├── RetryPolicy.java           # Interface de retry
│   ├── FixedBackoff.java          # Backoff fixo
│   └── ExponentialBackoff.java    # Backoff exponencial
└── com/example/processor/
    └── OpenApiProcessor.java      # Annotation Processor
```

## 🌟 Uso Básico

### 1. Definindo um Contrato

Crie um arquivo OpenAPI (YAML ou JSON):

```yaml
# petstore.yaml
openapi: 3.0.0
info:
  title: Pet Store API
  version: 1.0.0
paths:
  /pets:
    post:
      operationId: createPet
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/Pet'
      responses:
        '201':
          description: Pet created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Pet'
components:
  schemas:
    Pet:
      type: object
      required:
        - name
        - type
      properties:
        id:
          type: integer
          format: int64
        name:
          type: string
        type:
          type: string
          enum: [cat, dog, bird]
        age:
          type: integer
```

### 2. Aplicando a Anotação @Summer

```java
import com.example.annotations.Summer;
import static com.example.annotations.Summer.Mode;

@Summer(
    value = "${PWD}/petstore.yaml",
    cluster = "pets",
    mode = Mode.ASYNC,
    maxRetries = 5,
    circuitBreaker = true,
    cbFailureThreshold = 3,
    cbDelaySeconds = 30,
    dlq = "channel.pets.dlq",
    batchSize = 10,
    batchInterval = "5s"
)
public interface PetsApi {}
```

### 3. Compilação

Durante a compilação, o Summer automaticamente gera:

- **DTOs**: `com.example.dto.Pet`
- **API Interface**: `com.example.api.PetsApiService`
- **Service Implementation**: `com.example.service.PetsApiServiceImpl`
- **Pipeline de Wrappers**: Retry → Circuit Breaker → Batch → DLQ

## ⚙️ Configuração Avançada

### Modos de Operação

#### SYNC (Síncrono)
```java
@Summer(
    value = "api-spec.yaml",
    mode = Mode.SYNC
)
public interface OrdersApi {}
```

O modo síncrono delega para um Handler:
```java
@ApplicationScoped
public class OrdersHandler {
    public Order handle(Order order) {
        // Processar pedido
        return processedOrder;
    }
}
```

#### ASYNC (Assíncrono)
```java
@Summer(
    value = "api-spec.yaml",
    mode = Mode.ASYNC,
    maxRetries = 10,
    circuitBreaker = true
)
public interface NotificationsApi {}
```

O modo assíncrono cria um pipeline de mensageria com wrappers de resiliência.

### Retry Policies

#### Fixed Backoff (Padrão)
```java
@ApplicationScoped
public class CustomFixedBackoff implements RetryPolicy {
    @Override
    public long nextDelay(int attempt) {
        return 1000L; // 1 segundo entre tentativas
    }
}
```

#### Exponential Backoff
```java
@ApplicationScoped
public class CustomExponentialBackoff implements RetryPolicy {
    @Override
    public long nextDelay(int attempt) {
        return Math.min(200L * (1L << (attempt - 1)), 30_000L);
    }
}
```

### Circuit Breaker

Baseado em MicroProfile Fault Tolerance:

```java
@Summer(
    value = "api-spec.yaml",
    circuitBreaker = true,
    cbFailureThreshold = 5,    // Falhas antes de abrir
    cbDelaySeconds = 60        // Tempo em estado aberto (segundos)
)
public interface ResilientApi {}
```

### Batching

Agrupa mensagens por tamanho ou tempo:

```java
@Summer(
    value = "api-spec.yaml",
    batchSize = 100,           // Máximo de mensagens por batch
    batchInterval = "10s"      // Flush a cada 10 segundos
)
public interface BatchedApi {}
```

### Dead Letter Queue

Mensagens com falha são enviadas para um canal DLQ:

```java
@Summer(
    value = "api-spec.yaml",
    dlq = "channel.errors.failed-messages"
)
public interface ReliableApi {}
```

Você precisa implementar o canal DLQ:

```java
@Channel("channel.errors.failed-messages")
@ApplicationScoped
public class FailedMessagesChannel implements Channel<Object> {
    private final Queue<Object> queue = new ConcurrentLinkedQueue<>();
    
    @Override
    public void send(Object message) {
        queue.offer(message);
        // Log, persist, ou processar mensagens com falha
    }
}
```

### Placeholders

Suporte a variáveis de ambiente e propriedades:

```java
@Summer(
    value = "${API_SPEC_PATH:/default/api.yaml}",
    cluster = "${CLUSTER_NAME:default}",
    dlq = "${DLQ_CHANNEL:channel.default.dlq}"
)
public interface ConfigurableApi {}
```

**Formato**: `${VARIAVEL:valor_padrao}`

- Busca primeiro em System Properties
- Depois em Environment Variables
- Por último usa o valor padrão

## 🔧 Implementação de Canais

### Canal em Memória (Desenvolvimento)

```java
@Channel("channel.pets.create")
@ApplicationScoped
public class InMemoryPetChannel implements Channel<Pet> {
    private final Queue<Pet> queue = new ConcurrentLinkedQueue<>();
    
    @Override
    public void send(Pet pet) {
        queue.offer(pet);
        System.out.println("Pet queued: " + pet.getName());
    }
    
    public List<Pet> drain() {
        List<Pet> pets = new ArrayList<>();
        Pet pet;
        while ((pet = queue.poll()) != null) {
            pets.add(pet);
        }
        return pets;
    }
}
```

### Canal Kafka

```java
@Channel("channel.orders.process")
@ApplicationScoped
public class KafkaOrderChannel implements Channel<Order> {
    
    @Inject
    private KafkaTemplate kafkaTemplate;
    
    @Override
    public void send(Order order) {
        kafkaTemplate.send("orders-topic", order.getId().toString(), order);
    }
}
```

### Canal JMS

```java
@Channel("channel.notifications.send")
@ApplicationScoped
public class JmsNotificationChannel implements Channel<Notification> {
    
    @Inject
    @JMSConnectionFactory("java:/jms/DefaultConnectionFactory")
    private JMSContext context;
    
    @Override
    public void send(Notification notification) {
        context.createProducer()
               .send(context.createQueue("notifications"), notification);
    }
}
```

## 📊 Monitoramento e Métricas

### MicroProfile Metrics (Opcional)

Se MicroProfile Metrics estiver no classpath, o Summer automaticamente adiciona métricas:

- `summer_retry_attempts_total{channel, operation}` - Tentativas de retry
- `summer_circuit_breaker_state{channel}` - Estado do circuit breaker
- `summer_batch_size{channel}` - Tamanho dos batches processados
- `summer_dlq_messages_total{channel}` - Mensagens enviadas para DLQ

### Health Checks

```java
@ApplicationScoped
public class ChannelHealthCheck implements HealthCheck {
    
    @Inject
    @Channel("channel.pets.create")
    private Channel<Pet> petChannel;
    
    @Override
    public HealthCheckResponse call() {
        try {
            // Teste de conectividade
            return HealthCheckResponse.up("pet-channel");
        } catch (Exception e) {
            return HealthCheckResponse.down("pet-channel");
        }
    }
}
```

## 🧪 Testes

### Testes Unitários

```java
@ExtendWith(MockitoExtension.class)
class PetServiceTest {
    
    @Mock
    private Channel<Pet> petChannel;
    
    @InjectMocks
    private PetsApiServiceImpl petService;
    
    @Test
    void shouldSendPetToChannel() {
        Pet pet = new Pet().name("Fluffy").type("cat");
        
        Response response = petService.createPet(pet);
        
        assertEquals(202, response.getStatus());
        verify(petChannel).send(pet);
    }
}
```

### Testes de Integração

```java
@QuarkusTest
class PetApiIntegrationTest {
    
    @Inject
    @Channel("channel.pets.create")
    private InMemoryPetChannel petChannel;
    
    @Test
    void shouldProcessPetCreation() {
        given()
            .contentType(ContentType.JSON)
            .body(new Pet().name("Rex").type("dog"))
        .when()
            .post("/pets")
        .then()
            .statusCode(202);
            
        List<Pet> queuedPets = petChannel.drain();
        assertEquals(1, queuedPets.size());
        assertEquals("Rex", queuedPets.get(0).getName());
    }
}
```

## 🔍 Troubleshooting

### Problemas Comuns

#### 1. Canal CDI não encontrado
```
Bean CDI faltando para canal "channel.pets.create"
```

**Solução**: Implemente o canal ou use o código sugerido no warning.

#### 2. Dependência MicroProfile não encontrada
```
Circuit Breaker skipped - MicroProfile FT API not present
```

**Solução**: Adicione a dependência MicroProfile Fault Tolerance.

#### 3. Erro de compilação do OpenAPI Generator
```
Processor error: Failed to generate from spec
```

**Solução**: Verifique se o arquivo OpenAPI está válido e acessível.

### Debug

Ative logs detalhados:

```properties
# application.properties
logging.level.com.example.processor=DEBUG
logging.level.org.openapitools=DEBUG
```

### Validação Manual

Verifique se os arquivos foram gerados:

```bash
find target/generated-sources/annotations -name "*.java" | grep -E "(Dto|Api|Service)"
```

## 🚀 Exemplos Completos

### E-commerce API

```java
@Summer(
    value = "${API_SPECS_DIR}/ecommerce.yaml",
    cluster = "ecommerce",
    mode = Mode.ASYNC,
    maxRetries = 3,
    circuitBreaker = true,
    cbFailureThreshold = 5,
    cbDelaySeconds = 30,
    batchSize = 20,
    batchInterval = "2s",
    dlq = "channel.ecommerce.failed"
)
public interface EcommerceApi {}
```

### Microserviços com Different Clusters

```java
// Serviço de Pedidos
@Summer(
    value = "orders-api.yaml",
    cluster = "orders",
    mode = Mode.ASYNC,
    maxRetries = 5,
    dlq = "channel.orders.dlq"
)
public interface OrdersApi {}

// Serviço de Pagamentos
@Summer(
    value = "payments-api.yaml",
    cluster = "payments", 
    mode = Mode.SYNC // Pagamento deve ser síncrono
)
public interface PaymentsApi {}

// Serviço de Notificações
@Summer(
    value = "notifications-api.yaml",
    cluster = "notifications",
    mode = Mode.ASYNC,
    batchSize = 50,
    batchInterval = "10s"
)
public interface NotificationsApi {}
```

## 🤝 Contribuindo

1. Fork o projeto
2. Crie uma branch para sua feature (`git checkout -b feature/amazing-feature`)
3. Commit suas mudanças (`git commit -m 'Add amazing feature'`)
4. Push para a branch (`git push origin feature/amazing-feature`)
5. Abra um Pull Request

## 📝 Licença

Este projeto está licenciado sob a MIT License - veja o arquivo [LICENSE](LICENSE) para detalhes.

## 🙏 Agradecimentos

- [OpenAPI Generator](https://openapi-generator.tech/) - Geração de código a partir de especificações OpenAPI
- [JavaPoet](https://github.com/square/javapoet) - API fluente para geração de código Java
- [MicroProfile](https://microprofile.io/) - Especificações para microserviços Java
- [Jimfs](https://github.com/google/jimfs) - Sistema de arquivos em memória para testes

---

**Summer Framework** - Simplifique a criação de APIs resilientes e escaláveis! 🌞
