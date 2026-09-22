# 05 - MCP 协议集成

## 阶段目标

让 Agent 能够连接外部 MCP Server，接入标准化工具生态。理解 MCP 的 Client-Server 架构、与 Function Calling 的关系，并完整掌握接入一个外部已有 MCP 服务的流程与排查方法。

完成后你应该能够回答：

- MCP 解决了 Function Calling 的什么扩展性问题？
- 如何接入一个外部已有的 MCP 服务？
- MCP 工具已发现但模型不调用时，如何排查？
- RAG 与 MCP 工具冲突时，如何让两者共存？


## 一、核心认知：为什么需要 MCP

### 1.1 Function Calling 的痛点

阶段三的 `@Tool` 注解让 Agent 能调用外部能力，但工具是**绑定在应用代码里的**。如果 10 个应用都需要"查天气"功能，就要写 10 遍。这就像 USB 出现之前的时代——每个外设都要自己写驱动程序。

### 1.2 MCP 的定位

MCP（Model Context Protocol）是 Anthropic 推出的开放协议，目标是让工具像 USB 设备一样"即插即用"。它把工具的定义和实现从应用中**解耦**，变成独立的"工具服务"。

| 维度 | USB（硬件世界） | MCP（AI 工具世界） |
|---|---|---|
| 解决的问题 | 每个外设都要自己写驱动 | 每个应用都要自己定义工具 |
| 标准化的是什么 | 物理接口 + 通信协议 | 工具描述格式 + 调用协议 |
| "即插即用" | 插上U盘就能用 | 配置 MCP Server 地址就能用 |
| 跨平台 | Windows/Mac/Linux 都能用 | 任何支持 MCP 的 AI 框架都能用 |

### 1.3 MCP 与 Function Calling 的关系

MCP **不是** Function Calling 的替代品，而是它的**标准化和规模化扩展**。Function Calling 是模型和应用之间的调用约定，MCP 是应用和工具进程之间的发现与传输协议。Spring 把后者适配成前者——MCP 工具最终还是会走 Function Calling 那条链路。

| 维度 | Function Calling | MCP |
|---|---|---|
| 工具定义位置 | 写在应用代码里（`@Tool`） | 独立的 MCP Server |
| 复用性 | 每个应用重新定义 | 一次开发，到处使用 |
| 通信方式 | 进程内方法调用 | 跨进程/跨网络 |
| 适用场景 | 工具逻辑简单，只在当前应用使用 | 工具需要被多个应用复用，或独立部署 |


## 二、MCP 的 Client-Server 架构

```
┌──────────────────┐        MCP协议        ┌──────────────────┐
│   MCP Host       │ <──────────────────> │   MCP Server     │
│   (AI 应用)       │   1. 发现工具列表      │   (工具服务)      │
│   - Spring应用    │   2. 调用工具          │   - 提供文件操作   │
│   - Claude       │   3. 获取结果          │   - 提供天气查询   │
└──────────────────┘                       └──────────────────┘
        │                                          │
        │ 内置                                     │ 独立进程
┌──────────────────┐                       ┌──────────────────┐
│   MCP Client     │                       │   实际能力:       │
│   (协议客户端)    │                       │   - REST API     │
│   - 解析MCP协议   │                       │   - 数据库       │
│   - 翻译调用请求   │                       │   - 文件系统     │
└──────────────────┘                       └──────────────────┘
```

**MCP Server**：轻量级程序，通过标准化协议暴露特定功能（工具、资源、提示词）。每个 Server 独立部署，可以被多个 Host 复用。

**MCP Client**：由 Host 应用创建，与特定的 MCP Server 保持 1:1 连接。

**MCP Host**：用户交互的 AI 应用（你的 Spring Boot 应用），负责编排多个 MCP Server 的调用。

### 2.1 传输方式

| 传输方式 | 适用场景 | 特点 |
|---|---|---|
| **STDIO** | 本地进程通信 | 基于标准输入/输出流，适用于轻量级本地工具 |
| **SSE** | Web 集成 | 基于 HTTP 的服务器发送事件，适用于远程服务 |
| **Streamable HTTP** | 有状态会话管理 | 支持可恢复的流式传输，适合生产环境 |


## 三、接入外部 MCP 服务：文件系统完整示例

以接入官方提供的**文件系统 MCP Server** 为例，完整演示连接流程。

### 3.1 环境准备

文件系统 MCP Server 基于 Node.js，确保本地已安装 **Node.js (v18+)**。

### 3.2 添加依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

### 3.3 配置 MCP 连接

