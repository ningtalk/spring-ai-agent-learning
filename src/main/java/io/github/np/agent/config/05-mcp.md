# 05 - MCP 协议集成

## 阶段目标

让 Agent 能够连接外部 MCP Server，接入标准化工具生态。理解 MCP 的 Client-Server 架构、与 Function Calling 的关系，以及如何用 Spring AI 连接一个已有的外部 MCP 服务。

完成后你应该能够回答：

- MCP 解决了 Function Calling 的什么扩展性问题？
- MCP Client 和 MCP Server 各自承担什么职责？
- 如何接入一个外部已有的 MCP 服务？
- 工具已发现但模型不调用时，如何排查？


## 一、核心认知：为什么需要 MCP

### 1.1 Function Calling 的痛点

阶段三的 `@Tool` 注解让 Agent 能调用外部能力，但工具是**绑定在应用代码里的**。如果 10 个应用都需要"查天气"功能，就要写 10 遍。这就像 USB 出现之前的时代——每个外设都要自己写驱动程序。

### 1.2 MCP 的定位

MCP（Model Context Protocol）是 Anthropic 推出的开放协议，目标是让工具像 USB 设备一样"即插即用"。它把工具的定义和实现从应用中**解耦**，变成独立的"工具服务"。任何遵循 MCP 协议的 AI 应用，都能直接连接这些工具服务。

| 维度 | USB（硬件世界） | MCP（AI 工具世界） |
|---|---|---|
| 解决的问题 | 每个外设都要自己写驱动 | 每个应用都要自己定义工具 |
| 标准化的是什么 | 物理接口 + 通信协议 | 工具描述格式 + 调用协议 |
| 核心角色 | USB Host / Device / Driver | MCP Host / Server / Client |
| "即插即用" | 插上U盘就能用 | 配置 MCP Server 地址就能用 |

### 1.3 MCP 与 Function Calling 的关系

MCP **不是** Function Calling 的替代品，而是它的**标准化和规模化扩展**。Function Calling 是模型和应用之间的调用约定，MCP 是应用和工具进程之间的发现与传输协议。Spring 把后者适配成前者——MCP 工具最终还是会走 Function Calling 那条链路。

| 维度 | Function Calling | MCP |
|---|---|---|
| 工具定义位置 | 写在应用代码里（`@Tool`） | 独立的 MCP Server |
| 复用性 | 每个应用重新定义 | 一次开发，到处使用 |
| 通信方式 | 进程内方法调用 | 跨进程/跨网络 |
| 适用场景 | 工具逻辑简单，只在当前应用使用 | 工具需要被多个应用复用，或独立部署 |


## 二、MCP 的 Client-Server 架构

MCP 遵循 Client-Server 架构，包含三个核心角色：

```
┌──────────────────┐        MCP协议        ┌──────────────────┐
│   MCP Host       │ <──────────────────> │   MCP Server     │
│   (AI 应用)       │   1. 发现工具列表      │   (工具服务)      │
│   - Spring应用    │   2. 调用工具          │   - 提供天气查询   │
│   - Claude       │   3. 获取结果          │   - 提供文件操作   │
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

**MCP Client**：由 Host 应用创建，与特定的 MCP Server 保持 1:1 连接。Client 负责协议解析和调用翻译。

**MCP Host**：用户交互的 AI 应用（你的 Spring Boot 应用），负责编排多个 MCP Server 的调用，并与 LLM 集成。

### 2.1 传输方式

| 传输方式 | 适用场景 | 特点 |
|---|---|---|
| **STDIO** | 本地进程通信 | 基于标准输入/输出流，无需网络协议。适用于轻量级本地工具 |
| **SSE** | Web 集成 | 基于 HTTP 的服务器发送事件，适用于远程服务访问 |
| **Streamable HTTP** | 有状态会话管理 | 支持可恢复的流式传输，适合生产环境 |


## 三、接入外部 MCP 服务：文件系统完整示例

以接入官方提供的**文件系统 MCP Server** 为例，完整演示 Spring AI 应用如何连接一个外部已有的 MCP 服务。

### 3.1 环境准备

文件系统 MCP Server 是基于 Node.js 的官方服务包，首先确保本地已安装 **Node.js (v18+)**。

### 3.2 添加依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

### 3.3 配置 MCP 连接

推荐使用**外部 JSON 文件**，可维护性更好。在 `src/main/resources/` 下创建 `mcp-servers.json`：

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

- `command`：启动命令。Windows 下通常需指定 `npx.cmd` 的完整路径
- `-y`：自动确认安装 `@modelcontextprotocol/server-filesystem` 包
- 最后一个参数：**允许 AI 访问的根目录**，必须是绝对路径且目录存在。AI 只能操作该目录及其子目录

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
            .defaultToolCallbacks(callbacks)   // 注意：必须用 defaultToolCallbacks
            .build();
}
```

