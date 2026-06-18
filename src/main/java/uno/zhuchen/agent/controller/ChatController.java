package uno.zhuchen.agent.controller;

import org.springframework.web.bind.annotation.*;
import uno.zhuchen.agent.service.LLMService;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final LLMService llmService;

    public ChatController(LLMService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String reply = llmService.chat(message);
        return Map.of("content", reply);
    }
}
