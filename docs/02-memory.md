# 02 - 对话记忆（Chat Memory）

## 阶段目标

理解 Spring AI 的记忆抽象，通过 `MessageChatMemoryAdvisor` 让 `ChatClient` 具备多轮对话能力，并掌握 `ChatMemory` 与 `ChatMemoryRepository` 的分层设计。

完成后你应该能够回答：

- 为什么 LLM 需要外挂记忆？记忆在请求链路的哪一环被注入？
- `ChatMemory` 和 `ChatMemoryRepository` 各自解决什么问题？
- 滑动窗口策略的取舍是什么？

---

## 一、核心认知：LLM 是无状态的

大语言模型本身不保留任何交互历史。所谓"多轮对话"，本质是在每次请求时把**历史消息列表**连同当前输入一起发给模型：

```
[系统提示] + [历史消息1] + [历史消息2] + ... + [用户最新提问] → LLM → 回复
```

Spring AI 的 Chat Memory 封装了这一过程，让开发者无需手动维护消息列表。

---

## 二、Advisor 链的第一个真实案例

阶段一提到 Advisor 是 Spring AI 的核心扩展点。`MessageChatMemoryAdvisor` 就是 Advisor 链的第一个实战应用，它在请求链路中**双向拦截**：

- **请求前**：从 `ChatMemory` 读取指定 `conversationId` 的历史消息，注入 Prompt
- **响应后**：自动将用户提问和模型回复写入 `ChatMemory`

这意味着 `ChatService` 的调用方式几乎不变，只需在调用时传入 `conversationId`，记忆读写完全由 Advisor 自动完成。

---

## 三、ChatMemory 的分层设计

Spring AI 1.0.x 将记忆系统拆分为**逻辑层**和**存储层**：

| 层次 | 接口 | 职责 |
|---|---|---|
| 逻辑层 | `ChatMemory` | 上下文管理、消息窗口控制 |
| 存储层 | `ChatMemoryRepository` | 消息的持久化读写 |

### ChatMemory 接口

```java
public interface ChatMemory {
    void add(String conversationId, List<Message> messages);
    List<Message> get(String conversationId);
    void clear(String conversationId);
}
```

### ChatMemoryRepository 接口

```java
public interface ChatMemoryRepository {
    List<String> findConversationIds();
    List<Message> findByConversationId(String conversationId);
    void saveAll(String conversationId, List<Message> messages);
    void deleteByConversationId(String conversationId);
}
```

**分层价值**：切换存储介质时，逻辑层的 `ChatMemory` 和上层的 Advisor 完全不用改。默认实现是 `InMemoryChatMemoryRepository`，内部用 `ConcurrentHashMap` 以会话 ID 为键存储消息。

---

## 四、MessageWindowChatMemory：滑动窗口策略

`ChatMemory` 的主要实现类是 `MessageWindowChatMemory`，采用**滑动窗口**策略：

- 维护固定大小的消息窗口，默认保留 **20 条**
- 超出最大值时移除最旧的消息
- 系统消息不会被自动移除

构造器私有，必须使用 Builder：

```java
@Bean
public ChatMemory chatMemory(ChatMemoryRepository repository) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(20)
            .build();
}
```

**为什么需要窗口**：每次请求都要把历史消息发给 LLM，消息越多 Token 消耗越大，也会分散模型注意力。生产环境推荐设置在 **10-50 条**之间，具体取决于模型 Context Window 和业务场景。

---

## 五、代码落地

### 5.1 ChatMemory Bean

```java
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }
}
```

### 5.2 挂载 Advisor 到 ChatClient

