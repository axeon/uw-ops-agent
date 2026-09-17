package uw.ops.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Properties;

/**
 * Properties工具类。
 * 主要为了解决字符串映射到Properties的问题。
 */
public class PropertyUtils {

    private static final Logger log = LoggerFactory.getLogger(PropertyUtils.class);

    /**
     * 从字符串中加载Properties。
     *
     * @param data 键值对文本（每行 key=value）
     * @return 解析结果（data 为 null/空时返回空 Properties；解析异常吞掉返回已解析部分）
     */
    public static Properties loadFromString(String data) {
        Properties properties = new Properties();
        if (data == null || data.isEmpty()) {
            return properties;
        }
        try {
            properties.load(new StringReader(data));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return properties;
    }

    /**
     * 把Properties保存到字符串中（每行 key=value）。
     *
     * @param properties 键值对集合
     * @return 键值对文本
     */
    public static String storeToString(Properties properties) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Object, Object> kv : properties.entrySet()) {
            sb.append(kv.getKey()).append("=").append(kv.getValue()).append("\n");
        }
        return sb.toString();
    }
}
