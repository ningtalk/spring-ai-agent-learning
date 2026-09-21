# 03 - 工具调用（Tool Calling）

## 阶段目标

让 Agent 具备调用外部工具的能力。理解 Tool Calling 的完整链路：模型如何决定调用工具、应用如何执行工具、结果如何返回给模型。

完成后你应该能够回答：

- 为什么 LLM 需要工具调用？它的本质是什么？
- `@Tool` 和 `@ToolParam` 注解如何协作生成 JSON Schema？
- 工具调用的 ReAct 循环是如何运转的？

---

## 一、核心认知：LLM 的能力边界

LLM 存在两个关键限制：

- **无法访问实时信息**：当前时间、天气、股价、最新新闻
- **无法直接执行外部操作**：发送邮件、操作数据库、调用 API

工具调用（Tool Calling）正是为解决这两个问题而设计的。它的本质是：

> 模型不直接执行工具，而是在响应中**表达调用特定工具的意图**（工具名 + 参数），应用程序执行工具后，将结果返回给模型，模型基于结果生成最终回答。

### 完整的工具调用链路

```
1. 请求中携带工具定义（名称、描述、输入Schema）
2. LLM 决定调用工具 → 返回工具名 + 参数（JSON）
3. 应用程序执行工具
4. 工具结果返回给 LLM
5. LLM 基于工具结果生成最终回答
```

这个循环可能重复多次——模型可以连续调用多个工具，直到收集到足够信息才输出最终回答。

---

## 二、定义工具：三种方式

Spring AI 1.0.x 支持三种工具定义方式，**注解式是最推荐、最常用的方式**。

| 方式 | 适用场景 | 复杂度 |
|---|---|---|
| `@Tool` 注解 | 自己编写的类，最常用 | ⭐ |
| `MethodToolCallback` | 已有类的方法，不想改源码 | ⭐⭐ |
| `FunctionToolCallback` | 基于函数式接口 | ⭐⭐ |

### 2.1 注解式（推荐）

在普通 Java 方法上加 `@Tool` 注解即可：

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class DateTimeTools {

    @Tool(description = "获取用户所在时区的当前日期和时间")
    public String getCurrentDateTime() {
        return LocalDateTime.now()
                .atZone(LocaleContextHolder.getTimeZone().toZoneId())
                .toString();
    }
}
```

带参数的工具：

```java
public class WeatherTools {

    @Tool(description = "获取指定城市的天气信息")
    public String getWeather(
            @ToolParam(description = "城市名称，例如：北京") String city) {
        // 调用天气API...
        return city + "：晴天，25°C";
    }
}
```

关键点：

- **`@Tool(description = "...")`**：告诉模型这个工具**做什么、何时用**。描述质量直接影响模型选择工具的准确性。
- **`@ToolParam(description = "...")`**：描述参数含义。Spring AI 自动生成 JSON Schema，`@ToolParam` 补充每个参数的描述和可选/必填提示。被 `@Nullable` 标注的参数默认视为可选。

### 2.2 工具方法不支持的参数和返回类型

方法工具**不支持**以下类型：

- `Optional`
- 异步类型：`CompletableFuture`、`Future`
- 响应式类型：`Mono`、`Flux`
- 函数类型：`Function`、`Supplier`、`Consumer`

---

## 三、注册工具到 ChatClient

### 3.1 全局注册（defaultTools）

在 `ChatClientConfig` 中通过 `defaultTools` 注册，所有请求自动可用：

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder,
                             ChatMemory chatMemory,
                             WeatherTools weatherTools,
                             DateTimeTools dateTimeTools) {
    return builder
            .defaultSystem("你是一个乐于助人的AI助手。")
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build()
            )
            .defaultTools(weatherTools, dateTimeTools)
            .build();
}
```

> **注意**：`defaultToolCallbacks()` 已废弃，请统一使用 `defaultTools()`。

### 3.2 运行时注册（tools）

只在某次请求中使用特定工具：

```java
String response = chatClient.prompt()
        .user("北京今天天气怎么样？")
        .tools(new WeatherTools())
        .call()
        .content();
```

**区别**：`defaultTools` 是全局默认，`tools` 是单次请求追加。生产环境建议优先用 `defaultTools`，避免每次调用重复注册。

### 3.3 Service 层改造

`ChatService` 的调用方式**几乎不需要改变**——如果工具已在 `defaultTools` 中注册：

```java
public String chat(String conversationId, String message) {
    return chatClient.prompt()
            .user(message)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
}
```

这正是 Spring AI 模块化设计的价值：**工具调用与记忆可以叠加使用，互不干扰**。

---

## 四、工具调用的内部机制

### 4.1 ToolCallback 接口

每个工具在底层都会被包装为 `ToolCallback` 实例。核心方法 `call` 负责：将模型输出的 JSON 字符串转为方法参数 → 执行工具方法 → 将结果转为 JSON 字符串返回。

### 4.2 ToolCallingManager

`DefaultToolCallingManager` 负责管理整个工具调用过程：解析工具定义、执行工具调用、构建工具上下文（维护历史 Message 记录）。

### 4.3 工具结果转换

工具返回值默认通过 `DefaultToolCallResultConverter` 转换为 JSON 字符串后回传给模型。

可以在 `@Tool` 上指定自定义转换器：

```java
@Tool(description = "...", resultConverter = MyConverter.class)
```

---

## 五、实战：实现两个实用工具

### 5.1 时间工具

