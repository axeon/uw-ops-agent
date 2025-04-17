package uw.ops.agent.api;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.common.dto.ResponseData;
import uw.common.util.JsonUtils;
import uw.httpclient.http.HttpConfig;
import uw.httpclient.http.HttpInterface;
import uw.httpclient.json.JsonInterfaceHelper;
import uw.ops.agent.vo.HostInfo;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.OpsTask;

import java.util.HashMap;
import java.util.List;

/**
 * opsAgent和opsCenter交互的类。
 */
public class OpsAgentApi {

    private static final Logger log = LoggerFactory.getLogger( OpsAgentApi.class );
    private static final HttpInterface agentClient =
            new JsonInterfaceHelper( HttpConfig.builder().connectTimeout( 30000 ).readTimeout( 30000 ).writeTimeout( 30000 ).retryOnConnectionFailure( true ).build() );
    /**
     * 主机hash,唯一标识符。
     */
    private static String hostHash;

    /**
     * 上传主机设备信息。
     */
    public static void uploadHostInfo(HostInfo hostInfo) throws Exception {
        hostHash = hostInfo.getHostHash();
        agentClient.postBodyForData( getOpsCenterHost() + "/agent/ops/uploadHostInfo", hostInfo );
    }

    /**
     * 上传主机统计信息。
     */
    public static void uploadHostStats(HostStats hostStats) throws Exception {
        agentClient.postBodyForData( getOpsCenterHost() + "/agent/ops/uploadHostStats", hostStats );
    }

    /**
     * 拉取任务执行脚本。
     *
     * @return
     */
    public static ResponseData<List<OpsTask>> getTaskList() throws Exception {
        return agentClient.getForEntity( getOpsCenterHost() + "/agent/ops/getTaskList", new TypeReference<ResponseData<List<OpsTask>>>() {
        }, new HashMap<>() {{
            put( "hostHash", hostHash );
        }} ).getValue();
    }

    /**
     * 上传ops任务。
     *
     * @param opsTask
     */
    public static void reportTaskResult(OpsTask opsTask) throws Exception {
        opsTask.setHostHash( hostHash );
        log.info( "ReportTaskResult: {}", JsonUtils.toString( opsTask ) );
        agentClient.postBodyForData( getOpsCenterHost() + "/agent/ops/reportTaskResult", opsTask );
    }

    /**
     * 获取opsCenter主机地址。
     *
     * @return
     */
    private static String getOpsCenterHost() {
        String opsCenterHost = System.getenv( "OPS_CENTER_HOST" );
        if (StringUtils.isBlank( opsCenterHost )) {
            opsCenterHost = "http://127.0.0.1:1000";
        }
        return opsCenterHost;
    }

}
