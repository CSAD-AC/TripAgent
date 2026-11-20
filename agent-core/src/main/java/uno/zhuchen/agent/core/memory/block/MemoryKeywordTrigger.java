package uno.zhuchen.agent.core.memory.block;

import java.util.regex.Pattern;

public final class MemoryKeywordTrigger {

    private static final Pattern KEYWORD =
            Pattern.compile("(记住|记一下|帮我记住|记下来|请记住)");

    private MemoryKeywordTrigger() {
    }

    public static boolean matches(String message) {
        return message != null && KEYWORD.matcher(message).find();
    }
}
