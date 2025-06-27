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
     * @param data
     * @return
     */
    public static Properties loadFromString(String data) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(data));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return properties;
    }

    /**
     * 把Properties保存到字符串中。
     *
     * @param properties
     * @return
     */
    public static String storeToString(Properties properties) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Object, Object> kv : properties.entrySet()) {
            sb.append(kv.getKey()).append("=").append(kv.getValue()).append("\n");
        }
        return sb.toString();
    }

    public static void main(String[] args) throws IOException {
        String data = "# test\na=b\nc=d";
        Properties properties = loadFromString(data);
        properties.put("x", "z");
        data = storeToString(properties);
        System.out.println(data);
    }
}
