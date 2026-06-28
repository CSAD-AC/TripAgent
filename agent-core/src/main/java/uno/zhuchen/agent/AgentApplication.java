package uno.zhuchen.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 启动类
 *
 * 包扫描范围: uno.zhuchen.agent.* (当前包及子包) + uno.zhuchen.workflow.*
 * 之所以需要显式声明 workflow 包,是因为 Graph 工作流模块在 uno.zhuchen.workflow
 * 下,不在 agent 包内,默认扫描不到。
 */
@SpringBootApplication
@ComponentScan({"uno.zhuchen.agent", "uno.zhuchen.workflow"})
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