在 `src/main/resources/` 下创建 `mcp-servers.json`：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "/Users/your-username/Desktop"
      ]
    }
  }
}
```

**参数说明**：

- `command`：启动命令。Windows 下需指定 `npx.cmd` 的完整路径
- `-y`：自动确认安装 `@modelcontextprotocol/server-filesystem` 包
- 最后一个参数：**允许 AI 访问的根目录**，必须是绝对路径且目录存在

### 3.4 启用 MCP Client 与工具回调

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: my-mcp-client
        version: 1.0.0
        request-timeout: 30s
        type: SYNC
        toolcallback:
          enabled: true                              # 关键配置，必须为 true
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

**关键配置项**：

- `toolcallback.enabled: true`：启用 MCP 工具回调与 Spring AI 工具执行框架的集成，**必须开启**才能将远端工具注册到 `ChatClient`
- `type: SYNC`：同步客户端模式，也可设为 `ASYNC`
- `request-timeout`：请求超时时间，防止 MCP 调用阻塞 AI 应用

### 3.5 注册 MCP 工具到 ChatClient

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder,
                             ChatMemory chatMemory,
                             VectorStore vectorStore,
                             ToolCallbackProvider mcpTools) {
    var callbacks = mcpTools.getToolCallbacks();
    log.info("【ChatClient配置】注册MCP工具数量: {}", callbacks.length);

    return builder
            .defaultSystem("你是一个乐于助人的AI助手。")
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build(),
                    QuestionAnswerAdvisor.builder(vectorStore).order(10).build()
            )
            .defaultTools(new DateTimeTools())
            .defaultToolCallbacks(callbacks)   // 关键：必须用 defaultToolCallbacks
            .build();
}
```

> **重要**：Spring AI 正式版中，MCP 工具需要使用 `defaultToolCallbacks()` 注册，而不是 `defaultTools()`。使用后者会导致工具静默失效，模型看不到任何工具。

### 3.6 验证

```bash
# 让 AI 列出桌面文件
curl "http://localhost:8080/api/chat?conversationId=test&message=帮我看看桌面上有哪些文件？"

# 让 AI 读取指定文件内容
curl "http://localhost:8080/api/chat?conversationId=test&message=读取桌面上的 test.txt 文件"

# 让 AI 创建新文件
curl "http://localhost:8080/api/chat?conversationId=test&message=在桌面上创建 hello.txt，内容写 'Hello MCP'"
```


## 四、工具发现日志解读

连接成功后，MCP 调试日志会显示完整的握手与工具发现过程。

### 4.1 协议握手

```
Server response with Protocol: 2024-11-05,
Capabilities: ServerCapabilities[..., tools=ToolCapabilities[listChanged=true]],
Info: Implementation[name=secure-filesystem-server, version=0.2.0]
```

说明 MCP Client 与 Server 连接成功，Server 支持工具能力。

### 4.2 工具列表

```
Received Response: JSONRPCResponse[...,
result={tools=[
  {name=read_text_file, ...},
  {name=read_media_file, ...},
  {name=write_file, ...},
  {name=edit_file, ...},
  {name=list_directory, ...},
  {name=directory_tree, ...},
  {name=search_files, ...},
  {name=list_allowed_directories, ...}
]}]
```

文件系统 MCP Server 默认暴露 13+ 个工具，覆盖读取、写入、编辑、目录列表、搜索等操作。

### 4.3 目录权限提示

```
STDERR Message received: Client does not support MCP Roots,
using allowed directories set from server args: [ '/Users/your-username/Desktop' ]
```

Server 的安全机制：即使 Client 不支持动态目录授权，Server 也会在启动参数中指定固定的允许目录，防止越权访问。


## 五、常见问题排查

### 5.1 ToolCallbackProvider 无法注入

**现象**：`Could not autowire. No beans of 'ToolCallbackProvider' type found.`

**原因**：`spring.ai.mcp.client.toolcallback.enabled` 未开启。

**解决方案**：

```yaml
spring:
  ai:
    mcp:
      client:
        toolcallback:
          enabled: true
```

如果开启后仍无法注入，手动定义 Bean 作为兜底：

```java
@Bean
@Primary
public ToolCallbackProvider toolCallbackProvider(List<McpSyncClient> mcpSyncClients) {
    return new SyncMcpToolCallbackProvider(mcpSyncClients);
}
```

### 5.2 工具已发现但模型不调用

**现象**：日志显示工具列表已加载，但模型返回纯文本，不调用任何工具。

这是本项目实际遇到的问题，分为**两层原因**。

