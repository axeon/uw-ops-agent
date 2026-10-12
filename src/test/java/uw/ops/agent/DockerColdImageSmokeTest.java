package uw.ops.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.helper.DockerInfoHelper;
import uw.ops.agent.helper.ReadinessProber;
import uw.ops.agent.helper.StructuredTaskExecutor;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;
import uw.ops.agent.vo.sub.ContainerInfo;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Opt-in real Docker test; creates and removes only its uniquely named container, without a Center or database. */
@EnabledIfSystemProperty(named = "smoke.cold-image", matches = "true")
class DockerColdImageSmokeTest {
    @Test
    void coldImagePullCreateStartProbeAndStop() throws Exception {
        DockerInfoHelper.negotiateApiVersion();
        String image = System.getProperty("smoke.cold-image-ref", "traefik/whoami:v1.11.0");
        assumeFalse(DockerInfoHelper.imageExists(image), "requires an image absent from the local daemon; never deletes existing images");
        long id = System.currentTimeMillis();
        String name = "uw-ops-cold-smoke-" + id;
        OpsTask task = new OpsTask();
        task.setId(id);
        task.setContainerId(id);
        task.setPlanId(id);
        TaskData data = new TaskData();
        data.setExecType("DOCKER");
        TaskData.ContainerSection docker = new TaskData.ContainerSection();
        docker.setAction("start");
        docker.setImage(image);
        docker.setContainerName(name);
        docker.setNetworkMode("none");
        docker.setProbeTaskData("exit 0");
        data.setContainer(docker);
        task.setTaskData(data);
        try {
            StructuredTaskExecutor.execute(task, data);
            assertEquals(TaskState.SUCCEED.getValue(), task.getState());
            assertTrue(DockerInfoHelper.imageExists(image));
            assertTrue(DockerInfoHelper.containerInspect(task.getDockerId()).getState().getRunning());
            ReadinessProber.probeOnce();
            ContainerInfo summary = new ContainerInfo();
            summary.setId(task.getDockerId());
            ReadinessProber.applyResult(summary);
            assertEquals(ReadinessProber.STATE_SUCCESS, summary.getProbeState());
            System.out.println("Cold Docker pull/create/start/probe passed: " + name);
        } finally {
            docker.setAction("stop");
            docker.setTargetContainerHash(task.getDockerId());
            StructuredTaskExecutor.execute(task, data);
            assertEquals(TaskState.SUCCEED.getValue(), task.getState());
            assertThrows(DockerInfoHelper.ContainerNotFoundException.class, () -> DockerInfoHelper.containerInspect(name));
            System.out.println("Cold Docker stop/remove cleanup passed: " + name);
        }
    }
}
