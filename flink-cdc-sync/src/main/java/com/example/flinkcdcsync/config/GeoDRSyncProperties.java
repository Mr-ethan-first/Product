package com.example.flinkcdcsync.config;

import com.example.flinkcdcsync.bean.DatabaseMapping;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoDRSync 业务配置绑定（前缀 geodrsync）。
 *
 * @author 50707
 */
@Data
@Component
@ConfigurationProperties(prefix = "geodrsync")
public class GeoDRSyncProperties {

    /** 是否灾备中心模式 */
    private boolean standbyMode = true;

    /** Flink 集群相关配置（本地默认不启用） */
    private Flink flink = new Flink();

    /** 内嵌同步引擎参数 */
    private Engine engine = new Engine();

    /** 连接池上限 */
    private Connection connection = new Connection();

    /** 数据库映射列表 */
    private List<DatabaseMapping> mappings = new ArrayList<>();

    @Data
    public static class Flink {
        private boolean enabled = false;
        private int parallelism = 2;
        private long checkpointIntervalMs = 30000;
        private String savepointPath = "/data/geodrsync/savepoint";
    }

    @Data
    public static class Engine {
        private long pollIntervalMs = 2000;
        private int batchSize = 1000;
        private int maxQueueSize = 20000;
        private long reconcileIntervalMs = 10000;
        private int deviationTimeoutSec = 300;
    }

    @Data
    public static class Connection {
        private int maxPoolSize = 32;
        private int maxGlobalConnections = 200;
    }
}
