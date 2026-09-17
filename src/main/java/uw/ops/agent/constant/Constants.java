package uw.ops.agent.constant;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Agent 常量与配置收口：环境变量名与默认值、构建期注入的产品标识，
 * 对外只提供取值方法（Agent 支持的全部环境配置集中在此）。
 */
public final class Constants {

    /**
     * Center 服务地址环境变量。
     */
    private static final String ENV_OPS_CENTER_HOST = "OPS_CENTER_HOST";

    /**
     * Center 服务地址默认值（环境变量未设置时）。
     */
    private static final String DEFAULT_OPS_CENTER_HOST = "http://127.0.0.1:1000";

    /**
     * Docker UDS socket 路径环境变量（非默认路径部署时覆盖，如 /run/docker.sock、rootless 场景）。
     */
    private static final String ENV_OPS_DOCKER_SOCK = "OPS_DOCKER_SOCK";

    /**
     * Docker UDS socket 默认路径。
     */
    private static final String DEFAULT_DOCKER_SOCKET_PATH = "/var/run/docker.sock";

    /**
     * Agent 密钥文件路径环境变量（便于测试覆盖）。
     */
    private static final String ENV_OPS_AGENT_SECRET_FILE = "OPS_AGENT_SECRET_FILE";

    /**
     * Agent 密钥文件默认路径。
     */
    private static final String DEFAULT_AGENT_SECRET_PATH = "/etc/uw-ops-agent/agent.secret";

    /**
     * 产品标识（名称:版本，logger 名/日志前缀）：由 pom.xml 的 project.name/project.version
     * 经 Maven resource filtering 注入 project-info.properties，类加载时读取一次
     * （改名称或版本只动 pom）。native 镜像经 -H:IncludeResources 打包该资源，
     * 发布前由 --verify-native-metadata 断言可读。
     */
    private static final String AGENT_INFO = loadAgentInfo();

    private Constants() {
    }

    /**
     * 获取产品标识（名称:版本，如 "uw-ops-agent:1.2.0"）。
     *
     * @return 产品标识
     */
    public static String getAgentInfo() {
        return AGENT_INFO;
    }

    /**
     * 加载产品标识：project-info.properties 只读一次，name/version 缺失或未过滤（${} 原文，
     * IDE 直接运行）时走兜底值；读取失败不阻断启动。
     */
    private static String loadAgentInfo() {
        Properties properties = new Properties();
        try (InputStream in = Constants.class.getClassLoader().getResourceAsStream("project-info.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            //读取失败走兜底值，不阻断启动。
        }
        String name = injectedValue(properties, "project.name", "uw-ops-agent");
        String version = injectedValue(properties, "project.version", "unknown");
        return name + ":" + version;
    }

    /**
     * 取注入值：${...} 原文 = 资源未经 Maven 过滤，视为未注入走兜底。
     */
    private static String injectedValue(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        if (value != null && !value.isEmpty() && !value.startsWith("${")) {
            return value.trim();
        }
        return fallback;
    }

    /**
     * 获取 Center 服务地址：优先环境变量 OPS_CENTER_HOST，未设置用默认值；
     * 值统一 trim 并剥尾部斜杠（拼路径形态为 host + "/agent/..."，尾斜杠会拼出双斜杠）。
     *
     * @return Center 服务地址
     */
    public static String getOpsCenterHost() {
        String host = System.getenv(ENV_OPS_CENTER_HOST);
        if (StringUtils.isBlank(host)) {
            return DEFAULT_OPS_CENTER_HOST;
        }
        host = host.trim();
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    /**
     * 获取 Docker UDS socket 路径：优先环境变量 OPS_DOCKER_SOCK，未设置用默认路径。
     *
     * @return Docker socket 路径
     */
    public static String getOpsDockerSock() {
        String path = System.getenv(ENV_OPS_DOCKER_SOCK);
        return StringUtils.isBlank(path) ? DEFAULT_DOCKER_SOCKET_PATH : path.trim();
    }

    /**
     * 获取 Agent 密钥文件路径：优先环境变量 OPS_AGENT_SECRET_FILE（便于测试），未设置用默认路径。
     *
     * @return 密钥文件路径
     */
    public static String getOpsAgentSecretFile() {
        String path = System.getenv(ENV_OPS_AGENT_SECRET_FILE);
        return StringUtils.isBlank(path) ? DEFAULT_AGENT_SECRET_PATH : path.trim();
    }

}
