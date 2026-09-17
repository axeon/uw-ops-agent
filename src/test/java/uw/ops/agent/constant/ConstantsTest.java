package uw.ops.agent.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constants 产品标识注入单测：Maven filtering 后 project-info.properties 应携带 pom 的
 * 名称与版本（IDE 直跑未过滤资源时走兜底值，此处验证过滤链路正常）。
 */
class ConstantsTest {

    @Test
    void agentInfoInjectedFromPom() {
        String agentInfo = Constants.getAgentInfo();
        assertTrue(agentInfo.startsWith("uw-ops-agent:"), "agentInfo=" + agentInfo);
        //非兜底：版本已注入且非 ${} 原文。
        assertFalse(agentInfo.endsWith(":unknown"), "project-info.properties not on classpath or not filtered");
        assertFalse(agentInfo.contains("${"), "project-info.properties is raw (filtering missing)");
    }
}
