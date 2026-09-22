package io.github.np.agent.config;

import io.github.np.agent.tool.DateTimeTools;
import io.github.np.agent.tool.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class ChatClientConfig {

//    @Bean
//    public ChatClient chatClient(ChatClient.Builder builder) {
//        return builder
//                .defaultSystem("你是一个乐于助人的AI助手，用简洁清晰的中文回答问题。")
//                .build();
//    }

//    @Bean
//    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
//        return builder
//                .defaultSystem("你是一个乐于助人的AI助手，用简洁清晰的中文回答问题。")
//                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory)
//                        .build())
//                .defaultTools(new DateTimeTools(), new WeatherTools())
//                .build();
//    }


    /**
     * RAG
     * @param builder
     * @param chatMemory
     * @param vectorStore
     * @return
     */
//    @Bean
//    public ChatClient chatClient(ChatClient.Builder builder,
//                                 ChatMemory chatMemory,
//                                 VectorStore vectorStore) {
//
//        PromptTemplate customTemplate = PromptTemplate.builder()
//                .renderer(StTemplateRenderer.builder()
//                        .startDelimiterToken('<')
//                        .endDelimiterToken('>')
//                        .build())
//                .template("""
//                上下文信息如下。
//                <question_answer_context>
//                根据上下文信息且没有先验知识，回答查询。
//                遵循以下规则：
//                1. 如果答案不在上下文中，只需说你不知道。
//                2. 不要编造答案。
//                """)
//                .build();
//
//        // 1. 构建一个带有检索参数的 SearchRequest
//        SearchRequest searchRequest = SearchRequest.builder()
//                .similarityThreshold(0.0)  // 相似度阈值
//                .topK(6)                     // 返回前6个结果
//                .build();
//
//        // 2. 将 SearchRequest 传入 QuestionAnswerAdvisor
//        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
//                .searchRequest(searchRequest)
//                .promptTemplate(customTemplate)   // 设置自定义模板
//                .order(10)
//                .build();
//
//        return builder
//                .defaultSystem("你是一个乐于助人的AI助手。")
//                .defaultAdvisors(
//                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
//                        qaAdvisor  // 挂载配置好的 RAG Advisor
//                )
//                .build();
//    }


    /**
     * 注意rag和mcp tools都要生效时，提示词不能向上面一样限制<根据上下文信息且没有先验知识，回答查询>
     *     提示词不对会导致llm不调用工具
     * @param builder
     * @param chatMemory
     * @param vectorStore
     * @param mcpTools
     * @return
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ChatMemory chatMemory,
                                 VectorStore vectorStore,
                                 ToolCallbackProvider mcpTools) {
        var callbacks = mcpTools.getToolCallbacks();
        System.out.println("【ChatClient配置】注册MCP工具数量: " + callbacks.length);

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

        return builder
                .defaultSystem("""
            你是一个智能助手，拥有两种信息来源：
            
            1. **上下文信息 (Context)**：从知识库中检索到的背景资料。
               - 适用于：回答关于已录入文档、政策、通用知识的问题。
            
            2. **工具 (Tools)**：可以调用的外部功能。
               - 适用于：获取实时数据（如当前时间）、查询外部系统、操作文件系统（如列出桌面文件）。
            
            **决策原则**：
            - 如果用户的问题涉及实时信息或需要执行操作，**必须调用工具**。
            - 如果问题可以从上下文信息中找到答案，则直接基于上下文回答。
            - 如果两者都不足以回答，请如实告知用户。
            """)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .promptTemplate(customTemplate) // 使用调整后的模板
                                .order(10)
                                .build()
                )
                .defaultTools(new DateTimeTools())
                .defaultToolCallbacks(callbacks)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Bean
    public CommandLineRunner checkTools(ToolCallbackProvider provider) {
        return args -> {
            Arrays.stream(provider.getToolCallbacks())
                    .forEach(t -> System.out.println("MCP工具: " + t.getToolDefinition().name()));
        };
    }
}
