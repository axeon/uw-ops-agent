package uw.ops.agent.constant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constants 单测：产品标识注入（Maven filtering 后 project-info.properties 应携带 pom 的
 * 名称与版本，IDE 直跑未过滤资源时走兜底值）与任务并发度配置解析
 * （property 优先于环境变量，非法/非正值回退默认 4）。
 */
class ConstantsTest {

    @AfterEach
    void tearDown() {
        //还原测试期临时 property，避免影响其它用例（同 JVM 共享）。
        System.clearProperty("uw.ops.agent.max-concurrent-tasks");
    }

    @Test
    void agentInfoInjectedFromPom() {
        String agentInfo = Constants.getAgentInfo();
        assertTrue(agentInfo.startsWith("uw-ops-agent:"), "agentInfo=" + agentInfo);
        //非兜底：版本已注入且非 ${} 原文。
        assertFalse(agentInfo.endsWith(":unknown"), "project-info.properties not on classpath or not filtered");
        assertFalse(agentInfo.contains("${"), "project-info.properties is raw (filtering missing)");
    }

    @Test
    void maxConcurrentTasksDefaultsAndPropertyOverride() {
        //未配置（测试环境无该 property/环境变量）→ 默认 4。
        System.clearProperty("uw.ops.agent.max-concurrent-tasks");
        assertEquals(4, Constants.getMaxConcurrentTasks(), "default concurrency must be 4");
        //System property 覆盖（优先于环境变量）。
        System.setProperty("uw.ops.agent.max-concurrent-tasks", "7");
        assertEquals(7, Constants.getMaxConcurrentTasks());
        //非法/非正值回退默认。
        System.setProperty("uw.ops.agent.max-concurrent-tasks", "0");
        assertEquals(4, Constants.getMaxConcurrentTasks());
        System.setProperty("uw.ops.agent.max-concurrent-tasks", "-3");
        assertEquals(4, Constants.getMaxConcurrentTasks());
        System.setProperty("uw.ops.agent.max-concurrent-tasks", "abc");
        assertEquals(4, Constants.getMaxConcurrentTasks());
        System.setProperty("uw.ops.agent.max-concurrent-tasks", " ");
        assertEquals(4, Constants.getMaxConcurrentTasks());
    }
}