```java
package io.github.<github-username>.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import java.time.LocalDateTime;

public class DateTimeTools {

    @Tool(description = "获取用户所在时区的当前日期和时间")
    public String getCurrentDateTime() {
        return LocalDateTime.now()
                .atZone(LocaleContextHolder.getTimeZone().toZoneId())
                .toString();
    }
}
```

### 5.2 天气工具（模拟）

```java
package io.github.<github-username>.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class WeatherTools {

    @Tool(description = "获取指定城市的当前天气信息，包括温度和天气状况")
    public String getWeather(
            @ToolParam(description = "城市名称，例如：北京、上海") String city) {
        // 实际项目可对接天气API
        return switch (city) {
            case "北京" -> "北京：晴，28°C，湿度40%";
            case "上海" -> "上海：多云，26°C，湿度65%";
            default -> city + "：阴，24°C";
        };
    }
}
```

### 5.3 注册到 ChatClient

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder,
                             ChatMemory chatMemory) {
    return builder
            .defaultSystem("你是一个乐于助人的AI助手。")
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build()
            )
            .defaultTools(new DateTimeTools(), new WeatherTools())
            .build();
}
```

### 5.4 验证

```bash
# 时间工具
curl "http://localhost:8080/api/chat?conversationId=test&message=现在几点了？"

# 天气工具
curl "http://localhost:8080/api/chat?conversationId=test&message=北京今天天气怎么样？"

# 多工具协作
curl "http://localhost:8080/api/chat?conversationId=test&message=现在几点了？北京天气如何？"

# 不需要工具（模型应直接回答）
curl "http://localhost:8080/api/chat?conversationId=test&message=用一句话解释什么是Java"
```

模型应能**自主判断**何时调用工具、调用哪个工具。

---

## 六、高级主题

### 6.1 returnDirect：直接返回模式

默认情况下，工具结果会返回给模型，由模型润色后输出。设置 `returnDirect = true` 可跳过大模型，直接返回工具结果：

```java
@Tool(description = "...", returnDirect = true)
public String queryDatabase(@ToolParam(description = "...") String sql) {
    return jdbcTemplate.queryForList(sql).toString();
}
```

适用于数据查询等不需要模型二次处理的场景。

### 6.2 ToolContext：传递额外上下文

`ToolContext` 可以让程序向工具传递额外参数，例如用户身份信息：

```java
@Tool(description = "查询当前用户的信息")
public String getUserInfo(ToolContext context) {
    String userId = (String) context.getContext().get("userId");
    return userService.findById(userId).toString();
}
```

调用时传入：

```java
chatClient.prompt()
    .user("查询我的信息")
    .toolContext(Map.of("userId", "12345"))
    .call()
    .content();
```

### 6.3 动态工具发现（Tool Search Tool）

当工具数量超过 30 个时，将所有工具定义都塞进 Prompt 会导致 Token 浪费和模型选择困难。Anthropic 提出的 **Tool Search Tool** 模式实现了 34%-64% 的 Token 节省。

核心思路：初始只发送一个搜索工具的定义，模型按需搜索并动态加载相关工具。Spring AI 通过 **Recursive Advisor** 实现了这一模式，适用于 OpenAI、Anthropic、Gemini 等所有模型。

---

## 七、核心问答

**Q：Tool Calling 和 Function Calling 是一回事吗？**

本质上是一回事。Spring AI 1.0.x 已统一使用 "Tool Calling" 术语，旧的 `FunctionCallback` API 已废弃。

**Q：模型如何知道该调用哪个工具？**

模型根据工具的 `description` 和参数的 `description` 来判断。描述写得越清晰，模型选择越准确。如果描述模糊，模型可能该调用时不调用，或调用错误的工具。

**Q：工具调用可以和多轮对话记忆一起用吗？**

可以，而且这是常见组合。`MessageChatMemoryAdvisor` 和工具调用完全独立，都通过 Advisor 链和 Builder 注册，互不干扰。

**Q：如果工具执行抛异常怎么办？**

异常会被 Spring AI 捕获，并将错误信息作为工具结果返回给模型，模型会基于错误信息决定下一步行动（如重试或告知用户）。

**Q：`defaultTools` 和 `tools` 同时使用会冲突吗？**

不会。`defaultTools` 注册全局工具，`tools` 在单次请求中追加额外工具，两者会合并生效。

**Q：工具方法可以直接返回对象吗？**

可以。Spring AI 默认用 Jackson 将返回对象序列化为 JSON 字符串再回传给模型。

---

## 八、阶段产出

- [ ] `DateTimeTools` — 获取当前时间
- [ ] `WeatherTools` — 模拟天气查询
- [ ] `ChatClientConfig` — 通过 `defaultTools` 注册工具
- [ ] 验证模型能自主判断是否需要调用工具
- [ ] 验证工具调用与多轮记忆协同工作
- [ ] `v0.3-tools` Tag

---

## 九、下一步

阶段四引入 **RAG（检索增强生成）** ，让 Agent 能够基于私有知识库回答问题。

届时 `ChatClient` 配置会增加 `QuestionAnswerAdvisor`，而工具调用仍然可用。RAG 解决的是"模型不知道你的私有数据"的问题，工具调用解决的是"模型无法执行操作"的问题，两者互补。

---

## 参考

- [Spring AI - Tool Calling 官方文档](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI 1.0.0 M6 发布说明](https://springframework.org.cn/blog/2025/02/14/spring-ai-1-0-0-m6-released/)
- [Spring AI 源码解析：Tool Calling 链路](https://java2ai.com/blog/spring-ai-toolcalling/)
- [Tool Calling in Spring AI 2.0](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling)