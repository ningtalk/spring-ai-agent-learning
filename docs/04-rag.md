# 04 - RAG 检索增强生成

## 阶段目标

让 Agent 能够基于私有知识库回答问题。理解 RAG 的完整链路：文档加载 → 分块 → 向量化 → 存储 → 检索 → 增强生成。

完成后你应该能够回答：

- RAG 解决了 LLM 的哪些根本性限制？
- ETL 管道的四个阶段各自承担什么职责？
- `QuestionAnswerAdvisor` 如何在 Advisor 链中实现检索增强？
- RAG 检索不到文档时，如何按步骤定位问题？


## 一、核心认知：为什么需要 RAG

LLM 有两个根本性限制：

- **有限的上下文**：无法一次性摄取整个语料库
- **静态知识**：训练数据在某个时间点被冻结，对私有数据一无所知

RAG 的本质是：**在查询时检索相关的外部知识，将其注入 Prompt，用特定上下文增强 LLM 的回答**。Spring AI 通过模块化架构支持 RAG，允许自行构建自定义流程，或使用 Advisor API 直接使用开箱即用的 RAG 流程。


## 二、RAG 的两阶段架构

**索引阶段（离线）** ：

```
文档加载 → 文本分割 → 向量化 → 存入向量数据库
```

**检索生成阶段（在线）** ：

```
用户问题 → 查询向量化 → 相似度匹配 → 上下文组装 → LLM 生成答案
```


## 三、ETL 管道：Spring AI 的 RAG 基础设施

| 阶段 | 组件 | 职责 |
|---|---|---|
| Extract | `DocumentReader` | 从 PDF、Word、Markdown 等格式提取文本 |
| Transform | `TextSplitter` | 将长文档切分为适当大小的片段 |
| Embed | `EmbeddingModel` | 将文本转为高维向量 |
| Load | `VectorStore` | 存储向量并支持相似度检索 |

### 3.1 TokenTextSplitter

`TokenTextSplitter` 使用 CL100K_BASE 编码根据令牌计数将文本分割成块，提供两种构造函数选项：无参构造使用默认设置，带参构造可指定 chunk 大小、最小字符数等参数。

```java
@Bean
public TokenTextSplitter tokenTextSplitter() {
    return new TokenTextSplitter(
        800,   // 默认chunk大小
        350,   // 最小chunk大小字符数
        5,     // 最小嵌入维度
        10000, // 最大chunk数量
        true   // 保留分隔符
    );
}
```

> **注意**：`TokenTextSplitter` 不会被 Spring 自动注册为 Bean，必须在 `@Configuration` 类中显式定义，否则构造函数注入时会报 `Could not autowire` 错误。


## 四、向量存储配置

### 4.1 为什么 VectorStore 不能直接注入

`VectorStore` 是一个接口，Spring 容器不会自动为接口创建实例。如果项目中没有自动装配出某个向量库实现，也没有手动声明 Bean，启动时就会报错。

### 4.2 开发环境：SimpleVectorStore

`SimpleVectorStore` 需要 `EmbeddingModel` 来计算文本的向量维度。必须在配置类中显式注册：

```java
package io.github.<github-username>.agent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
```

### 4.3 生产环境：持久化向量库

引入对应 Starter 后 Spring AI 会自动配置 `VectorStore` Bean。例如 Redis：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-redis</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    vectorstore:
      type: redis
      redis:
        index: spring-ai-agent
        uri: redis://localhost:6379
```

自动配置的条件是：类路径下存在 `RedisClient` 和 `EmbeddingModel`，且 `spring.ai.vectorstore.type` 为 `redis`。此时可以移除手动的 `VectorStoreConfig`。


## 五、Embedding 模型配置

### 5.1 常见报错：模型不存在

Spring AI 的 `OpenAiEmbeddingModel` 默认使用 `text-embedding-ada-002` 作为嵌入模型。该模型已在 OpenAI 被废弃，如果你的端点不支持此模型，会报 404：

```
The model `text-embedding-ada-002` does not exist or you do not have access to it.
```

### 5.2 正确配置

`chat` 和 `embedding` 的模型名必须分开配置，两者不能混淆。

**OpenAI**：

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com
      chat:
        options:
          model: gpt-4o-mini
      embedding:
        options:
          model: text-embedding-3-small
          dimensions: 1536
```

**DashScope 兼容模式**：

```yaml
spring:
  ai:
    openai:
      api-key: ${DASHSCOPE_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      chat:
        options:
          model: qwen-plus
      embedding:
        options:
          model: text-embedding-v3
          dimensions: 1024
```

`text-embedding-v3` 支持 1024（默认）、768、512、256、128 或 64 维，`text-embedding-v4` 支持更多维度选择。常用 Embedding 模型维度对比：