#### 第一层：工具未正确注册到 ChatClient

**API 使用错误**：MCP 工具必须用 `defaultToolCallbacks()` 注册。

```java
.defaultToolCallbacks(tools.getToolCallbacks())   // ✅
.defaultTools(tools)                              // ❌ 静默失效
```

**验证方法**：在 `ChatClientConfig` 中打印工具数量：

```java
var callbacks = mcpTools.getToolCallbacks();
log.info("MCP工具数量: {}", callbacks.length);
for (var cb : callbacks) {
    log.info("  - {}", cb.getToolDefinition().name());
}
```

如果数量为 0，说明初始化时序有问题（MCP Client 未完全就绪时 `ChatClient` 已被创建），手动定义 `ToolCallbackProvider` Bean 即可。

#### 第二层：RAG 自定义 Prompt 模板抑制了工具调用

**这是本项目实际踩过的坑**。阶段四为 `QuestionAnswerAdvisor` 配置了强约束的自定义模板：

```
根据上下文信息且没有先验知识，回答查询。
遵循以下规则：
1. 如果答案不在上下文中，只需说你不知道。
2. 不要编造答案。
```

这段模板的**排他性指令**向 LLM 传达了一个信号："**只能用上下文，不能用工具**"。当用户问"桌面上有哪些文件"时，RAG 检索到的上下文是 `knowledge.md` 的内容（关于 Spring AI 的知识），完全不含桌面信息，LLM 只能照着模板说"我不知道"。

**解决方案**：移除模板的排他性指令，改为中性引导：

```java
PromptTemplate customTemplate = PromptTemplate.builder()
    .renderer(StTemplateRenderer.builder()
        .startDelimiterToken('<')
        .endDelimiterToken('>')
        .build())
    .template("""
        以下是可能相关的背景信息，仅供参考。
        如果你能从中找到答案，请结合它回答。
        如果你需要获取实时数据或执行操作，请使用可用的工具。
        
        背景信息：
        <question_answer_context>
        
        用户问题：<query>
        """)
    .build();
```

并在 `defaultSystem` 中明确分工：

```java
.defaultSystem("""
    你是一个智能助手，拥有两种信息来源：
    
    1. **上下文信息 (Context)**：从知识库中检索到的背景资料。
       - 适用于：回答关于已录入文档、政策、通用知识的问题。
    
    2. **工具 (Tools)**：可以调用的外部功能。
       - 适用于：获取实时数据、查询外部系统、操作文件系统。
    
    **决策原则**：
    - 如果用户问题涉及实时信息或需要执行操作，**必须调用工具**。
    - 如果问题可以从上下文信息中找到答案，则直接基于上下文回答。
    - 如果两者都不足以回答，请如实告知用户。
    """)
```

### 5.3 日志不输出

**现象**：配置了 `logging.level.org.springframework.ai: DEBUG`，但没有任何日志。

**原因**：Spring AI 的日志输出主要依赖 `SimpleLoggerAdvisor`，仅修改日志级别而**不注册 Advisor**，不会有任何日志输出。

**解决方案**：在 `ChatClient` 中注册 `SimpleLoggerAdvisor`：

```java
.defaultAdvisors(
    new SimpleLoggerAdvisor(),
    MessageChatMemoryAdvisor.builder(chatMemory).build(),
    QuestionAnswerAdvisor.builder(vectorStore).order(10).build()
)
```

同时精确指定日志级别：

```yaml
logging:
  level:
    org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor: DEBUG
```

### 5.4 排查清单

| 检查项 | 预期值 | 说明 |
|---|---|---|
| `spring.ai.mcp.client.toolcallback.enabled` | `true` | 否则工具回调 Bean 不会创建 |
| 使用 `defaultToolCallbacks()` 注册 | 是 | 不能用 `defaultTools()` |
| `ChatClientConfig` 打印工具数量 | 13+ | 否则注册环节失败 |
| RAG 自定义模板是否排他 | 中性引导 | 不能写"只用上下文" |
| 系统提示明确工具使用场景 | 是 | 让 LLM 知道何时用工具 |
| `SimpleLoggerAdvisor` 已注册 | 是 | 否则日志不输出 |
| 模型支持工具调用 | 是 | 换 `gpt-4o` 或 `qwen-max` 测试 |


## 六、进阶主题

### 6.1 连接 SSE 类型的远程 MCP 服务

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            mcp-hub:
              url: http://localhost:3000
              sse-endpoint: /mcp-hub/sse/xxxxx