`MessageChatMemoryAdvisor` 的构造器是私有的，必须通过 Builder 创建：

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
    return builder
            .defaultSystem("你是一个乐于助人的AI助手，用简洁清晰的中文回答问题。")
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build()
            )
            .build();
}
```

> **注意**：`new MessageChatMemoryAdvisor(chatMemory)` 会报错——Spring AI 1.0.x 强制使用 Builder 模式，避免构造器参数膨胀，也让 API 更清晰。

### 5.3 ChatService 传入 conversationId

```java
public String chat(String conversationId, String message) {
    return chatClient.prompt()
            .user(message)
            .advisors(a -> a.param(
                    ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
}
```

`conversationId` 通过 Advisor 的 param 动态传入，同一个 `ChatClient` 可以服务多个会话。

### 5.4 ChatController

```java
@GetMapping
public String chat(
        @RequestParam String conversationId,
        @RequestParam String message) {
    return chatService.chat(conversationId, message);
}
```

### 5.5 验证

```bash
# 第一轮
curl "http://localhost:8080/api/chat?conversationId=user-001&message=我叫小明"

# 第二轮，同一 conversationId
curl "http://localhost:8080/api/chat?conversationId=user-001&message=我叫什么名字？"
# 预期：能回答"小明"

# 换会话
curl "http://localhost:8080/api/chat?conversationId=user-002&message=我叫什么名字？"
# 预期：模型不知道
```

---

## 六、持久化：从内存到数据库

内存存储的应用重启后记忆全部丢失。Spring AI 提供 JDBC 和 Redis 两种存储实现。

### 6.1 JDBC 方案

依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-jdbc</artifactId>
</dependency>
```

配置：

```yaml
spring:
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always   # 开发环境自动建表
```

替换 Repository Bean：

```java
@Bean
public ChatMemoryRepository chatMemoryRepository(JdbcTemplate jdbcTemplate) {
    return JdbcChatMemoryRepository.builder()
            .jdbcTemplate(jdbcTemplate)
            .build();
}
```

**`ChatMemory` 和 Advisor 的代码完全不用改**——这就是分层设计的价值。

### 6.2 Redis 方案

Spring AI 也提供 `RedisChatMemoryRepository`，将消息以 JSON 文档形式存储。微服务架构下，Redis 比 JDBC 更适合多实例共享会话状态。

---

## 七、核心问答

**Q：`MessageChatMemoryAdvisor` 和 `PromptChatMemoryAdvisor` 有什么区别？**

`MessageChatMemoryAdvisor` 将历史消息作为结构化的 Message 列表注入，保留角色信息（user/assistant/system）；`PromptChatMemoryAdvisor` 则是将历史拼接成一段文本注入。前者是 1.0.x 的推荐用法。

**Q：`conversationId` 应该由谁生成？**

通常由前端在会话开始时生成唯一 ID（如 UUID），后续请求携带同一个 ID。也可由后端根据 `userId + sessionId` 生成。关键是保证**同一会话使用同一 ID，不同用户之间隔离**。

**Q：窗口满了之后，被移除的消息还能找回来吗？**

不能。`MessageWindowChatMemory` 移除旧消息时是永久删除。如需保留完整历史，需要自己实现 `ChatMemoryRepository`，在 `saveAll` 时同步写入不参与窗口的归档存储。

**Q：多个用户并发访问，`InMemoryChatMemoryRepository` 线程安全吗？**

安全。内部使用 `ConcurrentHashMap`，以 `conversationId` 为键隔离不同会话。但不同实例之间不共享数据，分布式部署需换 Redis 或 JDBC。

**Q：为什么 Advisor 要用 Builder 模式创建？**

Spring AI 1.0.x 将 Advisor 的构造器设为私有，强制使用 Builder。原因是 Advisor 有多个可选配置项（如 `conversationId`、`order`），Builder 能避免构造器参数列表膨胀，也让 API 更易演进。

**Q：`maxMessages` 设多少合适？**

需要权衡：窗口越大上下文越完整，但 Token 消耗越大且容易分散模型注意力。默认 20 条对多数场景够用；长对话场景可适当调大，但要注意模型的 Context Window 上限。

---

## 八、阶段产出

- [ ] `ChatMemoryConfig` — `ChatMemory` 和 `ChatMemoryRepository` Bean
- [ ] `ChatClientConfig` — 挂载 `MessageChatMemoryAdvisor`
- [ ] `ChatService` — 增加 `conversationId` 参数
- [ ] `ChatController` — 接口增加 `conversationId`
- [ ] 多轮对话验证通过，不同 `conversationId` 会话隔离
- [ ] `v0.2-memory` Tag

---

## 九、下一步

阶段三引入 **Tool Calling**，让 Agent 具备调用外部工具的能力（搜索、文件操作、时间查询）。

届时 `ChatClient` 配置会增加 `defaultTools`，而 `ChatService` 的调用方式基本不变——**Advisor 链和工具调用可以叠加使用**。这是 Spring AI 模块化设计带来的好处：每个能力独立演进，组合时不互相干扰。

---

## 参考

- [Spring AI - Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI - Advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [MessageChatMemoryAdvisor Javadoc](https://spring.pleiades.io/spring-ai/docs/current/api/org/springframework/ai/chat/client/advisor/MessageChatMemoryAdvisor.html)