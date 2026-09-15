# 01 - 基础对话与 Spring AI 核心抽象

## 阶段目标

跑通 `ChatClient` → LLM 链路，理解 Spring AI 的核心抽象：**ChatClient、Advisor、Prompt 结构**。

---

## 一、ChatClient：模型无关的对话入口

Spring AI 最核心的价值是**屏蔽模型厂商差异**。`ChatClient` 是这一抽象的门面，无论底层是 OpenAI、DashScope 还是 Ollama，调用方式完全一致。

### 基础调用链

```java
String answer = chatClient.prompt()
        .user("你好")
        .call()
        .content();
```

四个环节分别对应：

| 环节 | 作用 |
|---|---|
| `.prompt()` | 创建 Prompt 构建器 |
| `.user(...)` | 填充用户消息 |
| `.call()` | 同步调用模型 |
| `.content()` | 提取文本内容 |

流式版本把 `.call()` 换成 `.stream()`，返回 `Flux<String>`。

### Prompt 的组成

一次请求由多段消息构成，Spring AI 用 `ChatClient` 的链式 API 表达：

```java
chatClient.prompt()
        .system("你是一个Java专家")     // 系统提示
        .user("解释一下Spring AI")       // 用户消息
        .call()
        .content();
```

还有 `.assistant()` 用于注入历史助手回复，阶段二引入记忆后会大量使用。

---

## 二、ChatClient.Builder 与 Bean 装配

不要手动 `new ChatClient`，而是注入 `ChatClient.Builder`：

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder) {
    return builder
            .defaultSystem("你是一个乐于助人的AI助手，用简洁清晰的中文回答问题。")
            .build();
}
```

**为什么用 Builder**：Starter 已经为 Builder 装配了模型配置、重试策略、可观测性等基础设施。手动 new 会丢失这些能力。

**为什么集中定义**：`defaultSystem` 注册在 Builder 上，所有调用自动带上系统提示。后续阶段的记忆、RAG、工具也都是通过 Builder 注入 Advisor，形成统一的增强链。

---

## 三、Advisor 机制：Spring AI 的扩展点

Advisor 是 Spring AI 最重要的设计，本质是**围绕 LLM 调用的拦截器链**。每次请求按顺序经过 Advisor 链，每个 Advisor 可以修改输入 Prompt 或拦截输出响应。

```
请求 → Advisor1 → Advisor2 → ... → LLM → ... → Advisor2 → Advisor1 → 响应
```

Spring AI 内置的关键 Advisor：

| Advisor | 作用 | 引入阶段 |
|---|---|---|
| `MessageChatMemoryAdvisor` | 对话记忆 | 阶段二 |
| `QuestionAnswerAdvisor` | RAG 检索增强 | 阶段四 |
| `SimpleLoggerAdvisor` | 请求响应日志 | 阶段七 |
| 自定义 Advisor | 可观测性、安全护栏 | 阶段七 |

**理解 Advisor 是理解 Spring AI 的关键**。阶段一虽未显式使用，但 `defaultSystem` 本质也是一种内置增强。从阶段二开始，每引入一个新能力，都是在 Advisor 链上增加一环，而 `ChatService` 的调用方式几乎不变。

---

## 四、模型无关性

Spring AI 的 OpenAI Starter 兼容所有 OpenAI 协议服务，切换模型只改配置：

```yaml
# OpenAI
spring.ai.openai.base-url: https://api.openai.com
spring.ai.openai.chat.options.model: gpt-4o-mini

# DashScope 兼容模式
spring.ai.openai.base-url: https://dashscope.aliyuncs.com/compatible-mode
spring.ai.openai.chat.options.model: qwen-plus

# 本地 Ollama（需换 Starter）
spring.ai.ollama.base-url: http://localhost:11434
spring.ai.ollama.chat.options.model: qwen2.5
```

Java 代码零改动。这就是 ChatClient 抽象的价值。

---

## 五、API Key 管理

`application-local.yml` 放在**项目根目录**（不是 `src/main/resources`），Spring Boot 从 `file:./` 加载，优先级高于 classpath，且不提交、不打包：

```yaml
spring:
  ai:
    openai:
      api-key: sk-你的真实key
```

`.gitignore` 中加入 `application-local.yml`，仓库提交 `application-local.yml.example` 作为模板。

启动时激活：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

---

## 六、分层约定

```
controller/  → 只做参数接收
service/     → 承载 ChatClient 调用
config/      → ChatClient Bean 与 Advisor 装配
```

**后续所有阶段改动集中在 service 和 config，controller 保持不变**。这是阶段一最重要的结构决策。

---

## 七、核心问答

**Q：ChatClient 和 ChatModel 有什么区别？**

`ChatModel` 是底层模型接口，直接暴露 `call(Prompt)`。`ChatClient` 是上层门面，提供流畅 API、Advisor 链、默认配置。业务代码用 ChatClient，只有在写自定义 Advisor 或底层适配时才接触 ChatModel。

**Q：为什么 prompt 要用 `.user()` 而不是直接传字符串？**

因为一次 LLM 请求是多角色消息的集合（system / user / assistant）。`.user()` 只是其中最常用的一段，`.system()` 和 `.assistant()` 分别对应另外两种角色。阶段二引入记忆后，历史消息会自动以 assistant 角色注入，理解这个结构才能理解记忆的工作方式。

**Q：`defaultSystem` 和每次调用 `.system()` 会不会冲突？**

会叠加。`defaultSystem` 是默认值，调用时显式传入的 `.system()` 会作为额外消息追加，模型同时看到两段系统提示。建议系统级提示统一放 `defaultSystem`，调用级的临时约束才用 `.system()`。

**Q：切换模型后代码里有没有硬编码风险？**

没有。`ChatClient` API 完全模型无关，代码里不应出现任何模型名、厂商名。所有模型相关配置都在 `application-local.yml` 中。

---

## 八、阶段产出

- [x] `ChatClient` Bean，带 `defaultSystem`
- [x] `ChatService` 封装调用逻辑
- [x] `ChatController` 暴露 HTTP 接口
- [x] API Key 通过 `application-local.yml` 隔离
- [x] `v0.1-chat` Tag

---

## 九、下一步

阶段二引入 **Advisor 链的第一个真实案例**：`MessageChatMemoryAdvisor`。

将回答两个问题：

1. 同一 `conversationId` 的多轮对话如何保持上下文？
2. 应用重启后会话如何恢复？

核心改动：在 `ChatClientConfig` 的 Builder 上挂载 Advisor，`ChatService` 增加 `conversationId` 参数。Controller 不变。

---

## 参考

- [Spring AI - ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Spring AI - Advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Spring AI - Chat Models](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)