```

连接建立后，远端工具自动被发现并注册，`ChatClient` 代码无需改动。

### 6.2 作为 MCP Server 暴露自己的能力

添加依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

使用 `@McpTool` 注解声明工具：

```java
@Service
public class McpWeatherService {

    @McpTool(description = "获取指定城市的当前温度")
    public String getTemperature(
            @McpToolParam(description = "城市名称", required = true) String city) {
        return String.format("%s 当前温度: 22°C", city);
    }
}
```

配置：

```yaml
spring:
  ai:
    mcp:
      server:
        name: weather-mcp-server
        version: 1.0.0
        protocol: STREAMABLE
```

### 6.3 动态工具更新

MCP Server 支持运行时动态添加或移除工具，并通过 `tool-change-notification` 通知客户端。客户端可以注册 `@McpToolListChanged` 处理器响应变更。

### 6.4 采样（Sampling）

MCP 的采样功能允许 Server 反向请求 Client 的 LLM 生成内容，实现**双向 AI 交互**。适用于 Server 端需要 LLM 能力但不想部署 LLM 的场景。


## 七、核心问答

**Q：MCP 和 Function Calling 是二选一吗？**

不是。MCP 工具最终走 Function Calling 的调用链路。Function Calling 是模型和应用之间的调用约定，MCP 是应用和工具进程之间的发现与传输协议。两者是叠加关系。

**Q：什么时候应该用 MCP Server，什么时候直接用 `@Tool`？**

工具逻辑简单、只在当前应用内使用，用 `@Tool` 即可。工具需要被多个应用复用、需要独立部署、或需要跨语言/跨团队提供时，封装为 MCP Server。

**Q：`defaultTools()` 和 `defaultToolCallbacks()` 有什么区别？**

`defaultTools()` 接受工具对象实例（如 `@Tool` 注解的类），`defaultToolCallbacks()` 接受 `ToolCallback` 数组。MCP 工具通过 `ToolCallbackProvider.getToolCallbacks()` 返回 `ToolCallback[]`，因此必须用后者。用错 API 会导致工具静默失效。

**Q：为什么 RAG 和 MCP 工具会冲突？**

`QuestionAnswerAdvisor` 的自定义 `PromptTemplate` 如果包含"只根据上下文回答"之类的排他性指令，会抑制 LLM 调用工具的意愿。解决方案是移除排他性指令，并在系统提示中明确工具的使用场景。

**Q：MCP Client 能同时连接多个 MCP Server 吗？**

可以。Spring AI 的 MCP Client Starter 支持同时连接多个 MCP Server，每个 Server 对应一个独立的连接。

**Q：为什么配置了 DEBUG 日志却没有输出？**

Spring AI 的日志主要依赖 `SimpleLoggerAdvisor`。仅修改日志级别而不注册该 Advisor，不会有任何日志输出。需要在 `ChatClient` 的 `defaultAdvisors` 中加入 `new SimpleLoggerAdvisor()`。

**Q：`@McpTool` 和 `@Tool` 有什么区别？**

`@Tool` 用于本地工具（阶段三），`@McpTool` 用于 MCP Server 端暴露工具。两者 API 风格一致，但作用域不同。


## 八、阶段产出

- [ ] `mcp-servers.json` — 外部 MCP Server 连接配置
- [ ] `application-local.yml` — 启用 MCP Client 和 toolcallback
- [ ] `ChatClientConfig` — 通过 `defaultToolCallbacks` 注册 MCP 工具
- [ ] `SimpleLoggerAdvisor` — 注册日志 Advisor
- [ ] 调整 RAG 自定义 Prompt 模板，移除排他性指令
- [ ] `defaultSystem` 中明确 Context 与 Tools 的决策原则
- [ ] 验证 Agent 能通过 MCP 协议调用外部文件系统工具
- [ ] 验证 MCP 工具与本地工具、RAG、记忆四者协同工作
- [ ] `v0.5-mcp` Tag


## 九、下一步

阶段六引入**多 Agent 协作**，将复杂任务拆解为多个专业化 Agent 的协作。届时会引入 Spring AI Alibaba 的 Graph 工作流和 Multi-Agent 编排能力。MCP 解决了"工具标准化"，多 Agent 协作解决的是"任务分解与编排"——这是 Agent 从"单个助手"走向"团队协作"的关键一步。


**参考**：

- [Spring AI - MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)
- [Spring AI - MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [Spring AI - Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [MCP 官方服务器列表](https://github.com/modelcontextprotocol/servers)
- [MCP 官方规范](https://modelcontextprotocol.io/specification)