| 模型 | 维度 | 适用场景 |
|---|---|---|
| text-embedding-3-small（OpenAI） | 1536 | 通用场景 |
| text-embedding-v3（DashScope） | 1024 | 中文场景首选 |
| bge-large-zh（BAAI） | 1024 | 开源自部署 |


## 六、QuestionAnswerAdvisor：Advisor 链中的 RAG

`QuestionAnswerAdvisor` 会查询向量数据库以获取与用户问题相关的文档，并将结果附加到用户文本中，为 AI 模型提供生成响应的上下文。

### 6.1 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-advisors-vector-store</artifactId>
</dependency>
```

### 6.2 挂载到 ChatClient

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder,
                             ChatMemory chatMemory,
                             VectorStore vectorStore) {
    return builder
            .defaultSystem("你是一个乐于助人的AI助手。")
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build(),
                    QuestionAnswerAdvisor.builder(vectorStore)
                            .order(10)
                            .build()
            )
            .defaultTools(new DateTimeTools(), new WeatherTools())
            .build();
}
```

### 6.3 Advisor 执行顺序

Advisor 链按照 `getOrder()` 值顺序依次调用，**较低的值首先执行**，最后一个 advisor 将请求发送到 LLM。Advisor 链的工作方式类似栈结构：链中第一个 advisor 最先处理请求，也是最后处理响应。

**为什么顺序很重要**：如果 `QuestionAnswerAdvisor` 被放置在链中过于靠前的位置，后续 advisor 可能会覆盖或忽略它注入的检索结果。推荐显式指定 `order` 值，确保 RAG Advisor 在记忆 Advisor 之后执行：

```java
.defaultAdvisors(
    MessageChatMemoryAdvisor.builder(chatMemory).build(),   // 先执行：注入历史消息
    QuestionAnswerAdvisor.builder(vectorStore).order(10).build()  // 后执行：注入检索文档
)
```

### 6.4 配置检索参数

检索参数在**创建 `QuestionAnswerAdvisor` 时**，通过 `.searchRequest()` 方法设置：

```java
QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(SearchRequest.builder()
                .similarityThreshold(0.5d)  // 相似度阈值
                .topK(6)                     // 返回前6个结果
                .build())
        .build();
```

**动态过滤表达式**：可以在运行时通过 `FILTER_EXPRESSION` 参数动态调整过滤条件：

```java
chatClient.prompt()
        .user("请回答我的问题")
        .advisors(a -> a.param(
                QuestionAnswerAdvisor.FILTER_EXPRESSION,
                "type == 'Spring'"))
        .call()
        .content();
```

### 6.5 自定义 Prompt 模板

通过 `.promptTemplate()` 方法提供自定义模板。模板**必须**包含两个占位符：`query` 用于接收用户问题，`question_answer_context` 用于接收检索到的上下文。

```java
PromptTemplate customTemplate = PromptTemplate.builder()
        .renderer(StTemplateRenderer.builder()
                .startDelimiterToken('<')
                .endDelimiterToken('>')
                .build())
        .template("""
            上下文信息如下。
            <question_answer_context>
            根据上下文信息且没有先验知识，回答查询。
            遵循以下规则：
            1. 如果答案不在上下文中，只需说你不知道。
            2. 不要编造答案。
            """)
        .build();

QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
        .promptTemplate(customTemplate)
        .build();
```

注意：这里提供的 `PromptTemplate` 自定义的是 Advisor 如何将检索到的上下文与用户查询合并，这与在 `ChatClient` 本身上配置 `TemplateRenderer` 不同，后者影响的是 Advisor 运行之前的初始提示词渲染。`QuestionAnswerAdvisor.Builder.userTextAdvise()` 方法已被弃用，建议使用 `.promptTemplate()`。

### 6.6 ChatService 无需改动

如果 `QuestionAnswerAdvisor` 已在 `defaultAdvisors` 中注册，`ChatService` 不需要任何改动：

```java
public String chat(String conversationId, String message) {
    return chatClient.prompt()
            .user(message)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
}
```


## 七、知识库初始化

### 7.1 knowledge.md 文件放在哪里

文件放在项目的 **`src/main/resources/document/`** 目录下，编译后 classpath 中可通过 `classpath:document/*.md` 读取：

```text
src/main/resources/
├── application.yml
└── document/
    └── knowledge.md
```

### 7.2 知识库加载器

`MarkdownDocumentReader` 读取 Markdown 资源，将标题、段落或水平线分隔的文本分组为 Document，配置可通过 `MarkdownDocumentReaderConfig` 控制。

```java
@Component
public class KnowledgeBaseLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    public KnowledgeBaseLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<Document> loadMarkdownDocuments() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            Resource[] resources = resourcePatternResolver
                    .getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", resource.getFilename())
                        .build();
                MarkdownDocumentReader reader =
                        new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(reader.get());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load markdown documents", e);
        }
        return allDocuments;
    }
}
```

