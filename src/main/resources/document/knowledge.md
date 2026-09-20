# Spring AI Agent 学习知识库

本文档是 `spring-ai-agent-learning` 项目的示例知识库，用于演示 RAG（检索增强生成）功能。内容涵盖 Spring AI 的核心概念、Agent 开发的基础知识，以及本项目的学习路径。

## 一、Spring AI 是什么

Spring AI 是 Spring 官方推出的 AI 应用开发框架，目标是让 Java 开发者能够像使用 Spring Data、Spring Web 一样，以熟悉的方式接入大语言模型。

它的核心价值在于：

- **模型无关**：通过统一的 `ChatClient` API 屏蔽不同厂商的协议差异，切换 OpenAI、DashScope、Ollama 只需修改配置。
- **模块化设计**：对话、记忆、工具调用、RAG 等能力以 Advisor 链的形式叠加，每个能力独立演进。
- **企业级集成**：与 Spring Boot、Spring Data、Spring Security 等无缝配合。

## 二、ChatClient

`ChatClient` 是 Spring AI 最重要的抽象，它提供了流畅的 API 来与 LLM 交互。

基础调用链如下：

```
chatClient.prompt()
        .user("你好")
        .call()
        .content();
```

四个环节分别对应：创建 Prompt、填充用户消息、同步调用、提取文本内容。流式版本将 `.call()` 替换为 `.stream()`，返回 `Flux<String>`。

`ChatClient` 应通过 `ChatClient.Builder` 构建，而不是手动 new。Builder 由 Starter 自动装配，已经注入了模型配置、重试策略和可观测性等基础设施。

## 三、Advisor 机制

Advisor 是 Spring AI 的核心扩展点，本质是围绕 LLM 调用的拦截器链。每次请求按顺序经过 Advisor 链，每个 Advisor 可以修改输入 Prompt 或拦截输出响应。

Spring AI 内置的关键 Advisor 包括：

- `MessageChatMemoryAdvisor`：对话记忆，自动注入历史消息并保存新消息。
- `QuestionAnswerAdvisor`：RAG 检索增强，从向量库检索相关文档并注入 Prompt。
- `SimpleLoggerAdvisor`：记录请求和响应日志。

自定义 Advisor 可以实现可观测性、安全护栏等功能。Advisor 链的设计让新增能力时，`ChatService` 的调用方式几乎不变。

## 四、对话记忆（Chat Memory）

LLM 本身是无状态的，多轮对话的本质是在每次请求时把历史消息列表一起发给模型。Spring AI 通过 `ChatMemory` 和 `ChatMemoryRepository` 两层抽象来管理记忆。

- `ChatMemory`：逻辑层，负责上下文管理和滑动窗口控制。
- `ChatMemoryRepository`：存储层，负责消息的持久化读写。

默认实现 `InMemoryChatMemoryRepository` 使用 `ConcurrentHashMap` 存储。`MessageWindowChatMemory` 采用滑动窗口策略，默认保留 20 条消息，超出时移除最旧的消息。生产环境可切换为 JDBC 或 Redis 存储。

使用 `MessageChatMemoryAdvisor` 时，必须在调用时传入 `conversationId`，否则会抛出异常。

## 五、工具调用（Tool Calling）

工具调用让 Agent 具备执行外部操作的能力。它的本质是：模型不直接执行工具，而是在响应中表达调用特定工具的意图，应用程序执行工具后将结果返回给模型。

在 Spring AI 中，通过 `@Tool` 注解定义工具：

```
@Tool(description = "获取指定城市的天气信息")
public String getWeather(
        @ToolParam(description = "城市名称，例如：北京") String city) {
    return city + "：晴天，25°C";
}
```

工具通过 `ChatClient.Builder` 的 `defaultTools()` 方法全局注册，或通过 `.tools()` 在单次请求中追加。`@Tool` 的 `description` 直接影响模型选择工具的准确性，描述越清晰，模型判断越准确。

## 六、RAG 检索增强生成

