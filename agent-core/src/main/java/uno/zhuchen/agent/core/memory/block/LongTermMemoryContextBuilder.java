package uno.zhuchen.agent.core.memory.block;

import org.springframework.stereotype.Component;

@Component
public class LongTermMemoryContextBuilder {

    private final MemoryBlockService blockService;

    public LongTermMemoryContextBuilder(MemoryBlockService blockService) {
        this.blockService = blockService;
    }

    public String render(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String userPrompt = blockService.userPrompt(userId);
        String profile = blockService.content(userId, MemoryBlockType.PROFILE);
        String events = blockService.content(userId, MemoryBlockType.EVENT);
        if (isBlank(userPrompt) && isBlank(profile) && isBlank(events)) {
            return null;
        }

        StringBuilder builder = new StringBuilder("[长期记忆]\n")
                .append("\n");
        if (!isBlank(userPrompt)) {
            builder.append("[用户固定提示词｜最高优先级且模型不可修改]\n")
                    .append(userPrompt.trim())
                    .append("\n\n");
        }
        if (!isBlank(profile) || !isBlank(events)) {
            builder.append("[自动记忆｜模型可更新，用户可编辑]\n");
            if (!isBlank(profile)) {
                builder.append("用户信息：\n")
                        .append(profile.trim())
                        .append("\n\n");
            }
            if (!isBlank(events)) {
                builder.append("事件记忆：\n")
                        .append(events.trim())
                        .append("\n");
            }
        }
        return builder.toString().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