### 7.3 知识库初始化器

初始化逻辑应独立成 `CommandLineRunner`，而不是放在 `VectorStoreConfig` 的 `@Bean` 方法中。这样职责清晰、启动时机可控、失败易定位。

```java
@Component
@Order(1)
public class KnowledgeBaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInitializer.class);

    private final KnowledgeBaseLoader loader;
    private final TokenTextSplitter splitter;
    private final VectorStore vectorStore;

    public KnowledgeBaseInitializer(KnowledgeBaseLoader loader,
                                    TokenTextSplitter splitter,
                                    VectorStore vectorStore) {
        this.loader = loader;
        this.splitter = splitter;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        log.info("【知识库初始化】开始加载文档...");

        List<Document> documents = loader.loadMarkdownDocuments();
        log.info("【知识库初始化】加载原始文档数: {}", documents.size());

        if (documents.isEmpty()) {
            log.warn("【知识库初始化】未加载到任何文档，请检查 classpath:document/*.md");
            return;
        }

        List<Document> chunks = splitter.apply(documents);
        log.info("【知识库初始化】分块后片段数: {}", chunks.size());

        vectorStore.add(chunks);
        log.info("【知识库初始化】写入向量库完成");
    }
}
```

### 7.4 依赖汇总

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-advisors-vector-store</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-markdown-document-reader</artifactId>
</dependency>
```


## 八、RAG 常见问题排查指南

当 RAG 检索不到文档时，按以下顺序逐步排查。

### 第一步：验证知识库是否成功初始化

检查启动日志中 `KnowledgeBaseInitializer` 的输出：

```
【知识库初始化】加载原始文档数: 1        ← 如果为 0，问题在 Loader
【知识库初始化】分块后片段数: 8          ← 如果为 0，问题在 Splitter
【知识库初始化】写入向量库完成
```

如果加载数量为 0，检查 `src/main/resources/document/` 目录是否存在 `.md` 文件，以及通配符路径是否正确。

### 第二步：绕过 Advisor，直接测试向量检索

```java
@Autowired
private VectorStore vectorStore;

@Test
void testSearch() {
    SearchRequest request = SearchRequest.builder()
            .query("Spring AI 的 Advisor 有哪些")
            .topK(5)
            .build();
    List<Document> results = vectorStore.similaritySearch(request);
    System.out.println("检索到文档数量: " + results.size());
    results.forEach(doc -> System.out.println(doc.getText()));
}
```

如果返回 0 条，说明问题在索引阶段（Embedding 模型或文档分块）；如果返回了文档，说明向量库有数据，问题出在 Advisor 配置上。

### 第三步：检查相似度阈值

相似度阈值的分布取决于所使用的 Embedding 模型。**阈值过高会导致检索不到任何文档**，过低则会引入无关噪音。如果你使用 Ollama 的 Embedding 模型，存在一个已知问题：设置了 `similarityThreshold` 后可能始终返回 0 条结果。

**排查方法**：临时将 `similarityThreshold` 设为 0.0（禁用阈值），如果此时能检索到文档，说明阈值设得太高。建议从 0.3-0.5 开始测试。

### 第四步：检查 Advisor 注册顺序

确认 `QuestionAnswerAdvisor` 在 Advisor 链中的位置。较低 order 值的 advisor 先执行，`QuestionAnswerAdvisor` 需要在请求即将发往 LLM 之前执行。显式指定 order 值以确保正确的执行顺序。

### 第五步：确认 Embedding 模型一致性

初始化知识库时使用的 `EmbeddingModel`，必须与 `QuestionAnswerAdvisor` 在检索时使用的是同一个 Bean。如果向量维度不一致（如 1536 vs 1024），检索会返回空结果。

### 第六步：打开调试日志

```yaml
logging:
  level:
    org.springframework.ai.chat.client.advisor: DEBUG
    org.springframework.ai.vectorstore: TRACE
```

控制台会打印 `QuestionAnswerAdvisor` 检索到的文档数量。如果显示 `Found 0 similar documents`，回到第三步检查阈值；如果显示了文档但模型没用，检查 Advisor 顺序。


## 九、验证

```bash
# 基础 RAG 问答
curl "http://localhost:8080/api/chat?conversationId=test&message=文档里提到了哪些核心概念？"

# RAG + 记忆协同
curl "http://localhost:8080/api/chat?conversationId=test&message=第一个概念具体是什么意思？"