> **重要**：Spring AI 正式版中，MCP 工具需要使用 `defaultToolCallbacks()` 注册，而不是 `defaultTools()`。使用后者会导致启动报错。

### 3.6 验证

```bash
# 让 AI 列出桌面文件
curl "http://localhost:8080/api/chat?conversationId=test&message=帮我看看桌面上有哪些文件？"

# 让 AI 读取指定文件内容
curl "http://localhost:8080/api/chat?conversationId=test&message=读取桌面上的 test.txt 文件，告诉我内容是什么"

# 让 AI 创建新文件
curl "http://localhost:8080/api/chat?conversationId=test&message=在桌面上创建一个名为 hello.txt 的文件，内容写 'Hello MCP'"
```


## 四、工具发现日志解读

连接成功后，MCP 调试日志会显示完整的握手与工具发现过程。

### 4.1 协议握手

```
Server response with Protocol: 2024-11-05,
Capabilities: ServerCapabilities[..., tools=ToolCapabilities[listChanged=true]],
Info: Implementation[name=secure-filesystem-server, version=0.2.0]
```

说明 MCP Client 与 Server 连接成功，Server 支持工具能力，且支持动态更新通知。

### 4.2 工具列表

```
Received Response: JSONRPCResponse[...,
result={tools=[
  {name=read_text_file, ...},
  {name=read_media_file, ...},
  {name=read_multiple_files, ...},
  {name=write_file, ...},
  {name=edit_file, ...},
  {name=create_directory, ...},
  {name=list_directory, ...},
  {name=list_directory_with_sizes, ...},
  {name=directory_tree, ...},
  {name=move_file, ...},
  {name=search_files, ...},
  {name=get_file_info, ...},
  {name=list_allowed_directories, ...}
]}]
```

文件系统 MCP Server 默认暴露 13 个工具，覆盖读取、写入、编辑、目录列表、搜索、元数据查询等操作。

### 4.3 目录权限提示

```
STDERR Message received: Client does not support MCP Roots,
using allowed directories set from server args: [ '/Users/ningpeng/Desktop' ]
```

这是 Server 的安全机制：即使 Client 不支持动态目录授权，Server 也会在启动参数中指定固定的允许目录，防止越权访问。


## 五、常见问题排查

### 5.1 ToolCallbackProvider 无法注入

**现象**：`Could not autowire. No beans of 'ToolCallbackProvider' type found.`

**原因**：`spring.ai.mcp.client.toolcallback.enabled` 未开启，MCP 工具的回调 Bean 不会被创建。

**解决方案**：

```yaml
spring:
  ai:
    mcp:
      client:
        toolcallback:
          enabled: true   # 必须显式设为 true
```

### 5.2 工具已发现但模型不调用

**现象**：日志显示工具列表已加载，但模型返回纯文本，不调用任何工具。

**排查步骤**：

**第一步：确认工具是否注册到 ChatClient**

在 `ChatClientConfig` 中打印工具数量：

```java
var callbacks = mcpTools.getToolCallbacks();
System.out.println("注册MCP工具数量: " + callbacks.length);
```

如果数量为 0，说明自动配置的 `ToolCallbackProvider` 是空的。手动定义 Bean 作为兜底：

```java
@Bean
public ToolCallbackProvider toolCallbackProvider(List<McpSyncClient> mcpSyncClients) {
    return new SyncMcpToolCallbackProvider(mcpSyncClients);
}
```

**第二步：确认请求到达应用**

在 `ChatController` 入口加日志：

```java
@GetMapping
public String chat(@RequestParam String conversationId,
                   @RequestParam String message) {
    log.info("收到请求 conversationId={}, message={}", conversationId, message);
    return chatService.chat(conversationId, message);
}
```

**第三步：打开 LLM 请求日志**

```yaml
logging:
  level:
    org.springframework.ai.chat.client: DEBUG
    org.springframework.ai.chat.model: DEBUG
    org.springframework.ai.openai: DEBUG
```

观察发送给 LLM 的 Prompt 是否包含工具定义。如果 Prompt 里没有工具，说明注册环节失败；如果 Prompt 有工具但模型仍不调用，说明模型能力问题。

**第四步：更换模型**

部分轻量模型（如 `gpt-4o-mini`、`qwen-turbo`）对工具调用的支持不稳定。临时切换到 `gpt-4o` 或 `qwen-max` 测试。

### 5.3 排查清单

