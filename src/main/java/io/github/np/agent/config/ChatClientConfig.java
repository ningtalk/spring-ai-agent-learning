package io.github.np.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

//    @Bean
//    public ChatClient chatClient(ChatClient.Builder builder) {
//        return builder
//                .defaultSystem("你是一个乐于助人的AI助手，用简洁清晰的中文回答问题。")
//                .build();
//    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem("你是一个乐于助人的AI助手，用简洁清晰的中文回答问题。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .build())
                .build();
    }
}