# RAG + 工具协同
curl "http://localhost:8080/api/chat?conversationId=test&message=现在几点了？另外文档里讲了什么？"
```


## 十、进阶主题

### 10.1 查询重写（Query Rewrite）

用户问题可能冗长、模糊或包含无关信息，直接影响检索质量。`RewriteQueryTransformer` 使用 LLM 重写用户查询，以在向量存储或搜索引擎中获得更好的结果。这个 Transformer 属于 Spring AI 模块化 RAG 架构的**预检索（Pre-Retrieval）** 阶段。

```java
@Bean
public QueryTransformer queryTransformer(ChatClient.Builder builder) {
    return RewriteQueryTransformer.builder()
            .chatClientBuilder(builder)
            .build();
}
```

### 10.2 多路召回

单路检索的召回率有限。多路召回通过多个查询从不同角度检索，合并结果后重排序：

```
用户问题 → 查询重写 → 多向量扩展（生成2-4个子查询）
         → 对每个子查询并行检索
         → 去重 + 重排序
         → 组装 Prompt 生成答案
```

`MultiQueryExpander` 是实现多路召回的核心组件，可将召回率提升 20-30%。

### 10.3 Agentic RAG

传统 RAG 是“一次检索一次生成”。Agentic RAG 让 Agent 能够**迭代检索**——如果第一次检索结果不够，Agent 可以改写查询再次检索。这需要将检索能力封装为**工具**（阶段三的内容），由 Agent 自主决定何时检索、检索几次。


## 十一、核心问答

**Q：`VectorStore` 为什么不能直接注入？**

`VectorStore` 是一个接口，Spring 容器不会自动为接口创建实例。必须显式注册一个实现类 Bean（如 `SimpleVectorStore`），或者引入持久化向量库的 Starter 让 Spring AI 自动配置。

**Q：`QuestionAnswerAdvisor` 和 `RetrievalAugmentationAdvisor` 有什么区别？**

`QuestionAnswerAdvisor` 是开箱即用的简单 Advisor，适合标准两步 RAG。`RetrievalAugmentationAdvisor` 基于模块化架构，支持查询转换、文档后处理等高级功能。

**Q：相似度阈值设多少合适？**

取决于嵌入模型和文档质量。阈值过高会导致检索不到任何文档，过低会引入无关噪音。建议从 0.3-0.5 开始测试。

**Q：为什么 `QuestionAnswerAdvisor` 需要显式指定 order？**

Advisor 链按 `getOrder()` 值顺序执行，较低的值先执行。如果不指定 order，`QuestionAnswerAdvisor` 可能被放置在链中过于靠前的位置，导致其注入的检索结果被后续 Advisor 覆盖。

**Q：RAG 和工具调用如何选择？**

RAG 解决“模型不知道你的私有数据”的问题，工具调用解决“模型无法执行操作”的问题。两者互补，在 `ChatClientConfig` 中同时挂载 `QuestionAnswerAdvisor` 和注册工具即可。

**Q：`knowledge.md` 文件放在哪？**

放在 `src/main/resources/document/` 目录下。编译后该目录内容会复制到 classpath 根目录，代码中通过 `classpath:document/*.md` 读取。

**Q：`TokenTextSplitter` 为什么需要手动注册 Bean？**

`TokenTextSplitter` 是工具类，Spring 不会自动将其注册为 Bean。需要在 `@Configuration` 类中显式定义 `@Bean` 方法，否则构造函数注入时会报 `Could not autowire` 错误。

**Q：Embedding 模型报 404 怎么解决？**

Spring AI 默认使用 `text-embedding-ada-002`，该模型已被 OpenAI 废弃。需要在配置中显式指定 `spring.ai.openai.embedding.options.model`，指向端点实际支持的模型（如 `text-embedding-3-small` 或 `text-embedding-v3`）。


## 十二、阶段产出

- [ ] `VectorStoreConfig` — 注册 `SimpleVectorStore` Bean
- [ ] `KnowledgeBaseLoader` — 读取 `src/main/resources/document/*.md`
- [ ] `KnowledgeBaseInitializer` — 启动时加载文档到 VectorStore
- [ ] `ChatClientConfig` — 挂载 `QuestionAnswerAdvisor`，配置检索参数与 order
- [ ] `application-local.yml` — 配置正确的 Embedding 模型
- [ ] 验证 Agent 能基于私有知识库回答问题
- [ ] 验证 RAG 与记忆、工具三者协同工作
- [ ] `v0.4-rag` Tag


## 十三、下一步

阶段五引入 **MCP 协议集成**，让 Agent 能够连接外部 MCP Server，接入标准化工具生态。RAG 解决的是“知识注入”，MCP 解决的是“工具标准化”。届时你会发现，Agent 的能力边界不再受限于你自己编写的代码——任何符合 MCP 协议的服务都可以成为 Agent 的工具。


**参考**：

- [Spring AI - Retrieval Augmented Generation](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI - ETL Pipeline](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)
- [Spring AI - Vector Databases](https://docs.spring.io/spring-ai/reference/api/vectordbs.html)
- [Spring AI - Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Spring AI Alibaba - RAG](https://java2ai.com/docs/1.0.0.2/tutorials/basics/RAG/)