RAG 解决 LLM 无法访问私有数据的问题。它的核心思想是：在查询时检索相关的外部知识，将其注入 Prompt，用特定上下文增强 LLM 的回答。

RAG 分为两个阶段：

- **索引阶段（离线）**：文档加载 → 文本分割 → 向量化 → 存入向量数据库。
- **检索生成阶段（在线）**：用户问题 → 查询向量化 → 相似度匹配 → 上下文组装 → LLM 生成答案。

Spring AI 的 ETL 管道包含四个核心组件：`DocumentReader`、`TextSplitter`、`EmbeddingModel` 和 `VectorStore`。每个组件都是可插拔的。

`QuestionAnswerAdvisor` 是开箱即用的 RAG Advisor，通过 `.searchRequest()` 配置检索参数，通过 `.promptTemplate()` 自定义 Prompt 模板。模板中必须包含 `query` 和 `question_answer_context` 两个占位符。

开发阶段推荐使用 `SimpleVectorStore`，它基于内存，无需额外服务。生产环境可切换为 PgVector、Redis 或 Milvus。

## 七、MCP 协议

MCP（Model Context Protocol）是 Anthropic 提出的开放协议，用于标准化 Agent 与外部工具的交互。它让不同框架、不同语言的 Agent 可以共享工具定义。

在 Spring AI 中，Agent 可以作为 MCP Client 连接外部 MCP Server，将 MCP 工具暴露给 LLM；也可以作为 MCP Server，将自己的工具封装后供其他 Agent 调用。

MCP 与 Function Calling 的关系：MCP 是 Function Calling 的一种标准化实现，本质上仍然是模型决定调用什么工具。

## 八、多 Agent 协作

多 Agent 协作将复杂任务拆解为多个专业化 Agent 的协作。常见的协作模式包括：

- **SubAgent 模式**：主 Agent 将子任务委派给拥有独立记忆和工具的子 Agent。
- **Handoffs 模式**：一个 Agent 决定将控制权移交给另一个 Agent。
- **Supervisor 模式**：Supervisor Agent 将其他 Agent 作为工具调用。

多 Agent 设计的核心是上下文工程——决定每个 Agent 看到什么信息。Spring AI Alibaba 提供了 Graph 工作流和多 Agent 编排能力，适合构建复杂的协作系统。

## 九、本项目学习路径

`spring-ai-agent-learning` 项目以个人知识助理为场景，将 Agent 开发拆解为七个阶段：

1. 基础对话与 Spring AI 核心抽象（`v0.1-chat`）
2. 对话记忆（`v0.2-memory`）
3. 工具调用（`v0.3-tools`）
4. RAG 检索增强（`v0.4-rag`）
5. MCP 协议集成（`v0.5-mcp`）
6. 多 Agent 协作（`v0.6-multi-agent`）
7. 可观测性与工程化（`v0.7-observability`）

每个阶段对应一个可运行的 Git Tag，配合 `docs/` 下的学习笔记，帮助 Java 开发者逐步理解 Agent 开发的知识细节。

## 十、常见问题

**问：ChatClient 和 ChatModel 有什么区别？**

答：`ChatModel` 是底层模型接口，直接暴露 `call(Prompt)`。`ChatClient` 是上层门面，提供流畅 API、Advisor 链和默认配置。业务代码应使用 ChatClient。

**问：为什么 Advisor 要用 Builder 模式创建？**

答：Spring AI 1.0.x 将 Advisor 的构造器设为私有，强制使用 Builder。原因是 Advisor 有多个可选配置项，Builder 能避免构造器参数列表膨胀。

**问：SimpleVectorStore 能在生产环境用吗？**

答：不建议。`SimpleVectorStore` 基于内存，重启后数据丢失，且不支持分布式部署。生产环境应使用 PgVector、Redis 或 Milvus。

**问：RAG 和工具调用可以同时使用吗？**

答：可以。RAG 注入知识上下文，工具调用执行动作，两者互补。在 `ChatClientConfig` 中同时挂载 `QuestionAnswerAdvisor` 和注册工具即可。