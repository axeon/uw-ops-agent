package uw.ops.agent.helper;

import org.junit.jupiter.api.Test;
import uw.ops.agent.vo.TaskData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 容器路径 → 宿主源 bind 反查锚定（路径边界/最长前缀/无匹配 fail-closed）。
 */
class ResolveHostPathTest {

    private static TaskData.ContainerSection docker(String... mounts) throws Exception {
        TaskData.ContainerSection docker = new TaskData.ContainerSection();
        docker.setMounts(List.of(mounts));
        return docker;
    }

    @Test
    void resolvesInsideMountPoint() throws Exception {
        assertEquals("/home/mysql-3306/conf/uw-tuning.cnf",
                StructuredTaskExecutor.resolveHostPath(
                        docker("/home/mysql-3306/conf:/etc/mysql/conf.d", "/home/mysql-3306/data:/var/lib/mysql"),
                        "/etc/mysql/conf.d/uw-tuning.cnf"));
    }

    @Test
    void longestMountPointWinsOnNesting() throws Exception {
        assertEquals("/host/sub/x/cookie",
                StructuredTaskExecutor.resolveHostPath(
                        docker("/host/all:/data", "/host/sub:/data/sub"),
                        "/data/sub/x/cookie"));
    }

    @Test
    void boundaryMatchPreventsSegmentMisfit() {
        // /data/sub 不能匹配路径 /data/sub.bak/x（段边界）
        assertThrows(StructuredTaskExecutor.TaskExecutionException.class,
                () -> StructuredTaskExecutor.resolveHostPath(docker("/host/sub:/data/sub"), "/data/sub.bak/x"));
    }

    @Test
    void noMountMatchFails() {
        assertThrows(StructuredTaskExecutor.TaskExecutionException.class,
                () -> StructuredTaskExecutor.resolveHostPath(docker("/h:/etc/mysql/conf.d"), "/etc/other/cnf"));
        assertThrows(StructuredTaskExecutor.TaskExecutionException.class,
                () -> StructuredTaskExecutor.resolveHostPath(docker("/h:/etc"), null));
    }

    @Test
    void bindWithOptionsSegmentTolerated() throws Exception {
        // bind 串带 ro 选项段（hostSrc:dst:opts）
        assertEquals("/host/conf/a.cnf",
                StructuredTaskExecutor.resolveHostPath(docker("/host/conf:/etc/conf:ro"), "/etc/conf/a.cnf"));
    }
}