| 检查项 | 预期值 | 说明 |
|---|---|---|
| `spring.ai.mcp.client.toolcallback.enabled` | `true` | 否则工具回调 Bean 不会创建 |
| 使用 `defaultToolCallbacks()` 注册 | 是 | 不能用 `defaultTools()` |
| MCP Server 启动命令 | 正确路径 | Windows 下用 `npx.cmd` 完整路径 |
| 允许访问的目录 | 绝对路径且存在 | 否则 Server 启动失败 |
| 调试日志显示工具发现 | 13+ 个工具 | 否则连接层有问题 |
| ChatClient 注册工具数量 | 与发现数一致 | 否则注册层有问题 |
| Prompt 包含工具定义 | 是 | 否则模型看不到工具 |
| 模型支持工具调用 | 是 | 换更强的模型测试 |


## 六、进阶主题

### 6.1 连接 SSE 类型的远程 MCP 服务

如果外部 MCP 服务以 SSE 方式独立部署，只需调整配置：

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            mcp-hub:
              url: http://localhost:3000
              sse-endpoint: /mcp-hub/sse/cf9ec4527e3c4a2cbb149a85ea45ab01
```

连接建立后，远端工具会自动被发现并注册，上层 `ChatClient` 的代码无需任何改动。

### 6.2 作为 MCP Server 暴露自己的能力

除了连接外部 MCP 服务，你也可以将自己的工具通过 MCP Server 暴露出去。

添加依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

使用 `@McpTool` 注解声明 MCP 工具：

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

MCP Server 支持在运行时动态添加或移除工具，并通过 `tool-change-notification` 通知客户端。客户端可以注册 `@McpToolListChanged` 处理器来响应这些变更。

### 6.4 采样（Sampling）

MCP 的采样功能允许 Server 反向请求 Client 的 LLM 生成内容，实现**双向 AI 交互**。这在需要 Server 端调用 LLM 能力但又不希望在 Server 端部署 LLM 的场景中非常有用。


## 七、核心问答

**Q：MCP 和 Function Calling 是二选一吗？**

不是。MCP 工具最终仍然走 Function Calling 的调用链路。Function Calling 是模型和应用之间的调用约定，MCP 是应用和工具进程之间的发现与传输协议。两者是叠加关系。

**Q：什么时候应该用 MCP Server，什么时候直接用 `@Tool`？**

工具逻辑简单、只在当前应用内使用，用 `@Tool` 即可。工具需要被多个应用复用、需要独立部署、或需要跨语言/跨团队提供时，封装为 MCP Server。

**Q：STDIO 和 SSE 传输怎么选？**

STDIO 适用于本地进程通信，适合命令行工具和桌面应用。SSE 和 Streamable HTTP 适用于远程服务，适合生产环境部署。

**Q：MCP Client 能同时连接多个 MCP Server 吗？**

可以。Spring AI 的 MCP Client Starter 支持同时连接多个 MCP Server，每个 Server 对应一个独立的连接。

**Q：`@McpTool` 和 `@Tool` 有什么区别？**

`@Tool` 用于本地工具（阶段三），`@McpTool` 用于 MCP Server 端暴露工具。两者 API 风格一致，但作用域不同。

**Q：为什么 MCP 工具必须用 `defaultToolCallbacks()` 而不是 `defaultTools()`？**

这是 Spring AI 正式版的 API 约定。`defaultTools()` 接受的是工具对象实例，`defaultToolCallbacks()` 接受的是 `ToolCallback` 数组。MCP 工具通过 `ToolCallbackProvider.getToolCallbacks()` 返回 `ToolCallback[]`，因此必须用后者。


## 八、阶段产出

- [ ] `mcp-servers.json` — 外部 MCP Server 连接配置
- [ ] `application-local.yml` — 启用 MCP Client 和 toolcallback
- [ ] `ChatClientConfig` — 通过 `defaultToolCallbacks` 注册 MCP 工具
- [ ] 验证 Agent 能通过 MCP 协议调用外部文件系统工具
- [ ] 验证 MCP 工具与本地工具、RAG、记忆四者协同工作
- [ ] `v0.5-mcp` Tag


## 九、下一步

阶段六引入**多 Agent 协作**，将复杂任务拆解为多个专业化 Agent 的协作。届时会引入 Spring AI Alibaba 的 Graph 工作流和 Multi-Agent 编排能力。MCP 解决了"工具标准化"，多 Agent 协作解决的是"任务分解与编排"——这是 Agent 从"单个助手"走向"团队协作"的关键一步。


**参考**：

- [Spring AI - MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)
- [Spring AI - MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [Spring AI - Getting Started with MCP](https://docs.spring.io/spring-ai/reference/guides/getting-started-mcp.html)
- [Spring AI Blog - Connect Your AI to Everything](https://spring.io/blog/2025/09/16/spring-ai-mcp-intro-blog)
- [MCP 官方服务器列表](https://github.com/modelcontextprotocol/servers)
- [MCP 官方规范](https://modelcontextprotocol.io/specification)