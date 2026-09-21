package io.github.np.agent.config;

import io.github.np.agent.tool.DateTimeTools;
import io.github.np.agent.tool.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
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

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ChatMemory chatMemory,
                                 VectorStore vectorStore,
                                 ToolCallbackProvider mcpTools) {

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

        // 1. 构建一个带有检索参数的 SearchRequest
        SearchRequest searchRequest = SearchRequest.builder()
                .similarityThreshold(0.0)  // 相似度阈值
                .topK(6)                     // 返回前6个结果
                .build();

        // 2. 将 SearchRequest 传入 QuestionAnswerAdvisor
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .promptTemplate(customTemplate)   // 设置自定义模板
                .order(10)
                .build();

        return builder
                .defaultSystem("你是一个乐于助人的AI助手。")
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        qaAdvisor  // 挂载配置好的 RAG Advisor
                )
                .defaultToolCallbacks(mcpTools.getToolCallbacks())
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
