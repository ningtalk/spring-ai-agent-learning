package io.github.np.agent.controller;

import io.github.np.agent.service.ChatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping
    public String chat(@RequestParam String message) {
        return chatService.chat(message);
    }

    @GetMapping
    public String chatWithMemory(
            @RequestParam String conversationId,
            @RequestParam String message) {
        return chatService.chatWithMem(conversationId, message);
    }
}