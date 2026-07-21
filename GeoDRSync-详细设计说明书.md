# GeoDRSync 地理数据库异地灾备同步服务 — 详细设计说明书

---

## 文档标识

| 项 | 内容 |
|---|------|
| 项目名称 | GeoDRSync (flink-cdc-sync) |
| 文档名称 | 详细设计说明书 |
| 版本 | v3.0 |
| 状态 | 终稿（基于源码反向梳理 + 全模块覆盖） |
| 对应源码 | `flink-cdc-sync/src/main/java/com/example/flinkcdcsync/**` |
| 技术栈 | Spring Boot 3.4.3 + MyBatis-Plus 3.5.9 + React 18 UMD |
| 最后更新 | 2026-07-20 |

---

## 修订记录

| 版本 | 日期 | 修订内容 | 修订人 |
|------|------|----------|--------|
| v1.0 | 2026-07-18 | 初稿（基础架构、接口清单） | AI |
| v2.0 | 2026-07-19 | 补充全量后端细节、前端组件树、Mermaid 图、异常场景、FAQ | AI |
| v3.0 | 2026-07-20 | 基于源码逐文件反向梳理：补全 SyncHostJob 流式分页/大表优化、TableSinkFunction enqueue 超时/死信上限、DynamicShardedConnectionManager 按主机清理连接池、DatabaseSyncManager 配置热生效(update+reload)与用户隔离、OperationLogAspect 密码脱敏、install.sh 一键部署、systemd+Nginx、188 测试覆盖矩阵 | AI |

---

## 目录

- [第一章 引言](#第一章-引言)
- [第二章 总体设计](#第二章-总体设计)
- [第三章 数据库设计](#第三章-数据库设计)
- [第四章 核心模块详细设计](#第四章-核心模块详细设计)
- [第五章 API 接口设计](#第五章-api-接口设计)
- [第六章 前端设计](#第六章-前端设计)
- [第七章 部署设计](#第七章-部署设计)
- [第八章 异常处理与容错](#第八章-异常处理与容错)
- [第九章 测试策略](#第九章-测试策略)
- [附录](#附录)

---

## 第一章 引言

### 1.1 编写目的

本文档是 **GeoDRSync** 项目的全套技术设计说明书，目标读者为后续接手该项目的开发人员、运维人员和测试人员。文档力求做到"仅凭本文档 + 源码即可独立维护系统"，涵盖：

- 后端全部 Controller / Service / Manager / Sync 引擎 / Sink 管线的设计与实现逻辑
- 同步引擎的核心算法：全量流式分页扫描、增量水位拉取、DDL 检测(ALTER/DROP)、删除对账、大表优化
- 数据写入管线：表级队列、批量 upsert、WAL 重试、死信队列、enqueue 超时机制
- 连接池管理：按库分片 HikariCP、全局信号量限流、按主机清理防泄漏
- 配置热生效：update + reload 双步流程，无需重启服务
- 用户隔离与审计：Session 认证、多用户配置隔离、AOP 操作日志
- 数据库 ER 设计、表结构 DDL
- 前端 SPA 架构、组件结构、状态管理
- Linux 一键部署（install.sh 交互式配置、systemd 服务、Nginx 反向代理）
- 异常处理与容错（单库失败不中断、enqueue 超时、断电续传、断网续传）
- 测试策略与 188 个自动化测试覆盖矩阵

### 1.2 适用范围

本文档覆盖 `flink-cdc-sync/flink-cdc-sync/` 模块的全部代码（Java 后端 + React 前端 + MySQL 元数据库 + Linux 部署脚本），不涉及 Flink 集群部署（预留 `geodrsync.flink.enabled=true` 开关但当前版本未启用，使用自研内嵌同步引擎）。

### 1.3 遵循规范

- Java 17 源码编译（`pom.xml` 中 `maven.compiler.source=17`），JDK 21 运行兼容
- Spring Boot 3.4.3 标准 MVC 分层架构
- MyBatis-Plus 3.5.9 ORM 规范
- React 18 UMD 单文件 SPA，JSX 构建期预编译（无 Node 构建链）
- RESTful API 设计，JSON 统一响应格式 `{code, message, success, traceId, data}`
- MySQL 8.0（源）→ MySQL 5.7（目标）跨大版本 DDL 兼容（自动排序规则降级）
- HttpSession + Cookie 鉴权（服务端 Session，前端不接触 Token）

### 1.4 术语表

| 术语 | 英文 | 说明 |
|------|------|------|
| 生产中心 | Production Center | 数据生产端（源主机） |
| 灾备中心 | Disaster Recovery Center | 数据备份端（目标主机） |
| 主机对 | Host Pair | 一组"源主机↔目标主机"配置，instanceKey = `sourceHost->targetHost` |
| 同步作业 | SyncHostJob | 针对单个主机对的同步执行单元，内含 ScheduledExecutorService 定时调度 |
| 全量同步 | Full Sync | 首次将源库完整数据复制到目标库（流式分页扫描） |
| 增量同步 | Incremental Sync | 仅同步自上次扫描后变更的数据（基于 update_time 水位） |
| 水位 | Watermark | 表级 update_time 最大值，用于增量拉取的起始点 |
| 删除对账 | Reconcile Deletes | 扫描目标端存在但源端不存在的行并删除（最终一致性关键） |
| 流式分页 | Stream Scan | 按 keyset（单列主键）或 OFFSET 分页逐页处理，内存占用恒定 |
| WAL 重试 | Write-Ahead-Log Retry | 批次写入失败后的退避重试机制（maxWalRetries=5） |
| 死信队列 | Dead Letter Queue | 重试全部失败后的事件暂存队列（上限 100,000，60s 定时重试） |
| enqueue 超时 | Enqueue Timeout | 队列满时自旋等待的上限（60s），超时丢弃依赖全量补偿 |
| DDL 清洗 | DDL Sanitization | 将 MySQL 8.0 DDL 中 5.7 不支持的排序规则降级为 utf8mb4_general_ci |
| 忽略模式 | Ignore Pattern | 按库/表粒度跳过同步的规则（精确/glob 通配/re:正则） |
| 表结构指纹 | Table Signature | 列名列表 + 主键列表拼接的签名，用于 ALTER 检测 |
| 配置热生效 | Hot Reload | 通过 update + reload 双步流程在运行时修改同步配置，无需重启 |
| 用户隔离 | User Isolation | 动态新增的映射绑定 userId，用户只能看到全局映射和自己创建的映射 |

---

## 第二章 总体设计

### 2.1 项目定位

GeoDRSync 是一个 **MySQL 跨主机数据库灾备同步中间件**，核心能力：

1. **主机对模型**：配置源主机→目标主机（1:1），自动同步该源主机下所有用户库与表
2. **自动化库发现**：定时探测源主机所有用户库，排除系统库（information_schema/mysql/performance_schema/sys/geodrsync）与忽略库
3. **自动化表同步**：对每个库下所有表进行全量（流式分页）+ 增量（水位）同步
4. **DDL 自动同步**：新增表自动建表、ALTER 检测重建表、DROP 检测删表
5. **双模式忽略**：DML+DDL 全忽略 / 仅 DDL 忽略，支持库级、表级（扁平+层级）、通用三种粒度
6. **字段转换**：支持按库/表/字段维度的值替换（IP 替换、脱敏、枚举映射）
7. **跨版本兼容**：MySQL 8.0 源 → 5.7 目标自动 DDL 排序规则降级
8. **删除对账**：目标端存在但源端已删除的行自动清理（最终一致性保障）
9. **大表优化**：首次全量同步跳过 reconcileDeletes、流式分页进度日志
10. **管理后台**：Web UI 配置、监控、异常处理、多用户隔离、操作审计

### 2.2 技术选型表

| 层级 | 技术 | 版本 | 选型理由 |
|------|------|------|----------|
| 框架 | Spring Boot | 3.4.3 | 成熟的企业级 Java 微服务框架，内嵌 Tomcat |
| ORM | MyBatis-Plus | 3.5.9 | 灵活的动态 SQL + 内置 CRUD + 分页插件 |
| 数据库驱动 | mysql-connector-j | 8.3.0 | 同时兼容 MySQL 5.7 / 8.0 |
| 连接池 | HikariCP | Spring Boot 内置 | 业界最快的 JDBC 连接池 |
| 前端框架 | React | 18.3.1 (UMD) | 组件化 UI，轻量部署（无 Node 构建链） |
| 运行时转译 | @babel/standalone | 7.29.7 (构建期) | JSX→经典脚本预编译 |
| 构建工具 | Maven | 3.9.9 | 标准 Java 构建 |
| 测试框架 | JUnit 5 + Spring Boot Test | — | 集成测试（@SpringBootTest + TestRestTemplate） |
| AOP | spring-boot-starter-aop | 3.4.3 | 操作日志切面 |
| 验证 | spring-boot-starter-validation | 3.4.3 | DTO 参数校验 |
| 监控 | spring-boot-starter-actuator | 3.4.3 | 健康检查 /actuator/health |
| 运行环境 | JDK | 17 编译 / 21 运行 | LTS 版本 |
| 部署 | systemd + Nginx | CentOS 7 | Linux 标准服务管理 + 反向代理 |

### 2.3 系统架构图

```
+------------------------------------------------------------------+
|                        浏览器 (React 18 SPA)                       |
|   登录页 | 统计监控 Tab | 配置中心 Tab                              |
+----------------------------------+-----------------------------------+
                                   | HTTP + Cookie (HttpSession)
                                   v
+------------------------------------------------------------------+
|                     Spring Boot 8080 (内嵌 Tomcat)                 |
|                                                                    |
|  +-------------------+    +----------------------------------+     |
|  | AuthInterceptor   |--->| AuthController (/auth/**)        |     |
|  | (Session 鉴权)     |    |  register/login/logout/me        |     |
|  +-------------------+    +----------------------------------+     |
|           |                        |                              |
|           v                        v                              |
|  +-------------------+    +----------------------------------+     |
|  | UserContext       |    | SyncProgressController (/sync/**)|     |
|  | (ThreadLocal)     |    |  db/list, status, mappings,      |     |
|  +-------------------+    |  mapping/add/remove/reload/update|     |
|           |                +----------------------------------+     |
|           v                           |                           |
|  +-------------------+                v                           |
|  | OperationLogAspect|    +----------------------------------+     |
|  | (AOP 审计日志)     |    | SyncProgressService             |     |
|  +-------------------+    |  queryPage, status, persist      |     |
|           |                +----------------------------------+     |
|           v                           |                           |
|  +-------------------+                v                           |
|  | operation_log 表  |    +----------------------------------+     |
|  +-------------------+    | DatabaseSyncManager (总控)        |     |
|                           |  syncProgressMap (内存缓存)       |     |
|                           |  runningJobs (主机对→SyncHostJob) |     |
|                           |  addMappings/removeMapping/      |     |
|                           |  reloadMapping/updateMappingConfig|    |
|                           +----------------------------------+     |
|                                      |                            |
|                    +-----------------+-----------------+          |
|                    v                                   v          |
|          +------------------+              +------------------+  |
|          | SyncHostJob × N  |              | 定时任务          |  |
|          | (主机对同步引擎)  |              | SyncProgressJob  |  |
|          |  doSync() 每2s   |              |  (10s 持久化进度) |  |
|          +------------------+              | ScanNewDatabaseJob|  |
|                    |                       |  (60s 新库发现)   |  |
|         +----------+----------+            +------------------+  |
|         |                     |                                  |
|         v                     v                                  |
|  +----------------+   +------------------+                       |
|  | DatabaseMeta   |   | DataBaseSinkFunc |                       |
|  | dataService    |   | (库级 Sink)       |                       |
|  | 库/表发现      |   |  tableFunctionMap|                       |
|  | DDL 清洗       |   +------------------+                       |
|  +----------------+            |                                 |
|         |                      v                                 |
|         |              +------------------+                      |
|         |              | TableSinkFunc × N|                      |
|         |              | (表级写入器)      |                      |
|         |              |  队列+WAL+死信   |                      |
|         |              +------------------+                      |
|         |                      |                                 |
|         v                      v                                 |
|  +-------------+        +-------------+                          |
|  | DynamicShard|        | TableLevel  |                          |
|  | Connection  |        | QueueMgr    |                          |
|  | Manager     |        | GroupedExec |                          |
|  | (HikariCP   |        +-------------+                          |
|  |  分片+信号量)|                                                 |
|  +-------------+                                                 |
+------------------------------------------------------------------+
         |                      |                      |
         v                      v                      v
+----------------+    +----------------+    +----------------+
| MySQL 源 (8.0) |    | MySQL 目标(5.7)|    | 元数据库        |
| 生产中心       |--->| 灾备中心       |    | geodrsync 库   |
| 所有用户库     |    | 镜像库         |    | sync_progress  |
+----------------+    +----------------+    | sys_user       |
                                            | operation_log  |
                                            +----------------+
```

### 2.4 模块划分

```
com.example.flinkcdcsync
├── FlinkCdcSyncApplication              ← 启动类，@EnableScheduling
├── bean/                                ← 核心数据模型
│   ├── DatabaseMapping.java             ← 主机对映射（源→目标，含 userId）
│   ├── DatabaseConfig.java              ← 数据库连接配置（host/port/user/pass/db）
│   ├── TableInfo.java                   ← 表结构信息（表名+列+主键）
│   ├── RowChange.java                   ← 数据变更事件（INSERT/DELETE + after + pk）
│   ├── TransformRule.java               ← 字段转换规则（db/table/field + src→tgt）
│   └── DbTableIgnore.java               ← 库-表层级忽略（database + tables[]）
├── controller/                          ← REST API 入口
│   ├── SyncProgressController.java      ← /sync/** 核心接口（15 个端点）
│   ├── AuthController.java              ← /auth/** 鉴权接口（4 个端点）
│   └── OperationLogController.java      ← /operation-log/** 审计日志查询
├── dto/                                 ← 请求/响应 DTO
│   ├── SyncProgressPageQuery.java       ← 分页查询（page/pageSize/condition/order）
│   ├── SyncProgressVO.java              ← 进度展示对象
│   ├── SyncMappingRequestDTO.java       ← 新增映射请求（源/目标+多库扇出）
│   ├── DatabaseMappingVO.java           ← 映射展示对象（密码脱敏+运行状态+来源）
│   ├── MappingBatchResult.java          ← 批量新增结果（created/skipped/failed）
│   └── SourceTablesRequestDTO.java      ← 加载源表请求
├── service/                             ← 业务服务层
│   ├── SyncProgressService.java         ← 进度服务接口
│   ├── SyncProgressServiceImpl.java     ← 进度查询/状态/内存持久化
│   └── DatabaseMetadataService.java     ← 库表元数据操作 + DDL 清洗
├── manager/                             ← 核心管理器层
│   ├── DatabaseSyncManager.java         ← 总控调度器（作业CRUD+配置热生效+用户隔离）
│   ├── AuthInterceptor.java             ← 登录拦截器（Session 鉴权 + UserContext）
│   ├── DynamicShardedConnectionManager.java ← 动态分片连接池（HikariCP + 信号量）
│   ├── GroupedExecutorManager.java      ← 分组线程池
│   ├── TableLevelQueueManager.java      ← 表级阻塞队列（有界 LinkedBlockingQueue）
│   └── SysUserInitializer.java          ← 默认账号初始化（admin/admin123）
├── sync/                                ← 同步引擎
│   └── SyncHostJob.java                 ← 主机级同步作业（717行，核心引擎）
├── sink/                                ← 数据写入管线
│   ├── DataBaseSinkFunction.java        ← 数据库级 Sink（表路由 + DDL 处理）
│   └── TableSinkFunction.java           ← 表级 Sink（队列+批量+WAL+死信）
├── job/                                 ← 定时任务
│   ├── SyncProgressJob.java             ← 10s 内存进度持久化到 sync_progress 表
│   ├── ScanNewDatabaseJob.java          ← 60s 新增库发现（日志记录）
│   ├── SourceBinlogJob.java             ← 30s Binlog 位点备份
│   └── DBHASwitchStatusJob.java         ← HA 主备切换检测
├── po/                                  ← MyBatis-Plus 实体
│   ├── SyncProgress.java                ← sync_progress 表
│   ├── SysUser.java                     ← sys_user 表
│   ├── OperationLog.java                ← operation_log 表
│   └── ...
├── mapper/                              ← MyBatis-Plus Mapper
├── enums/                               ← 枚举
│   ├── SyncStateEnum.java               ← 0无效/1全量/2同步中/3中止
│   ├── DelivationStatusEnum.java        ← 1正常/2异常
│   └── AllSyncStateEnum.java            ← 总状态 NORMAL/ABNORMAL
├── common/                              ← 通用组件
│   ├── Result.java                      ← 统一响应 {code,msg,success,traceId,data}
│   ├── GeoDRSyncErrorCodeEnum.java      ← 10位错误码体系
│   ├── GlobalExceptionHandler.java      ← @ControllerAdvice 全局异常
│   ├── TraceContext + TraceIdFilter     ← MDC 链路追踪（12位 traceId）
│   ├── UserContext.java                 ← ThreadLocal 用户上下文
│   ├── PasswordUtil.java                ← SHA-256 + 16字节随机盐
│   ├── IpUtils.java                     ← 客户端 IP 提取（X-Forwarded-For）
│   ├── OperationLogAspect.java          ← AOP 操作日志切面
│   ├── annotation/OperationLogAnnotation.java ← @OperationLogAnnotation 注解
│   └── InternationalizationEnum.java    ← 中止原因/处理方法国际化
├── config/                              ← Spring 配置
│   ├── WebConfig.java                   ← 拦截器注册（app.auth.enabled 开关）
│   ├── CorsConfig.java                  ← 跨域配置
│   └── GeoDRSyncProperties.java         ← geodrsync.* 配置绑定
└── util/
    └── MatchPattern.java                ← 表名匹配（精确/glob/re:正则）
```

### 2.5 核心类图关系

```
DatabaseSyncManager "1" *-- "1..N" SyncHostJob : 管理
DatabaseSyncManager "1" *-- "1" DynamicShardedConnectionManager : 注入
DatabaseSyncManager "1" *-- "1" GroupedExecutorManager : 注入
DatabaseSyncManager "1" *-- "1" TableLevelQueueManager : 注入
DatabaseSyncManager "1" *-- "1" DatabaseMetadataService : 注入
DatabaseSyncManager "1" o-- "1..N" DatabaseMapping : 配置
DatabaseSyncManager ..> UserContext : 读取 userId

SyncHostJob "1" *-- "1..N" DataBaseSinkFunction : 按库惰性创建(sinksByDb)
SyncHostJob "1" --> "1" DatabaseMetadataService : 调用
SyncHostJob "1" --> "1" DynamicShardedConnectionManager : 调用
SyncHostJob "1" --> "1" DatabaseMapping : 配置
SyncHostJob "1" o-- "1..N" SyncProgress : 进度(引用 syncProgressMap)

DataBaseSinkFunction "1" *-- "1..N" TableSinkFunction : 路由(tableFunctionMap)
DataBaseSinkFunction "1" o-- "1..N" TableInfo : 缓存(tableInfoMap)
DataBaseSinkFunction --> DatabaseMetadataService : 调用
DataBaseSinkFunction --> TableLevelQueueManager : 调用

TableSinkFunction "1" *-- "1" TableLevelQueueManager.TableQueue : 队列
TableSinkFunction "1" o-- "1" ConcurrentLinkedQueue<RowChange> : 死信队列
TableSinkFunction --> DynamicShardedConnectionManager : 获取连接
TableSinkFunction --> GroupedExecutorManager : 线程池

DatabaseMetadataService --> DynamicShardedConnectionManager : 获取连接

AuthInterceptor ..> UserContext : 填充/清理
AuthInterceptor ..> HttpSession : 读取 uid
OperationLogAspect ..> UserContext : 读取操作者
OperationLogAspect ..> OperationLogAnnotation : 拦截
AuthController --> SysUserMapper : 用户CRUD
AuthController ..> UserContext : 登录后设置
SyncProgressController --> DatabaseSyncManager : 调用
SyncProgressController --> SyncProgressService : 调用
SyncProgressController --> DatabaseMetadataService : 调用
SyncProgressServiceImpl --> DatabaseSyncManager : 调用(内存缓存)
SyncProgressJob --> SyncProgressService : 10s 持久化
ScanNewDatabaseJob --> DatabaseMetadataService : 60s 扫描
```

---

## 第三章 数据库设计

### 3.1 元数据库 ER 关系

元数据库 `geodrsync` 存储同步进度、用户、操作日志等管控数据。与业务库（源/目标）完全隔离。

```
+-------------------+          +-------------------+
|    sync_progress  |          |     sys_user      |
+-------------------+          +-------------------+
| PK: ID            |          | PK: ID            |
| UK: (SOURCE_IP,   |          | UK: USERNAME      |
|      SOURCE_DB)   |          | PASSWORD_HASH     |
| USER_ID (FK逻辑)  |--------->| SALT              |
| STATE             |          +-------------------+
| DEVIATION_STATUS  |                  ^
+-------------------+                  |
        |                              |
        |          +-------------------+
        |          |  operation_log    |
        |          +-------------------+
        |          | PK: ID            |
        +--------->| USER_ID (FK逻辑)  |
                   | OPERATION_TYPE    |
                   | REQUEST_URL       |
                   | RESULT_STATUS     |
                   | CLIENT_IP         |
                   | DURATION_MS       |
                   +-------------------+

+-----------------------------+  +-----------------------------+
| source_latest_binlog_info   |  | dbha_switch_status          |
+-----------------------------+  +-----------------------------+
| PK: ID                      |  | PK: ID                      |
| UK: (SOURCE_IP, SOURCE_DB)  |  | UK: (VIRTUAL_IP, SOURCE_DB) |
| SOURCE_BINLOG_FILE          |  | VIRTUAL_IP / MAIN_IP        |
| SOURCE_BINLOG_POS           |  | OLD_MAIN_IP / OLD_STANDBY   |
+-----------------------------+  | SWITCH_TIME                 |
                                 +-----------------------------+

+-----------------------------+
| sync_restart_task           |
+-----------------------------+
| PK: ID                      |
| UK: (SOURCE_HOST,SOURCE_DB, |
|      TARGET_HOST,TARGET_DB) |
| STATUS (0待处理/1处理中/    |
|         2成功/3失败)        |
+-----------------------------+
```

### 3.2 全部表结构 DDL

#### 3.2.1 sync_progress（同步进度表）

```sql
CREATE TABLE IF NOT EXISTS sync_progress (
    ID                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    SOURCE_IP           VARCHAR(45)  NOT NULL COMMENT '生产中心IP',
    SOURCE_DB_NAME      VARCHAR(64)  NOT NULL COMMENT '生产中心库名',
    TARGET_IP           VARCHAR(45)  NOT NULL COMMENT '灾备中心IP',
    TARGET_DB_NAME      VARCHAR(64)  NOT NULL COMMENT '灾备中心库名',
    USER_ID             BIGINT       DEFAULT NULL COMMENT '所属用户ID（用户隔离）',
    STATE               TINYINT      NOT NULL DEFAULT 0 COMMENT '0-失效 1-全量 2-同步中 3-中止',
    SYNC_START_TIME     DATETIME     DEFAULT NULL COMMENT '同步开始时间',
    SOURCE_BINLOG_FILE  VARCHAR(255) DEFAULT NULL COMMENT '源binlog文件(本地引擎为LOCAL-SYNC)',
    SOURCE_BINLOG_TIME  DATETIME     DEFAULT NULL COMMENT '源binlog时间',
    SYNC_BINLOG_FILE    VARCHAR(255) DEFAULT NULL COMMENT '同步到binlog文件',
    SYNC_BINLOG_TIME    DATETIME     DEFAULT NULL COMMENT '同步到binlog时间',
    DEVIATION_TIMES     BIGINT       DEFAULT NULL COMMENT '偏差秒数',
    DEVIATION_STATUS    TINYINT      DEFAULT NULL COMMENT '1-正常 2-异常',
    SUSPENSION_REASON   VARCHAR(512) DEFAULT NULL COMMENT '中止原因(国际化code)',
    PROCESSING_METHOD   VARCHAR(512) DEFAULT NULL COMMENT '处理建议(含 tables=N,src=N,tgt=N)',
    CREATE_TIME         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_TIME         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    UNIQUE KEY IDX_SOURCE_IP_DB (SOURCE_IP, SOURCE_DB_NAME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步进度';
```

字段说明：
- `STATE`：0=INVALID(失效), 1=FULL_SYNC(全量), 2=SYNCING(同步中), 3=SUSPENDED(中止)
- `SOURCE_BINLOG_FILE`：本地内嵌引擎模式固定为 `LOCAL-SYNC`（无真实 binlog 消费）
- `PROCESSING_METHOD`：格式如 `tables=5,src=1000,tgt=1000`（本轮同步的表数/源行数/目标行数）
- `USER_ID`：由 install.sh 的 `ALTER TABLE ADD COLUMN` 添加，实现用户隔离

#### 3.2.2 dbha_switch_status（HA 主备切换状态表）

```sql
CREATE TABLE IF NOT EXISTS dbha_switch_status (
    ID                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    VIRTUAL_IP          VARCHAR(45)  NOT NULL COMMENT '虚IP',
    OLD_MAIN_IP         VARCHAR(45)  DEFAULT NULL COMMENT '原主IP',
    OLD_STANDBY_IP      VARCHAR(45)  DEFAULT NULL COMMENT '原备IP',
    MAIN_IP             VARCHAR(45)  NOT NULL COMMENT '新主IP',
    STANDBY_IP          VARCHAR(45)  NOT NULL COMMENT '新备IP',
    SWITCH_TIME         DATETIME     DEFAULT NULL COMMENT '切换时间',
    SOURCE_DB_NAME      VARCHAR(64)  NOT NULL COMMENT '库名',
    SOURCE_BINLOG_FILE  VARCHAR(255) DEFAULT NULL COMMENT 'binlog文件',
    SOURCE_BINLOG_POS   BIGINT       DEFAULT NULL COMMENT 'binlog位点',
    CREATE_TIME         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_TIME         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    UNIQUE KEY IDX_VIRTUAL_IP_DB (VIRTUAL_IP, SOURCE_DB_NAME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HA主备切换状态';
```

#### 3.2.3 source_latest_binlog_info（源库最新 Binlog 位点表）

```sql
CREATE TABLE IF NOT EXISTS source_latest_binlog_info (
    ID                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    SOURCE_IP           VARCHAR(45)  NOT NULL,
    SOURCE_DB_NAME      VARCHAR(64)  NOT NULL,
    SOURCE_BINLOG_FILE  VARCHAR(255) NOT NULL,
    SOURCE_BINLOG_TIME  DATETIME     NOT NULL,
    SOURCE_BINLOG_POS   BIGINT       NOT NULL,
    CREATE_TIME         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_TIME         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    UNIQUE KEY IDX_SOURCE_IP_DB (SOURCE_IP, SOURCE_DB_NAME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='源库最新binlog位点';
```

#### 3.2.4 sync_restart_task（同步重启任务表）

```sql
CREATE TABLE IF NOT EXISTS sync_restart_task (
    ID           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    SOURCE_HOST  VARCHAR(45) NOT NULL,
    SOURCE_DB    VARCHAR(64) NOT NULL,
    TARGET_HOST  VARCHAR(45) NOT NULL,
    TARGET_DB    VARCHAR(64) NOT NULL,
    STATUS       TINYINT     DEFAULT 0 COMMENT '0-待处理 1-处理中 2-成功 3-失败',
    ERROR_MSG    VARCHAR(1024) DEFAULT NULL,
    CREATE_TIME  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_TIME  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    UNIQUE KEY IDX_UNIQUE_KEY (SOURCE_HOST, SOURCE_DB, TARGET_HOST, TARGET_DB)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步重启任务';
```

#### 3.2.5 sys_user（系统用户表）

```sql
CREATE TABLE IF NOT EXISTS sys_user (
    ID             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    USERNAME       VARCHAR(64)  NOT NULL,
    PASSWORD_HASH  VARCHAR(128) NOT NULL COMMENT 'salt:sha256Hex',
    SALT           VARCHAR(64)  NOT NULL COMMENT '16字节随机盐',
    CREATE_TIME    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATE_TIME    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    UNIQUE KEY IDX_USERNAME (USERNAME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台管理用户';
```

密码存储格式：`SHA-256(salt + rawPassword)` 的十六进制串，salt 为 16 字节随机字符串。
默认账号：`admin / admin123`（由 `SysUserInitializer` 在启动时自动创建）。

#### 3.2.6 operation_log（操作审计日志表）

> 注：此表由 `install.sh` 部署脚本创建，不在 `init.sql` 中。

```sql
CREATE TABLE IF NOT EXISTS operation_log (
    ID              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    USER_ID         BIGINT       DEFAULT NULL COMMENT '操作用户ID',
    USERNAME        VARCHAR(64)  DEFAULT NULL COMMENT '操作用户名',
    OPERATION_TYPE  VARCHAR(64)  NOT NULL    COMMENT '操作类型(LOGIN/LOGOUT/REGISTER/RESYNC/MAPPING_ADD等)',
    OPERATION_DESC  VARCHAR(255) DEFAULT NULL COMMENT '操作描述',
    REQUEST_URL     VARCHAR(512) DEFAULT NULL COMMENT '请求URL',
    REQUEST_METHOD  VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP方法',
    REQUEST_PARAMS  TEXT         DEFAULT NULL COMMENT '请求参数(密码字段已脱敏)',
    RESULT_STATUS   VARCHAR(20)  DEFAULT NULL COMMENT 'SUCCESS/FAILURE',
    ERROR_MSG       TEXT         DEFAULT NULL COMMENT '错误信息',
    CLIENT_IP       VARCHAR(45)  DEFAULT NULL COMMENT '客户端IP',
    DURATION_MS     BIGINT       DEFAULT NULL COMMENT '执行耗时(ms)',
    CREATE_TIME     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    KEY IDX_USER_ID (USER_ID),
    KEY IDX_OPERATION_TYPE (OPERATION_TYPE),
    KEY IDX_CREATE_TIME (CREATE_TIME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';
```

### 3.3 业务示例库

`init.sql` 还创建了 4 个示例业务库用于测试：

| 库名 | 用途 | 表 |
|------|------|------|
| `geo_source` | 生产中心示例库 | t_order, t_user |
| `geo_target` | 灾备中心示例库（结构与源一致） | t_order, t_user |
| `biz_source` | 第二个生产中心库（多库同步演示） | t_customer |
| `biz_target` | 第二个灾备中心库 | t_customer |

所有示例表均含 `update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`，用于增量水位同步。

---

## 第四章 核心模块详细设计

### 4.1 SyncHostJob — 主机级同步引擎

**源码位置**：`sync/SyncHostJob.java`（717 行）

#### 4.1.1 职责

针对单个主机对（sourceHost→targetHost），定时扫描源主机下所有用户库与表，完成全量/增量同步、DDL 检测、删除对账，并将进度写入内存缓存。

#### 4.1.2 生命周期

```
SyncHostJob.start()
  ├── running = true
  ├── 创建 ScheduledExecutorService（单线程，daemon，命名 SyncHostJob-{instanceKey}）
  ├── scheduler.scheduleAtFixedRate(safeSync, 0, pollIntervalMs, MILLISECONDS)
  │   └── pollIntervalMs = max(500, props.engine.pollIntervalMs)  // 默认 2000ms
  └── 日志: "SyncHostJob started for {instanceKey}"

SyncHostJob.stop()
  ├── running = false
  ├── scheduler.shutdownNow()
  ├── 遍历 sinksByDb 关闭所有库级 Sink
  ├── sinksByDb.clear()
  └── 日志: "SyncHostJob stopped for {instanceKey}"

SyncHostJob.fullReset()  // 全量重置
  ├── stop()
  ├── watermarks.clear()         // 清空所有表的水位
  ├── knownSignatures.clear()    // 清空表结构指纹
  ├── knownTablesByDb.clear()    // 清空已知表集合
  └── start()                    // 重新启动（下一轮全量重扫）
```

#### 4.1.3 核心状态字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `sinksByDb` | `Map<String, DataBaseSinkFunction>` | 每个库一个独立的库级 Sink（惰性创建） |
| `watermarks` | `Map<String, LocalDateTime>` | 表级 update_time 水位（key=`db.table`） |
| `knownSignatures` | `Map<String, String>` | 表结构指纹（key=`db.table`，value=`列名列表#主键列表`） |
| `knownTablesByDb` | `Map<String, Set<String>>` | 每个库当前已发现的表集合（用于 DROP 检测） |
| `progressMap` | `Map<String, SyncProgress>` | 引用 DatabaseSyncManager.syncProgressMap（内存进度缓存） |
| `running` | `volatile boolean` | 运行标志（控制 doSync 提前退出） |

#### 4.1.4 doSync 主流程

```
safeSync()
  ├── if (!running) return
  ├── try { doSync() } catch (Exception) { log.error }  // 异常不中断调度
  └── 继续

doSync()
  ├── sourceHost = mapping.toSourceHostConfig()
  ├── targetHost = mapping.toTargetHostConfig()
  ├── dbs = metadataService.getAllUserDatabases(sourceHost, ignoreDatabases)
  │   └── 排除系统库: information_schema, mysql, performance_schema, sys, geodrsync
  └── for (String db : dbs)
      ├── if (!running) return          // 优雅停止
      ├── if (MatchPattern.matchesAny(ignoreDatabases, db)) continue
      ├── try { syncOneDatabase(db, sourceHost, targetHost) }
      └── catch (Exception) { log.error("单库同步失败，继续其他库") }
          └── 单库失败不中断其他库的同步
```

#### 4.1.5 syncOneDatabase 单库同步流程

```
syncOneDatabase(db, sourceHost, targetHost)
  │
  ├── 1. 确保目标库存在
  │   if (!metadataService.ensureTargetDatabase(targetHost, db))
  │       return  // 无法建库则跳过
  │   └── CREATE DATABASE IF NOT EXISTS `db` DEFAULT CHARACTER SET utf8mb4
  │
  ├── 2. 获取库级 Sink（惰性创建，绑定真实库名）
  │   sink = getSink(db)
  │   └── sinksByDb.computeIfAbsent(db, k -> new DataBaseSinkFunction(...))
  │
  ├── 3. 扫描源库所有表
  │   tables = metadataService.listTables(sourceDB, db)
  │   └── SELECT TABLE_NAME FROM information_schema.TABLES
  │       WHERE TABLE_SCHEMA='db' AND TABLE_TYPE='BASE TABLE'
  │   current = new HashSet<>(tables)
  │
  ├── 4. 遍历每个表同步
  │   for (String table : tables)
  │       ├── if (!running) return
  │       ├── if (isDmlIgnored(db, table)) continue   // DML+DDL 全忽略
  │       ├── ddlIgnored = isDdlIgnored(db, table)
  │       ├── if (!ddlIgnored) detectAndApplyAlter(db, table, ...)  // ALTER 检测
  │       ├── if (sink.ensureTable(table) == null) continue  // 建表失败/忽略
  │       ├── synced = syncTable(sink, db, table, sourceDB, targetDB)
  │       ├── sourceRows += synced
  │       ├── targetRows += countRows(targetDB, db, table)
  │       └── tableCount++
  │
  ├── 5. DROP 检测：源库已不存在的表 → 删除目标库对应表
  │   prev = knownTablesByDb.get(db)
  │   if (prev != null)
  │       for (String t : prev)
  │           if (!current.contains(t) && !isDmlIgnored(db, t))
  │               sink.removeTable(t)              // DDL DROP
  │               knownSignatures.remove(db + "." + t)
  │   knownTablesByDb.put(db, current)
  │
  ├── 6. 等待所有表队列处理完成（保证一致性后再统计）
  │   sink.flush(15000)  // 15s 超时
  │   └── 遍历所有 TableSinkFunction.waitIdle()
  │
  └── 7. 更新内存进度
      updateProgress(db, tableCount, sourceRows, targetRows)
      └── progressMap.computeIfAbsent(sourceHost|db, ...)
          state = SYNCING, binlogFile = "LOCAL-SYNC"
          processingMethod = "tables=N,src=N,tgt=N"
```

#### 4.1.6 DDL 检测：ALTER

```
detectAndApplyAlter(db, table, sourceDB, targetDB)
  ├── cur = metadataService.getTableInfo(sourceDB, db, table)
  │   └── 查询 information_schema.COLUMNS + KEY_COLUMN_USAGE(PRIMARY)
  ├── if (cur == null) return
  ├── sig = signature(cur)
  │   └── sig = "col1,col2,col3#pk1,pk2"  (列名列表 + # + 主键列表)
  ├── prev = knownSignatures.get(db + "." + table)
  ├── if (prev != null && !prev.equals(sig))
  │   ├── metadataService.dropTargetTable(targetDB, table)   // DROP TABLE IF EXISTS
  │   ├── metadataService.ensureTargetTable(sourceDB, targetDB, table)  // 按源DDL重建
  │   ├── watermarks.remove(db + "." + table)                // 重置水位→下一轮全量
  │   └── log.info("DDL ALTER detected, recreated target table")
  └── knownSignatures.put(db + "." + table, sig)
```

> **设计要点**：ALTER 检测基于列名+主键的指纹签名。任何列增删改、主键变更、列顺序变化都会触发目标表重建 + 水位重置。重建后下一轮自动全量重扫，依靠幂等 upsert 保证数据最终一致。

#### 4.1.7 单表同步：全量 vs 增量

```
syncTable(sink, db, table, sourceDB, targetDB)
  ├── info = sink.tableInfoMap.get(table)
  ├── hasUpdateTime = info.getColumns().contains("update_time")  // 大小写不敏感
  ├── wm = watermarks.get(db + "." + table)
  ├── isFullSync = (wm == null)
  │
  ├── if (hasUpdateTime && wm != null)
  │   └── 增量同步: selectChanged(sourceDB, db, table, info, wm)
  │       SQL: SELECT * FROM `db`.`table` WHERE `update_time` > ? ORDER BY `update_time` ASC LIMIT (batchSize*5)
  │       └── 单批有 LIMIT 上限，天然不撑爆内存
  │
  └── else
      └── 全量同步: streamScan(sourceDB, db, table, info, pageHandler)
          └── 流式分页，内存恒定（见 4.1.8）
  │
  ├── processRows(sink, db, table, info, rows, wmKey, hasUpdateTime)
  │   ├── 更新水位: watermarks.put(wmKey, row.update_time)  // 取最大值
  │   ├── applyTransforms(db, table, row, pkCols)            // 字段转换（跳过主键）
  │   └── sink.invoke(new RowChange(INSERT, table, row, pkCols))  // 入队
  │
  ├── 大表优化: 首次全量同步跳过 reconcileDeletes
  │   if (isFullSync && scanned > 100_000)
  │       log.info("Skipping reconcileDeletes (large table first sync: {} rows)")
  │       └── 首次全量刚写入所有数据，目标端不存在多余行，跳过对账节省时间
  │   else
  │       reconcileDeletes(sink, db, table, sourceDB, targetDB, info)
  │
  └── return scanned
```

#### 4.1.8 流式分页扫描 streamScan（大表核心优化）

```
streamScan(cfg, db, table, info, pageHandler)
  ├── pageSize = props.engine.batchSize * 5  // 默认 5000
  ├── pks = info.getPrimaryKeyColumns()
  │
  ├── if (pks != null && pks.size() == 1)    // 单列主键: keyset 分页（高效）
  │   │   pk = pks.get(0)
  │   │   lastKey = null
  │   │   while (true)
  │   │       SQL: SELECT * FROM `db`.`table`
  │   │             [WHERE `pk` > ?] ORDER BY `pk` ASC LIMIT pageSize
  │   │       page = queryPage(cfg, sql, lastKey)
  │   │       if (page.isEmpty()) break
  │   │       pageHandler.accept(page)      // 处理本页
  │   │       total += page.size()
  │   │       ├── 进度日志: 每 100,000 行或每 30 秒输出一次
  │   │       │   log.info("streamScan progress {db}.{table}: {total} rows scanned")
  │   │       if (page.size() < pageSize) break
  │   │       lastKey = page.get(last).get(pk)
  │   └── return total
  │
  └── else                                    // 无单列主键: OFFSET 分页兜底
      offset = 0
      while (true)
          SQL: SELECT * FROM `db`.`table` LIMIT pageSize OFFSET offset
          page = queryRows(cfg, db, table, sql)
          if (page.isEmpty()) break
          pageHandler.accept(page)
          total += page.size()
          offset += page.size()
          if (page.size() < pageSize) break
      return total
```

> **设计要点**：
> - keyset 分页（`WHERE pk > lastKey ORDER BY pk LIMIT N`）性能恒定，不受 OFFSET 深翻页性能衰减影响
> - 每页处理完即释放，内存占用恒定（约 pageSize 行），百万/千万级行也不会 OOM
> - 大表进度日志便于运维监控长时间全量同步的进度

#### 4.1.9 删除对账 reconcileDeletes（最终一致性关键）

```
reconcileDeletes(sink, db, table, sourceDB, targetDB, info)
  ├── if (pkCols == null || pkCols.isEmpty()) return  // 无主键跳过
  ├── pk = pkCols.get(0)
  ├── pageSize = batchSize * 5
  ├── lastKey = null
  │
  └── while (true)
      ├── 1. 分页拉取目标端 PK（keyset 分页，内存恒定）
      │   targetPage = selectPksPaged(targetDB, db, table, pk, pageSize, lastKey)
      │   SQL: SELECT `pk` FROM `db`.`table` [WHERE `pk` > ?] ORDER BY `pk` ASC LIMIT pageSize
      │   if (targetPage.isEmpty()) break
      │
      ├── 2. 分批回查源端：这些 PK 是否仍存在（IN 列表按 1000 分片）
      │   present = new HashSet<>()
      │   for (int i = 0; i < targetPage.size(); i += 1000)
      │       chunk = targetPage.subList(i, min(i+1000, targetPage.size()))
      │       present.addAll(selectPksIn(sourceDB, db, table, pk, chunk))
      │       └── SELECT `pk` FROM `db`.`table` WHERE `pk` IN (?,?,...)
      │
      ├── 3. 源端不存在的 PK → 发 DELETE 事件
      │   for (Object tp : targetPage)
      │       if (!present.contains(tp))
      │           sink.invoke(new RowChange(DELETE, table, {pk: tp}, pkCols))
      │
      ├── if (targetPage.size() < pageSize) break
      └── lastKey = targetPage.get(last)
```

> **设计要点**：
> - 有界实现：按主键分页拉取目标端 PK，每页分批回查源端，内存占用恒定
> - IN 列表按 1000 分片，避免 MySQL `max_allowed_packet` 超限
> - 删除事件通过 `sink.invoke(DELETE)` 入队，由 TableSinkFunction 异步执行 `DELETE WHERE pk=?`

#### 4.1.10 字段转换 applyTransforms

```
applyTransforms(db, table, row, pkCols)
  ├── if (transformRules.isEmpty() || row == null) return
  ├── pkSet = new HashSet<>(pkCols)
  └── for (Map.Entry<String, Object> e : row.entrySet())
      col = e.getKey()
      if (pkSet.contains(col)) continue       // 跳过主键列（避免破坏幂等 upsert）
      val = e.getValue()
      if (val == null) continue
      strVal = String.valueOf(val)
      for (TransformRule rule : transformRules)
          if (rule.matches(db, table, col) && strVal.equals(rule.getSourceValue()))
              e.setValue(rule.getTargetValue())  // 值替换
              break
```

TransformRule 匹配逻辑（`rule.matches(db, table, col)`）：
- `dbName` 为 null 或匹配 db
- `tableName` 为 null 或匹配 table
- `fieldName` 为 null 或匹配 col
- 三个维度都满足时命中，执行 `sourceValue → targetValue` 替换

#### 4.1.11 忽略规则匹配

```
isDmlIgnored(db, table)  // DML + DDL 全忽略
  ├── if (MatchPattern.matchesTable(ignoreTables, db, table)) return true
  │   └── 旧式扁平写法，支持 "库.表" 限定
  ├── if (MatchPattern.matchesAny(commonIgnoreTables, table)) return true
  │   └── 通用忽略，对所有库生效
  └── for (DbTableIgnore e : ignoreTablesByDb)
      if (matchesAny([e.database], db) && matchesTable(e.tables, db, table)) return true
      └── 层级忽略：库名命中且表名命中该库下的表规则

isDdlIgnored(db, table)  // 仅忽略 DDL（结构变更），DML 数据仍同步
  ├── if (MatchPattern.matchesTable(ignoreDdlTables, db, table)) return true
  ├── if (MatchPattern.matchesAny(commonDdlIgnoreTables, table)) return true
  └── for (DbTableIgnore e : ignoreDdlTablesByDb)
      if (matchesAny([e.database], db) && matchesTable(e.tables, db, table)) return true
```

MatchPattern 支持三种写法：
1. **精确匹配**：`order_items`
2. **glob 通配**：`log_*`（含 `*` 或 `?`）
3. **正则匹配**：`re:^tmp_.*`（以 `re:` 开头）

表级规则支持 `"库.表"` 限定：`sales.log_*` → sales 库下的 log_ 前缀表

#### 4.1.12 库级 Sink 惰性创建

```
getSink(db)
  └── sinksByDb.computeIfAbsent(db, k -> new DataBaseSinkFunction(
          mapping.toSourceDB(k),       // 源库连接（含真实库名）
          mapping.toTargetDB(k),       // 目标库连接（含真实库名）
          connMgr, executorManager, queueManager, metadataService,
          batchSize=1000, maxWalRetries=5,
          instanceKey + "." + k,       // queueKey 前缀
          emptySet()))                 // ignoreTables（在 SyncHostJob 层面已过滤）
```

> **设计要点**：每个库创建独立的 DataBaseSinkFunction，绑定真实库名，避免 `db=null` 无法解析表结构的问题。Sink 惰性创建：每发现一个用户库才创建。

---

### 4.2 DataBaseSinkFunction + TableSinkFunction — 数据写入管线

**源码位置**：`sink/DataBaseSinkFunction.java` + `sink/TableSinkFunction.java`

#### 4.2.1 DataBaseSinkFunction（库级 Sink）

**职责**：管理单个库下所有表的写入算子，处理 DDL 事件（新增表/删除表），路由数据变更事件到对应表级 Sink。

```
DataBaseSinkFunction
├── tableFunctionMap: Map<String, TableSinkFunction>  // 表名→表级Sink
├── tableInfoMap: Map<String, TableInfo>              // 表名→表结构
└── ignoreTables: Set<String>                          // 忽略表集合
```

**核心方法**：

```
ensureTable(tableName)  // 确保表算子存在（动态新增表）
  ├── if (ignoreTables.contains(tableName)) return null  // 忽略表跳过
  ├── sink = tableFunctionMap.get(tableName)
  │   if (sink != null) return sink                       // 已存在直接返回
  ├── info = metadataService.getTableInfo(sourceDB, db, tableName)
  │   if (info == null) return null                       // 无法解析表结构
  ├── metadataService.ensureTargetTable(sourceDB, targetDB, tableName)
  │   └── 目标表不存在则按源DDL创建（含 DDL 清洗）
  ├── queueKey = instanceKey + "." + tableName
  ├── queue = queueManager.getOrCreateTableQueue(queueKey, 20000)  // 有界队列
  ├── newSink = new TableSinkFunction(targetDB, info, connMgr, executorMgr, queue, batchSize, maxWalRetries)
  ├── newSink.start()  // 启动处理器线程 + 死信重试定时器
  ├── tableFunctionMap.put(tableName, newSink)
  ├── tableInfoMap.put(tableName, info)
  └── return newSink

invoke(RowChange change)  // 路由数据变更事件
  ├── if (closed) return
  ├── sink = tableFunctionMap.get(change.getTableName())
  ├── if (sink == null) sink = ensureTable(change.getTableName())  // 动态建表
  ├── if (sink == null) return  // 无法建表，丢弃事件
  └── sink.enqueue(change)

addNewTable(tableName)   // DDL: 新增表
  └── ensureTable(tableName)

removeTable(tableName)   // DDL: 删除表
  ├── sink = tableFunctionMap.remove(tableName)
  │   if (sink != null) sink.close()
  ├── tableInfoMap.remove(tableName)
  ├── queueManager.removeTableQueue(instanceKey + "." + tableName)
  └── metadataService.dropTargetTable(targetDB, tableName)

flush(timeoutMs)  // 阻塞等待所有表队列处理完成
  └── for (sink : tableFunctionMap.values())
      sink.waitIdle(max(1000, deadline - now))

close()
  ├── closed = true
  ├── tableFunctionMap.forEach((name, sink) -> sink.close())
  ├── tableFunctionMap.clear()
  ├── tableInfoMap.clear()
  └── queueManager.removeAllQueuesForDatabase(instanceKey)
```

#### 4.2.2 TableSinkFunction（表级 Sink）

**职责**：负责单个表的具体数据批量写入与重试管理。从有界队列中取批次，经过 WAL 重试后写入目标库；多次失败的事件进入死信队列由定时器重试。

```
TableSinkFunction
├── targetDB: DatabaseConfig              // 目标库连接配置
├── tableInfo: TableInfo                  // 表结构（列+主键）
├── queue: TableLevelQueueManager.TableQueue  // 有界阻塞队列（容量 20000）
├── batchSize: int = max(100, batchSize)  // 批次大小（默认 1000）
├── maxWalRetries: int = max(1, maxWalRetries)  // WAL 重试次数（默认 5）
├── pendingFailedEvents: ConcurrentLinkedQueue<RowChange>  // 死信队列
├── MAX_DEAD_LETTER = 100_000             // 死信队列上限
├── processorThread: Thread               // 处理器线程
└── failedEventRetryScheduler: ScheduledExecutorService  // 死信重试定时器
```

**enqueue 超时机制**：

```
enqueue(RowChange change)
  ├── deadline = now + 60_000  // 60 秒超时上限
  ├── while (running && !queue.offer(change))
  │   ├── if (now > deadline)
  │   │   log.warn("enqueue timeout (60s) for table {}, queue full, dropping event (pk={}), "
  │   │             + "will be re-synced by next full scan")
  │   │   return  // 丢弃事件（下一轮全量重扫通过幂等 upsert 补偿）
  │   └── Thread.sleep(10)  // 自旋等待 10ms
  └── 入队成功
```

> **设计要点**：60 秒超时上限防止目标端长时间不可用时阻塞调度线程。超时后丢弃事件，下一轮全量重扫通过幂等 upsert 补偿，保证最终一致且不撑爆 JVM 堆。

**处理器循环 processorLoop**：

```
processorLoop()  // daemon 线程: "TableSink-{tableName}"
  while (running)
    ├── batch = queue.drain(batchSize)  // 非阻塞抽取最多 batchSize 个
    ├── if (batch.isEmpty()) Thread.sleep(200); continue
    ├── hasActiveProcessor = true
    ├── processBatchWithWal(batch)
    ├── lastProcessTime = now
    └── hasActiveProcessor = false
```

**WAL 重试与降级 processBatchWithWal**：

```
processBatchWithWal(changes)
  attempt = 0
  ok = false
  while (attempt < maxWalRetries && !ok)
    try
      processBatchInternal(changes)  // 批次事务写入
      ok = true
    catch (SQLException)
      attempt++
      if (attempt >= maxWalRetries)
        log.error("Batch failed after {} attempts, degrading to single-row processing")
        degradeToSingle(changes)     // 降级为单条处理
      else
        log.warn("Batch attempt {} failed, will retry")
        Thread.sleep(500L * attempt) // 线性退避: 500ms, 1000ms, 1500ms, 2000ms
```

**批次事务写入 processBatchInternal**：

```
processBatchInternal(changes)
  conn = connMgr.getConnection(targetDB)
  conn.setAutoCommit(false)          // 开启事务
  for (change : changes)
    executeOne(conn, change)         // 逐条执行 upsert/delete
  conn.commit()                       // 批次原子提交
  catch: conn.rollback()              // 失败回滚整批
  finally: conn.close(); connMgr.releaseConnection()
```

**单条降级 degradeToSingle**：

```
degradeToSingle(changes)
  for (change : changes)
    try
      processSingleEvent(change)     // 单条自动提交
    catch (SQLException)
      if (pendingFailedEvents.size() >= MAX_DEAD_LETTER)
        log.error("Dead-letter exceeded cap (100000), dropping event (re-synced by next full scan)")
        continue                     // 超出上限丢弃，依赖全量对账补偿
      pendingFailedEvents.offer(change)  // 进入死信队列
```

**upsert 写入 executeUpsert**：

```sql
-- 幂等 upsert：INSERT ... ON DUPLICATE KEY UPDATE
INSERT INTO `tableName` (col1, col2, ...) VALUES (?, ?, ...)
ON DUPLICATE KEY UPDATE col1=VALUES(col1), col2=VALUES(col2), ...
```

- 仅写入非 null 字段（`if (validCols.contains(col) && value != null)`）
- 列名从 `tableInfo.getColumns()` 校验，过滤源端多余字段

**delete 写入 executeDelete**：

```sql
DELETE FROM `tableName` WHERE pk1=? AND pk2=?
```

**参数绑定 setParam**：

| 类型 | 处理方式 |
|------|----------|
| `null` | `ps.setNull(index, Types.NULL)` |
| `LocalDateTime` | `ps.setObject(index, value)` — 以字面量绑定，避免跨时区偏移 |
| `LocalDate` | `ps.setDate(index, java.sql.Date.valueOf())` |
| `Timestamp` | `ps.setTimestamp(index, value)` |
| `Date` | `ps.setDate(index, value)` |
| `Number` | `ps.setObject(index, value)` |
| 其他 | `ps.setObject(index, value)` |

> **LocalDateTime 跨时区修复**：以字面量方式绑定 DATETIME，避免 LocalDateTime → Timestamp 经 JVM 默认时区转换，再经连接时区写出导致跨时区搬运时间被偏移（如本机 PDT 与库 Asia/Shanghai 差 15h）。

**死信重试定时器**：

```
startFailedEventRetry()
  failedEventRetryScheduler = newSingleThreadScheduledExecutor("FailedEventRetry-{tableName}")
  scheduleAtFixedRate(() -> {
    if (pendingFailedEvents.isEmpty()) return
    retry = drain all pendingFailedEvents
    for (change : retry)
      try processSingleEvent(change)
      catch: pendingFailedEvents.offer(change)  // 重新入队
  }, 60, 60, SECONDS)  // 初始延迟 60s，每 60s 执行一次
```

**健康检查与空闲等待**：

```
isProcessorHealthy(timeoutMs)
  └── if (!queue.isEmpty() && hasActiveProcessor && (now - lastProcessTime) > timeoutMs)
      hasActiveProcessor = false  // 处理器假死，重置标志
      return false

isIdle()
  └── queue.isEmpty() && !hasActiveProcessor

waitIdle(timeoutMs)  // 阻塞等待本表队列处理完成
  deadline = now + timeoutMs
  while (now < deadline)
    if (isIdle()) return
    Thread.sleep(50)
```

**关闭 close**：

```
close()
  ├── running = false
  ├── failedEventRetryScheduler.shutdownNow()
  ├── processorThread.interrupt()
  └── 尝试 flush 剩余队列
      remaining = queue.drain(batchSize * 4)
      if (!remaining.isEmpty())
        processBatchInternal(remaining)  // 最后一次批量写入
        catch: log.warn("Flush on close failed")
```

#### 4.2.3 数据管线完整流程图

```
SyncHostJob                    DataBaseSinkFunction           TableSinkFunction            目标 MySQL
    |                                |                              |                          |
    | invoke(RowChange)              |                              |                          |
    |------------------------------->|                              |                          |
    |                                | enqueue(RowChange)           |                          |
    |                                |----------------------------->|                          |
    |                                |                              | queue.offer(change)      |
    |                                |                              | (60s超时→丢弃+全量补偿)   |
    |                                |                              |                          |
    |                                |                              | processorLoop:           |
    |                                |                              |  batch = queue.drain(N)  |
    |                                |                              |  processBatchWithWal:    |
    |                                |                              |    attempt 1:            |
    |                                |                              |    BEGIN; upsert×N; COMMIT|
    |                                |                              |   --------------------->| (成功)
    |                                |                              |                          |
    |                                |                              |    (失败) attempt 2-5:   |
    |                                |                              |    sleep(500ms*attempt)  |
    |                                |                              |    重试...               |
    |                                |                              |                          |
    |                                |                              |    (全部失败) degrade:   |
    |                                |                              |    逐条 executeSingle   |
    |                                |                              |    (失败)→死信队列       |
    |                                |                              |                          |
    |                                |                              | 死信定时器(60s):         |
    |                                |                              |  重试 pendingFailedEvents|
    |                                |                              |  (失败)→重新入队         |
    |                                |                              |                          |
    | flush(15s)                     |                              |                          |
    |------------------------------->| waitIdle() per table         |                          |
    |                                |----------------------------->| isIdle() check           |
    |                                |                              |                          |
```

---

### 4.3 DynamicShardedConnectionManager — 动态分片连接池

**源码位置**：`manager/DynamicShardedConnectionManager.java`

#### 4.3.1 职责

基于 `(host:port/dbName)` 维度维护 HikariCP 连接池，通过全局信号量控制最大连接数，防止目标库连接数超限。

#### 4.3.2 核心结构

```
DynamicShardedConnectionManager
├── poolMap: Map<String, HikariDataSource>   // poolKey → 连接池
├── globalSemaphore: Semaphore               // 全局连接数信号量
├── usingConnections: AtomicInteger           // 当前使用中的连接数
└── properties: GeoDRSyncProperties          // 配置(maxPoolSize, maxGlobalConnections)
```

#### 4.3.3 连接池创建

```
poolKey(cfg) = cfg.host + ":" + cfg.port + "/" + cfg.databaseName

getDataSource(cfg)
  └── poolMap.computeIfAbsent(poolKey, k -> {
      HikariConfig hc = new HikariConfig()
      hc.setJdbcUrl(cfg.getDBURL())
      hc.setUsername(cfg.username)
      hc.setPassword(cfg.password)
      hc.setMaximumPoolSize(maxPoolSize)       // 默认 32
      hc.setMinimumIdle(2)
      hc.setPoolName("GeoDRSync-" + poolKey)
      hc.setConnectionTimeout(30000)            // 30s 获取连接超时
      hc.setIdleTimeout(600000)                 // 10min 空闲超时
      hc.setMaxLifetime(1800000)               // 30min 最大生命周期
      return new HikariDataSource(hc)
  })
```

#### 4.3.4 全局信号量限流

```
getConnection(cfg)
  ├── globalSemaphore.acquire()  // 阻塞获取许可（maxGlobalConnections=200）
  ├── try { conn = getDataSource(cfg).getConnection(); usingConnections++ }
  ├── catch (SQLException) { globalSemaphore.release(); throw }  // 获取失败释放许可
  └── return conn

releaseConnection()
  ├── globalSemaphore.release()  // 释放许可
  └── usingConnections--
```

> **设计要点**：连接池按 `(host:port/dbName)` 分片，每个分片独立 HikariCP 池（maxPoolSize=32）。全局信号量（maxGlobalConnections=200）限制所有分片池的并发连接总数，防止目标库 `max_user_connections` 超限。**调用方必须配对调用 `getConnection` → `releaseConnection`**，否则信号量泄漏。

#### 4.3.5 按主机清理连接池（防泄漏修复）

```
removeConnectionPoolsByHost(host, port)
  prefix = host + ":" + port + "/"
  closed = 0
  for (key : new ArrayList<>(poolMap.keySet()))
    if (key.startsWith(prefix))
      ds = poolMap.remove(key)
      ds.close()
      closed++
  return closed
```

> **修复背景**：`removeConnectionPool(cfg)` 仅能移除精确匹配 poolKey 的池，而同步过程中会按真实库名惰性创建 `host:port/dbName` 的分片池。调用 `removeConnectionPool(toSourceHostConfig())` 时 dbName 为空，只能关掉 `host:port/` 这个 key，真正在用的 `host:port/具体库名` 的池不会被清理，导致 mapping 移除后连接池长期驻留，占用 MySQL 连接数。`removeConnectionPoolsByHost` 遍历所有池 key，移除以 `host:port/` 开头的（含空库名和具体库名），彻底清理。

#### 4.3.6 连接池统计

```
getPoolStatistics()
  └── 返回 "poolStats[host:port/db=active/total; ... globalUsed=N/200]"
      └── 用于排查连接泄漏
```

---

### 4.4 DatabaseSyncManager — 同步管理器

**源码位置**：`manager/DatabaseSyncManager.java`

#### 4.4.1 职责

总控调度器：负责启动/停止/重启主机对同步任务，维护内存进度缓存与运行作业，支持页面动态新增/移除同步主机对（持久化到本地文件，重启后自动恢复），配置热生效（update + reload），用户隔离（userId 过滤）。

#### 4.4.2 核心结构

```
DatabaseSyncManager
├── syncProgressMap: static Map<String, SyncProgress>   // 内存进度缓存（sourceHost|sourceDbName → SyncProgress）
├── runningJobs: Map<String, SyncHostJob>               // 运行中的作业（instanceKey → SyncHostJob）
├── dynamicKeys: Set<String>                             // 动态新增的映射 key 集合
├── mappingsLock: Object                                 // 增删映射时的互斥锁
├── DYNAMIC_MAPPINGS_FILE = "geodrsync-dynamic-mappings.json"  // 持久化文件
└── properties: GeoDRSyncProperties                      // 配置（mappings 列表）
```

#### 4.4.3 启动流程

```
@PostConstruct startSyncTask()
  ├── if (!properties.isStandbyMode())
  │   log.warn("This server is not a disaster recovery center, sync service will not start.")
  │   return
  ├── loadDynamicMappings()  // 加载页面动态新增的映射（重启恢复）
  │   └── 读取 geodrsync-dynamic-mappings.json，反序列化为 List<DatabaseMapping>
  │       for (m : loaded)
  │         if (findByKey(m.getInstanceKey()) == null)
  │           properties.getMappings().add(m)
  │           dynamicKeys.add(m.getInstanceKey())
  ├── mappings = properties.getMappings()
  ├── for (mapping : mappings)
  │   key = mapping.getInstanceKey()
  │   if (!started.add(key)) continue  // 去重
  │   try { startHostJob(mapping) }
  │   catch { log.error("Failed to start sync for mapping") }
  └── log.info("Sync task started, running jobs: {}", runningJobs.size())

startHostJob(mapping)
  ├── job = new SyncHostJob(mapping, properties, connMgr, executorMgr, queueMgr, metadataService, syncProgressMap)
  ├── job.start()
  └── runningJobs.put(job.getInstanceKey(), job)
```

#### 4.4.4 主机对 CRUD

```
addMapping(mapping)  // 单个新增
  ├── key = mapping.getInstanceKey()
  ├── synchronized (mappingsLock)
  │   ├── if (findByKey(key) != null) throw "MAPPING_ALREADY_EXISTS:" + key
  │   ├── startHostJob(mapping)
  │   ├── if (!containsByKey(key)) properties.getMappings().add(mapping)
  │   ├── dynamicKeys.add(key)
  │   └── persistDynamicMappings()
  └── return key

addMappings(mappings)  // 批量新增（绑定 userId）
  ├── currentUserId = UserContext.getUserId()
  ├── synchronized (mappingsLock)
  │   for (mapping : mappings)
  │     key = mapping.getInstanceKey()
  │     if (findByKey(key) != null)
  │       result.skipped.add(key)
  │       continue
  │     if (mapping.getUserId() == null)
  │       mapping.setUserId(currentUserId)  // 绑定当前操作用户
  │     startHostJob(mapping)
  │     dynamicKeys.add(key)
  │     result.created.add(key)
  │   persistDynamicMappings()
  └── return result  // MappingBatchResult{created, skipped, failed}

removeMapping(instanceKey)
  ├── synchronized (mappingsLock)
  │   ├── mapping = findByKey(instanceKey)
  │   ├── if (mapping == null) throw "MAPPING_NOT_FOUND:" + instanceKey
  │   ├── job = runningJobs.remove(instanceKey)
  │   ├── if (job != null) job.stop()
  │   ├── connMgr.removeConnectionPoolsByHost(sourceHost, sourcePort)  // 按主机清理源连接池
  │   ├── connMgr.removeConnectionPoolsByHost(targetHost, targetPort)  // 按主机清理目标连接池
  │   ├── properties.getMappings().removeIf(m -> instanceKey.equals(m.getInstanceKey()))
  │   ├── dynamicKeys.remove(instanceKey)
  │   └── persistDynamicMappings()
```

#### 4.4.5 配置热生效（update + reload 双步流程）

```
updateMappingConfig(instanceKey, configUpdate)  // 步骤1: 更新内存配置
  ├── synchronized (mappingsLock)
  │   mapping = findByKey(instanceKey)
  │   if (mapping == null) throw "MAPPING_NOT_FOUND"
  │   // 逐字段更新（仅更新提供的字段，未提供的保持不变）
  │   if (configUpdate.containsKey("ignoreDatabases"))
  │     mapping.setIgnoreDatabases(toStringList(configUpdate.get("ignoreDatabases")))
  │   if (configUpdate.containsKey("ignoreTables"))
  │     mapping.setIgnoreTables(...)
  │   if (configUpdate.containsKey("ignoreDdlTables"))
  │     mapping.setIgnoreDdlTables(...)
  │   if (configUpdate.containsKey("commonIgnoreTables"))
  │     mapping.setCommonIgnoreTables(...)
  │   if (configUpdate.containsKey("commonDdlIgnoreTables"))
  │     mapping.setCommonDdlIgnoreTables(...)
  │   if (configUpdate.containsKey("transformRules"))
  │     mapping.setTransformRules(...)  // 解析 List<Map> → List<TransformRule>
  │   if (configUpdate.containsKey("ignoreTablesByDb"))
  │     mapping.setIgnoreTablesByDb(...)  // 解析 List<Map> → List<DbTableIgnore>
  │   if (configUpdate.containsKey("ignoreDdlTablesByDb"))
  │     mapping.setIgnoreDdlTablesByDb(...)
  │   // 动态 mapping 持久化到 JSON 文件
  │   if (changed && dynamicKeys.contains(instanceKey))
  │     persistDynamicMappings()
  └── return changed

reloadMapping(instanceKey)  // 步骤2: 重建作业让新配置生效
  ├── synchronized (mappingsLock)
  │   mapping = findByKey(instanceKey)
  │   if (mapping == null) throw "MAPPING_NOT_FOUND"
  │   // 1. 停止旧作业
  │   oldJob = runningJobs.remove(instanceKey)
  │   if (oldJob != null) oldJob.stop()
  │   // 2. 清理旧连接池（含按库名分片的池）
  │   connMgr.removeConnectionPoolsByHost(sourceHost, sourcePort)
  │   connMgr.removeConnectionPoolsByHost(targetHost, targetPort)
  │   // 3. 用最新 mapping 对象重建作业
  │   startHostJob(mapping)
  │   // 新作业的 watermarks/knownSignatures 从空白开始（等价于 fullReset）
  │   // 下一轮扫描自动全量重扫并重建水位，依靠幂等 upsert 保证数据最终一致
  └── return true
```

> **设计要点**：配置热生效分两步：
> 1. `updateMappingConfig`：仅更新内存中的 mapping 对象字段，不影响运行中的作业
> 2. `reloadMapping`：停止旧作业 → 清理旧连接池 → 用最新 mapping 创建新 SyncHostJob 并启动
>
> 新作业的 watermarks/knownSignatures 从空白开始，等价于 fullReset 效果，下一轮自动全量重扫，依靠幂等 upsert 保证数据最终一致。无需重启服务。

#### 4.4.6 用户隔离（userId 过滤）

```
listMappingsVO(userId)
  └── properties.getMappings().stream()
      .filter(m -> userId == null              // 不过滤（管理员）
              || m.getUserId() == null          // 全局/yml 配置的映射，所有用户可见
              || userId.equals(m.getUserId()))  // 自己创建的映射
      .map(m -> DatabaseMappingVO.from(m,
          runningJobs.containsKey(m.getInstanceKey()),  // 运行状态
          dynamicKeys.contains(m.getInstanceKey()) ? "dynamic" : "yaml"))  // 来源
      .collect(Collectors.toList())
```

> **设计要点**：
> - `userId == null` 的映射：来自 yml 配置，全局可见
> - `userId == 当前用户` 的映射：通过页面动态新增时自动绑定
> - 用户只能看到全局映射和自己创建的映射
> - `DatabaseMappingVO.from` 对密码脱敏（`******`）

#### 4.4.7 全量重置 resyncDatabases

```
resyncDatabases(databaseList)
  for (q : databaseList)
    mapping = findMappingByIp(q.getIp())  // 按源/目标 IP 匹配主机对
    if (mapping == null) failed.add(q); continue
    job = runningJobs.get(mapping.getInstanceKey())
    if (job == null) startHostJob(mapping)
    else job.fullReset()  // stop → clear → start
    success.add(q)
  return {success, failed}
```

#### 4.4.8 动态映射持久化

```
persistDynamicMappings()
  toSave = properties.getMappings().stream()
      .filter(m -> dynamicKeys.contains(m.getInstanceKey()))
      .collect(Collectors.toList())
  objectMapper.writerWithDefaultPrettyPrinter()
      .writeValue(new File("geodrsync-dynamic-mappings.json"), toSave)

loadDynamicMappings()  // 启动时恢复
  f = new File("geodrsync-dynamic-mappings.json")
  if (!f.exists()) return
  loaded = objectMapper.readValue(f, new TypeReference<List<DatabaseMapping>>(){})
  for (m : loaded)
    if (findByKey(m.getInstanceKey()) == null)
      properties.getMappings().add(m)
      dynamicKeys.add(m.getInstanceKey())
```

#### 4.4.9 关闭 shutdown

```
@PreDestroy shutdown()
  ├── runningJobs.values().forEach(SyncHostJob::stop)
  ├── runningJobs.clear()
  ├── queueManager.TABLE_QUEUES.forEach((k, q) -> q.clear())
  ├── queueManager.TABLE_QUEUES.clear()
  ├── executorManager.shutdownAll()
  └── connMgr.shutdownAll()
```

---

### 4.5 DatabaseMetadataService — 元数据服务

**源码位置**：`service/DatabaseMetadataService.java`

#### 4.5.1 职责

用户库发现、表结构提取、目标库建表（镜像源库 DDL）、DDL 跨版本清洗。

#### 4.5.2 库发现

```
SYSTEM_SCHEMAS = {information_schema, mysql, performance_schema, sys, geodrsync}

getAllUserDatabases(cfg, ignorePatterns)
  ├── SQL: SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
  ├── for (db : result)
  │   if (SYSTEM_SCHEMAS.contains(db.toLowerCase())) skip
  │   if (MatchPattern.matchesAny(ignorePatterns, db)) skip  // 支持精确/glob/re:正则
  └── return userDatabases
```

#### 4.5.3 表扫描

```
listTables(cfg, dbName)
  └── SQL: SELECT TABLE_NAME FROM information_schema.TABLES
           WHERE TABLE_SCHEMA='{dbName}' AND TABLE_TYPE='BASE TABLE'
      └── 仅返回基础表，不含视图
```

#### 4.5.4 表结构提取

```
getTableInfo(cfg, dbName, tableName)
  ├── 列查询: SELECT COLUMN_NAME FROM information_schema.COLUMNS
  │          WHERE TABLE_SCHEMA='{dbName}' AND TABLE_NAME='{tableName}' ORDER BY ORDINAL_POSITION
  ├── 主键查询: SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
  │            WHERE TABLE_SCHEMA='{dbName}' AND TABLE_NAME='{tableName}'
  │            AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION
  └── return new TableInfo(tableName, pk, columns)
```

#### 4.5.5 DDL 清洗（8.0 → 5.7 跨版本兼容）

```
getCreateTableSql(cfg, tableName)
  ├── SQL: SHOW CREATE TABLE `tableName`
  ├── ddl = rs.getString(2)  // 第二列是建表语句
  ├── 去掉库限定符: ddl.replaceAll("`[^`]+`\\s*\\.", "")
  ├── 改为 IF NOT EXISTS: ddl.replaceFirst("(?i)^CREATE TABLE", "CREATE TABLE IF NOT EXISTS")
  ├── sanitizeDdlForPortability(ddl)  // 排序规则降级
  └── return ddl

sanitizeDdlForPortability(ddl)
  └── ddl.replaceAll("(?i)utf8mb4_0900_\\w+", "utf8mb4_general_ci")
      └── utf8mb4_0900_ai_ci / utf8mb4_0900_as_cs / utf8mb4_0900_bin → utf8mb4_general_ci
```

> **设计要点**：MySQL 8.0 默认排序规则 `utf8mb4_0900_ai_ci` 在 5.7 上不存在，直接复制源表 DDL 会抛 "Unknown collation"。正则 `utf8mb4_0900_\w+` 统一降级为两版本通用的 `utf8mb4_general_ci`，保证同一 DDL 可在高低版本目标库上均能建表。

#### 4.5.6 目标库/表确保

```
ensureTargetDatabase(targetHost, dbName)
  ├── if (databaseExists(targetHost, dbName)) return true
  ├── SQL: CREATE DATABASE IF NOT EXISTS `dbName` DEFAULT CHARACTER SET utf8mb4
  └── return success

ensureTargetTable(sourceDB, targetDB, tableName)
  ├── if (tableExists(targetDB, tableName)) return true
  ├── ddl = getCreateTableSql(sourceDB, tableName)  // 含 DDL 清洗
  ├── if (ddl == null) return false
  ├── 执行: st.execute(ddl)
  └── return success

dropTargetTable(targetDB, tableName)
  └── SQL: DROP TABLE IF EXISTS `tableName`
```

---

### 4.6 认证体系：AuthController + AuthInterceptor + UserContext + OperationLogAspect

**源码位置**：`controller/AuthController.java` + `manager/AuthInterceptor.java` + `common/UserContext.java` + `common/OperationLogAspect.java`

#### 4.6.1 认证架构

```
请求 → TraceIdFilter → AuthInterceptor.preHandle()
                         ├── OPTIONS 请求直接放行（CORS 预检）
                         ├── 检查 session.uid
                         ├── 已登录: UserContext.set(uid, username, clientIp) → 放行
                         └── 未登录: 返回 401 JSON {code:"2807003003", message:"未登录或登录已过期"}
      → Controller 方法
      → (如有 @OperationLogAnnotation) OperationLogAspect.around()
      → AuthInterceptor.afterCompletion()
        └── UserContext.clear()  // 清理 ThreadLocal，防止内存泄漏
```

#### 4.6.2 AuthController — 认证控制器

**拦截配置**（`WebConfig.java`）：
- 拦截：`/sync/**`, `/operation-log/**`
- 放行：`/auth/login`, `/auth/register`, `/auth/me`, `/static/**`, `/error`, `/favicon.ico`
- 开关：`app.auth.enabled`（默认 true，测试环境置 false）

```
POST /auth/register  (无需登录)
  ├── 校验: username(3-32位), password(>=6位)
  ├── 检查: 用户名唯一
  ├── 创建: SysUser{username, salt=randomSalt(), passwordHash=SHA-256(salt+pwd)}
  ├── @OperationLogAnnotation(type="REGISTER", desc="用户注册")
  └── return Result.success()

POST /auth/login  (无需登录)
  ├── 校验: username + password 非空
  ├── 查询: SysUser by username
  ├── 验证: PasswordUtil.matches(password, passwordHash)
  ├── 成功:
  │   ├── session.setAttribute("uid", u.id)
  │   ├── session.setAttribute("username", u.username)
  │   ├── session.setMaxInactiveInterval(8h)  // 8小时过期
  │   ├── UserContext.set(uid, username, clientIp)  // 为 AOP 设置
  │   └── return {username}
  ├── @OperationLogAnnotation(type="LOGIN", desc="用户登录")
  └── 失败: throw BusinessException(AUTH_FAIL, "用户名或密码错误")

POST /auth/logout  (无需登录)
  ├── session = request.getSession(false)
  ├── 在 invalidate 前设置 UserContext（供 AOP 记录操作者）
  │   ├── uid = session.getAttribute("uid")
  │   ├── username = session.getAttribute("username")
  │   └── UserContext.set(uid, username, clientIp)
  ├── session.invalidate()
  ├── @OperationLogAnnotation(type="LOGOUT", desc="用户登出")
  └── return Result.success()

GET /auth/me  (无需登录，永远返回 200)
  ├── session = request.getSession(false)
  ├── 未登录: return {loggedIn: false}
  └── 已登录: return {loggedIn: true, username: ...}
  └── 设计: 用响应体字段表达登录态，避免浏览器首屏探测时打印 401 控制台噪声
```

#### 4.6.3 AuthInterceptor — 会话鉴权拦截器

```
preHandle(request, response, handler)
  ├── if ("OPTIONS".equalsIgnoreCase(method)) return true  // CORS 预检放行
  ├── session = request.getSession(false)
  ├── if (session != null && session.getAttribute("uid") != null)
  │   ├── uid = (Long) session.getAttribute("uid")
  │   ├── username = (String) session.getAttribute("username")
  │   ├── clientIp = IpUtils.getClientIp(request)
  │   ├── UserContext.set(uid, username, clientIp)  // 填充 ThreadLocal
  │   └── return true
  └── 未登录:
      ├── response.setStatus(401)
      ├── response.setContentType("application/json;charset=UTF-8")
      ├── response.getWriter().write({"code":"2807003003","message":"未登录或登录已过期","success":false})
      └── return false

afterCompletion(request, response, handler, ex)
  └── UserContext.clear()  // 清理 ThreadLocal，防止内存泄漏
```

#### 4.6.4 UserContext — 用户上下文（ThreadLocal）

```
UserContext (ThreadLocal)
├── userId: Long
├── username: String
├── clientIp: String
├── set(userId, username, clientIp)  // 设置当前线程上下文
├── getUserId() / getUsername() / getClientIp()  // 获取
└── clear()  // 清除（防止内存泄漏）
```

**填充时机**：
- `/sync/**` 接口：`AuthInterceptor.preHandle()` 在方法前设置
- `/auth/**` 接口：`login/register/logout` 方法内部设置
- `afterCompletion` 中清理

#### 4.6.5 OperationLogAspect — 操作日志 AOP 切面

```
@Around("@annotation(operationLogAnnotation)")
around(joinPoint, operationLogAnnotation)
  ├── startTime = now
  ├── opLog = new OperationLog()
  │   ├── operationType = annotation.type()    // LOGIN/LOGOUT/REGISTER/RESYNC/MAPPING_ADD...
  │   ├── operationDesc = annotation.desc()
  │   ├── createTime = now
  │   ├── requestUrl = request.getRequestURI()
  │   ├── requestMethod = request.getMethod()
  │   └── requestParams = truncate(buildParams(joinPoint), 2000)  // 密码脱敏后截断
  │
  ├── try
  │   result = joinPoint.proceed()
  │   opLog.resultStatus = "SUCCESS"
  │   return result
  ├── catch (Throwable e)
  │   opLog.resultStatus = "FAILURE"
  │   opLog.errorMsg = truncate(e.getMessage(), 1000)
  │   throw e
  └── finally
      ├── opLog.durationMs = now - startTime
      ├── 在 finally 中读取 UserContext（覆盖 /sync 和 /auth 两种场景）
      │   ├── opLog.userId = UserContext.getUserId()
      │   ├── opLog.username = UserContext.getUsername()
      │   └── opLog.clientIp = UserContext.getClientIp() ?? IpUtils.getClientIp(request)
      └── operationLogMapper.insert(opLog)  // 写入 operation_log 表（不影响主流程）
```

**密码脱敏**：

```
PASSWORD_FIELD_PATTERN = Pattern.compile(
    "(\"[^\"]*(?:password|passwd)[^\"]*\"\\s*:\\s*)\"[^\"]*\"",
    Pattern.CASE_INSENSITIVE)

buildParams(joinPoint)
  ├── 收集方法参数（跳过 HttpServletRequest/Response/Session/MultipartFile/byte[]）
  ├── 序列化为 JSON
  └── PASSWORD_FIELD_PATTERN.matcher(json).replaceAll("$1\"***\"")
      └── 所有含 password/passwd 的字段值统一替换为 "***"
```

**支持的审计操作类型**：

| type | desc | 接口 |
|------|------|------|
| REGISTER | 用户注册 | POST /auth/register |
| LOGIN | 用户登录 | POST /auth/login |
| LOGOUT | 用户登出 | POST /auth/logout |
| RESYNC | 全量重置同步 | POST /sync/resyncDatabases |
| TEST_CONNECTION | 测试数据库连接 | POST /sync/mapping/test |
| MAPPING_ADD | 新增同步映射 | POST /sync/mapping/add |
| MAPPING_REMOVE | 移除同步映射 | POST /sync/mapping/remove |
| MAPPING_RELOAD | 重载映射配置 | POST /sync/mapping/reload |
| MAPPING_UPDATE | 更新映射配置 | POST /sync/mapping/update |

---

### 4.7 TableLevelQueueManager + GroupedExecutorManager — 队列与线程池

#### 4.7.1 TableLevelQueueManager — 表级队列管理器

**源码位置**：`manager/TableLevelQueueManager.java`

```
TableLevelQueueManager
├── TABLE_QUEUES: ConcurrentHashMap<String, TableQueue>  // key = instanceKey.tableName
├── getOrCreateTableQueue(key, maxSize) → computeIfAbsent
├── getTableQueue(key)
├── removeTableQueue(key) → clear + remove
└── removeAllQueuesForDatabase(instanceKeyPrefix)
    └── 遍历所有 key，移除以 instanceKeyPrefix 开头的（DDL 删库时批量清理）

TableQueue (内部类)
├── queue: LinkedBlockingQueue<Object>  // 有界阻塞队列
├── maxSize: int = max(100, maxSize)    // 默认 20000
├── offer(e) → queue.offer(e)           // 非阻塞入队，满返回 false（反压）
├── drain(maxElements) → queue.drainTo(batch, maxElements)  // 批量抽取
├── size() / isEmpty() / clear()
└── poll(timeout, unit) → queue.poll()  // 阻塞等待取出（健康检查用）
```

#### 4.7.2 GroupedExecutorManager — 分组线程池管理器

**源码位置**：`manager/GroupedExecutorManager.java`

```
GroupedExecutorManager
├── EXECUTOR_GROUPS: Map<String, ExecutorService>  // groupKey → ThreadPoolExecutor
├── getGroupedExecutor(groupKey, parallelism)
  └── computeIfAbsent(groupKey, k -> {
      core = max(1, parallelism)
      return new ThreadPoolExecutor(core, core, 0L, MILLISECONDS,
          new LinkedBlockingQueue<>(1024),
          new NamedThreadFactory("geo-sync-" + counter.incrementAndGet()),
          new CallerRunsPolicy())  // 队列满时调用者线程执行
  })
├── shutdownGroup(groupKey)
└── shutdownAll()

NamedThreadFactory
├── prefix: "geo-sync-N"
├── newThread(r) → Thread(prefix + "-" + seq, daemon=true)
```

---

### 4.8 ScanNewDatabaseJob — 新库自动发现

**源码位置**：`job/ScanNewDatabaseJob.java`

```
@Scheduled(fixedDelay = 60000)  // 60 秒固定延迟
scan()
  ├── if (properties.getMappings() == null) return
  └── for (mapping : properties.getMappings())
      try
        dbs = metadataService.getAllUserDatabases(mapping.toSourceHostConfig(), mapping.getIgnoreDatabases())
        log.debug("ScanNewDatabaseJob: source={}, databases={}", mapping.getSourceIP(), dbs)
      catch
        log.warn("ScanNewDatabaseJob failed for {}", mapping.getSourceIP())
```

> **设计要点**：此 Job 主要用于日志记录和辅助发现。实际的新库同步由 `SyncHostJob.doSync()` 每 2 秒轮询时自动发现并纳入同步（`getAllUserDatabases` 包含新库 → `ensureTargetDatabase` 建库 → 同步表）。ScanNewDatabaseJob 的 60 秒间隔是一个补充的监控点，便于运维通过日志发现新库。

---

### 4.9 SyncProgressJob — 进度更新与持久化

**源码位置**：`job/SyncProgressJob.java` + `service/impl/SyncProgressServiceImpl.java`

#### 4.9.1 定时持久化

```
@Scheduled(fixedDelay = 10000)  // 10 秒固定延迟
persistProgress()
  └── syncProgressService.persistFromMemory()

persistFromMemory()
  └── for (mem : DatabaseSyncManager.syncProgressMap.values())
      try
        existing = getOne(eq(SOURCE_IP, mem.sourceIp).eq(SOURCE_DB_NAME, mem.sourceDbName))
        if (existing == null) save(mem)           // 新增
        else mem.setId(existing.id); updateById(mem)  // 更新
      catch
        log.warn("Persist progress failed for {}|{}", sourceIp, sourceDbName)
```

#### 4.9.2 进度更新流程

```
SyncHostJob.updateProgress(db, tableCount, sourceRows, targetRows)
  ├── key = sourceHost + "|" + db
  ├── p = progressMap.computeIfAbsent(key, k -> new SyncProgress())
  │   ├── sourceIp = mapping.getSourceHost()
  │   ├── sourceDbName = db
  │   ├── targetIp = mapping.getTargetHost()
  │   ├── targetDbName = db
  │   ├── syncStartTime = now
  │   └── state = FULL_SYNC (初始)
  ├── p.state = SYNCING
  ├── p.sourceBinlogFile = "LOCAL-SYNC"  // 本地引擎标记
  ├── p.sourceBinlogTime = now
  ├── p.syncBinlogFile = "LOCAL-SYNC"
  ├── p.syncBinlogTime = now
  ├── p.deviationTimes = 0
  ├── p.deviationStatus = NORMAL (1)
  ├── p.suspensionReason = null
  ├── p.processingMethod = "tables=N,src=N,tgt=N"  // 本轮同步统计
  └── p.updateTime = now
```

> **设计要点**：进度先写入内存缓存 `syncProgressMap`（ConcurrentHashMap，高性能），由 `SyncProgressJob` 每 10 秒异步持久化到 `sync_progress` 表。这样同步引擎的高频进度更新不会受数据库 IO 影响。

#### 4.9.3 总状态查询

```
status()
  ├── 查询 state IN (INVALID=0, SUSPENDED=3) 的记录，按 updateTime ASC 取第一条
  ├── if (存在异常记录)
  │   return {status: ABNORMAL, desc: "异常", firstExceptionTime: updateTime}
  └── else
      return {status: NORMAL, desc: "正常", firstExceptionTime: ""}
```

---

## 第五章 API 接口设计

### 5.1 统一响应格式

所有接口返回 JSON，格式如下：

```json
{
  "code": "0",
  "message": "成功",
  "success": true,
  "traceId": "a1b2c3d4e5f6",
  "data": { ... }
}
```

分页响应 `PageRespVO<T>`：

```json
{
  "total": 100,
  "nextPage": 2,
  "results": [ ... ]
}
```

### 5.2 鉴权接口（/auth/**）

| 方法 | 路径 | 鉴权 | 请求体 | 响应 | 说明 |
|------|------|------|--------|------|------|
| POST | /auth/register | 否 | `{username, password}` | `Result<Void>` | 注册（用户3-32位，密码≥6位） |
| POST | /auth/login | 否 | `{username, password}` | `Result<{username}>` | 登录（写入HttpSession，8h过期） |
| POST | /auth/logout | 否 | — | `Result<Void>` | 登出（session.invalidate） |
| GET | /auth/me | 否 | — | `Result<{loggedIn:bool, username?}>` | 登录态探测（永远200） |

**登录请求/响应示例**：

```http
POST /auth/login
Content-Type: application/json

{"username": "admin", "password": "admin123"}
```

```json
{
  "code": "0",
  "message": "成功",
  "success": true,
  "traceId": "a1b2c3d4",
  "data": {"username": "admin"}
}
```

> 登录成功后，服务端通过 `Set-Cookie: JSESSIONID=xxx; HttpOnly` 写入会话 Cookie，后续请求浏览器自动携带。

### 5.3 同步管理接口（/sync/**，全部需登录）

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | /sync/db/list | `SyncProgressPageQuery` | `PageRespVO<SyncProgressVO>` | 分页查询同步进度 |
| GET | /sync/status | — | `{status, desc, firstExceptionTime}` | 同步总状态（NORMAL/ABNORMAL） |
| GET | /sync/ipList | — | `[{ip, type}]` | IP 节点列表（生产/灾备） |
| GET | /sync/databases/{ips} | — | `["库名",...]` | 按 IP 获取数据库列表 |
| GET | /sync/{id} | — | `SyncProgressVO` | 同步详情 |
| POST | /sync/resyncDatabases | `[{ip, dbName}]` | `{success:[...], failed:[...]}` | 批量重新同步（全量重置） |
| POST | /sync/mapping/test | `SyncMappingRequestDTO` | `{source:ConnectionTestResult, target:...}` | 连接测试 |
| POST | /sync/sourceDatabases | `SyncMappingRequestDTO` | `["库名",...]` | 加载源库列表（排除系统库） |
| POST | /sync/sourceTables | `SourceTablesRequestDTO` | `["表名",...]` | 加载源表列表 |
| POST | /sync/mapping/add | `SyncMappingRequestDTO` | `MappingBatchResult` | 批量新增主机对映射 |
| GET | /sync/mappings | — | `List<DatabaseMappingVO>` | 查询所有映射（userId 过滤） |
| POST | /sync/mapping/remove | `{instanceKey}` | `Result<Void>` | 移除映射 |
| POST | /sync/mapping/reload | `{instanceKey}` | `{instanceKey, reloaded}` | 重载配置（热生效步骤2） |
| POST | /sync/mapping/update | `{instanceKey, ignoreDatabases?, ...}` | `{instanceKey, updated}` | 更新配置（热生效步骤1） |

### 5.4 请求/响应 DTO 详解

#### SyncProgressPageQuery（分页查询）

```json
{
  "page": 1,
  "pageSize": 20,
  "condition": {
    "ip": "127.0.0.1",
    "sourceDbName": "geo",
    "state": 2,
    "deviationStatus": 1
  },
  "order": "updateTime desc"
}
```

#### SyncMappingRequestDTO（新增映射请求）

```json
{
  "sourceHost": "127.0.0.1",
  "sourcePort": 3306,
  "sourceUser": "root",
  "sourcePassword": "123456",
  "targetHost": "192.168.88.88",
  "targetPort": 3306,
  "targetUser": "root",
  "targetPassword": "123456",
  "sourceDatabases": ["geo_source", "biz_source"],
  "ignoreDatabases": ["test_*"],
  "ignoreTables": [],
  "ignoreDdlTables": [],
  "commonIgnoreTables": [],
  "commonDdlIgnoreTables": [],
  "ignoreTablesByDb": [{"database": "geo_source", "tables": ["t_temp_*"]}],
  "ignoreDdlTablesByDb": [],
  "transformRules": [{"dbName": "geo_source", "tableName": "t_order", "fieldName": "remark", "sourceValue": "prod", "targetValue": "dr"}]
}
```

> `sourceDatabases` 可选：为空则扇出为 1 个主机对映射（同步所有用户库）；非空则扇出为 N 个映射（每个库一个，但实际实现中主机对是 1:1，sourceDatabases 用于预检和日志）

#### MappingBatchResult（批量新增结果）

```json
{
  "created": ["127.0.0.1->192.168.88.88"],
  "skipped": ["10.0.0.1->10.0.0.2"],
  "failed": ["10.0.0.3->10.0.0.4: Connection refused"]
}
```

#### DatabaseMappingVO（映射展示对象，密码脱敏）

```json
{
  "instanceKey": "127.0.0.1->192.168.88.88",
  "sourceHost": "127.0.0.1",
  "sourcePort": 3306,
  "sourceUser": "root",
  "sourcePassword": "******",
  "targetHost": "192.168.88.88",
  "targetPort": 3306,
  "targetUser": "root",
  "targetPassword": "******",
  "ignoreDatabases": ["day02"],
  "running": true,
  "source": "yaml",
  "userId": null
}
```

#### ConnectionTestResult（连接测试结果）

```json
{
  "ok": true,
  "message": "连接成功",
  "latencyMs": 15
}
```

### 5.5 错误码对照表

| 错误码 | 枚举名 | HTTP | 含义 | 可重试 |
|--------|--------|------|------|--------|
| 0 | SUCCESS | 200 | 成功 | — |
| 2807002001 | PARAM_ERROR | 400 | 参数错误 | 否 |
| 2807002003 | DATA_NOT_FOUND | 404 | 数据不存在 | 否 |
| 2807002011 | MAPPING_ADD_FAILED | 500 | 新增映射失败 | 否 |
| 2807002012 | DB_CONNECTION_TEST_FAILED | 400 | 连接测试未通过 | 否 |
| 2807002013 | MAPPING_ALREADY_EXISTS | 400 | 映射已存在 | 否 |
| 2807002014 | MAPPING_REMOVE_NOT_FOUND | 404 | 映射不存在 | 否 |
| 2807003001 | AUTH_FAIL | 401 | 用户/密码错误 | 否 |
| 2807003002 | AUTH_DUPLICATE | 409 | 用户已存在 | 否 |
| 2807003003 | AUTH_REQUIRED | 401 | 未登录 | 否 |
| 2807005001 | BATCH_COMMIT_FAILED | 500 | 批量提交异常 | 是 |
| 2807005007 | DATA_SYNC_TO_TARGET_FAILED | 500 | 同步到目标异常 | 是 |
| 2807005008 | SYNC_ENGINE_ERROR | 500 | 同步引擎内部错误 | 是 |

### 5.6 核心流程时序图

#### 5.6.1 主机对同步全流程

```
DatabaseSyncManager        SyncHostJob           DatabaseMetadataService    源MySQL     DataBaseSinkFunction   TableSinkFunction     目标MySQL
      |                        |                        |                    |                |                      |                    |
      | startHostJob(mapping)  |                        |                    |                |                      |                    |
      |----------------------->|                        |                    |                |                      |                    |
      |                        | start()                |                    |                |                      |                    |
      |                        | scheduler.scheduleAtFixedRate(safeSync,0,2s)|            |                      |                    |
      |                        |                        |                    |                |                      |                    |
      |                 loop 每2秒 doSync()             |                    |                |                      |                    |
      |                        | getAllUserDatabases()  |                    |                |                      |                    |
      |                        |----------------------->|                    |                |                      |                    |
      |                        |                        | SHOW DATABASES     |                |                      |                    |
      |                        |                        |------------------->|                |                      |                    |
      |                        |                        |<-------------------|                |                      |                    |
      |                        |<-----------------------| [库名列表]          |                |                      |                    |
      |                        |                        |                    |                |                      |                    |
      |                   for each db                   |                    |                |                      |                    |
      |                        | ensureTargetDatabase() |                    |                |                      |                    |
      |                        |----------------------->|                    |                |                      |                    |
      |                        |                        | CREATE DATABASE    |                |                      |                    |
      |                        |                        |---------------------------------------------->|       |                    |
      |                        |                        |<----------------------------------------------|       |                    |
      |                        | listTables()           |                    |                |                      |                    |
      |                        |----------------------->|                    |                |                      |                    |
      |                        |                        | information_schema |                |                      |                    |
      |                        |                        |------------------->|                |                      |                    |
      |                        |<-----------------------| [表名列表]          |                |                      |                    |
      |                        |                        |                    |                |                      |                    |
      |                   for each table                |                    |                |                      |                    |
      |                        | detectAndApplyAlter()  |                    |                |                      |                    |
      |                        | (指纹对比,ALTER则重建)  |                    |                |                      |                    |
      |                        | ensureTable()          |                    |                |                      |                    |
      |                        |----------------------------------------------------->|                      |                    |
      |                        |                        |                    |                | getTableInfo         |                    |
      |                        |                        |                    |                | ensureTargetTable    |                    |
      |                        |                        |                    |                | (CREATE TABLE IF NOT) |                   |
      |                        |                        |                    |                |------------------------------------>|       |
      |                        |<----------------------------------------------------------------|                      |       |
      |                        |                        |                    |                |                      |                    |
      |                        | syncTable()            |                    |                |                      |                    |
      |                        | (全量streamScan/增量selectChanged)          |                |                      |                    |
      |                        | SELECT * FROM ...      |                    |                |                      |                    |
      |                        |------------------------------------------->|                |                      |                    |
      |                        |<-------------------------------------------| [rows]          |                      |                    |
      |                        | for each row:           |                    |                |                      |                    |
      |                        |   applyTransforms()    |                    |                |                      |                    |
      |                        |   invoke(RowChange)    |                    |                |                      |                    |
      |                        |----------------------------------------------------->| enqueue(RowChange)  |                    |
      |                        |                        |                    |                |                      | queue.offer()     |
      |                        |                        |                    |                |                      | processorLoop:    |
      |                        |                        |                    |                |                      |  drain(batch)     |
      |                        |                        |                    |                |                      |  processBatchWithWal|
      |                        |                        |                    |                |                      |  BEGIN;upsert×N;COMMIT
      |                        |                        |                    |                |                      |------------------>|
      |                        |                        |                    |                |                      |<------------------|
      |                        |                        |                    |                |                      |                    |
      |                        | reconcileDeletes()     |                    |                |                      |                    |
      |                        | (分页PK对账,DELETE)     |                    |                |                      |                    |
      |                        | flush(15s)             |                    |                |                      |                    |
      |                        |----------------------------------------------------->| waitIdle()          |                    |
      |                        | updateProgress()       |                    |                |                      |                    |
      |                        | (写内存syncProgressMap) |                    |                |                      |                    |
```

#### 5.6.2 数据写入 WAL 重试与死信流程

```
TableSinkFunction         TableQueue           目标MySQL
      |                       |                    |
      | enqueue(change)       |                    |
      |---------------------->|                    |
      | (60s超时→丢弃)         |                    |
      |                       |                    |
      | processorLoop:        |                    |
      | drain(batchSize)      |                    |
      |<----------------------| [List<RowChange>]  |
      |                       |                    |
      | processBatchWithWal:  |                    |
      |  attempt 1:           |                    |
      |  BEGIN;               |                    |
      |  for change: executeOne|                   |
      |  COMMIT               |                    |
      |---------------------->| (成功)              |
      |                       |                    |
      |  (失败 SQLException)   |                    |
      |  sleep(500ms*1)       |                    |
      |  attempt 2:           |                    |
      |  重试...              |                    |
      |                       |                    |
      |  (5次全部失败)         |                    |
      |  degradeToSingle:     |                    |
      |  for change:          |                    |
      |    processSingleEvent |                    |
      |---------------------->| (成功)              |
      |    (失败)→死信队列     |                    |
      |    pendingFailedEvents.offer(change)       |
      |    (死信上限100000→丢弃)                    |
      |                       |                    |
      | 死信定时器(每60s):     |                    |
      |  retry = drain pendingFailedEvents         |
      |  for change: processSingleEvent            |
      |  (失败)→重新入队       |                    |
```

---

## 第六章 前端设计

### 6.1 技术栈与构建

| 项 | 说明 |
|----|------|
| 框架 | React 18.3.1 UMD（无 Node 构建工具链） |
| 转译 | @babel/standalone 7.29.7 构建期预编译 JSX → 经典脚本 |
| 样式 | 纯 CSS，内联于 index.html |
| 加载方式 | CDN unpkg.com 加载 react/react-dom UMD |
| 缓存策略 | app.js 内容 MD5 戳版本号 `?v=<hash>` + `no-store` |
| 响应式 | @media 768px / 480px 双断点 |
| 部署 | 编译后随 Spring Boot 打包到 `static/` 目录，后端直接托管 |

### 6.2 SPA 架构

单页面应用，通过 Tab 切换，无路由库。所有状态通过 React `useState` + `useRef` 管理，无外部状态库。

```
index.html
├── <head>
│   ├── CSS 样式（内联，约 143 行）
│   └── react@18.3.1 UMD CDN 引用
└── <body>
    ├── <div id="root"></div>
    └── <script src="static/app.js?v=<hash>"></script>  ← 预编译后的经典脚本

app.js (构建产物)
├── React.createElement(...) 调用（非 JSX，构建期预编译）
├── App 组件（根组件）
├── 子组件：登录页、管理主页、Tab 切换、统计监控、配置中心
└── API 调用封装：fetch() + Cookie 自动携带
```

### 6.3 页面结构

```
登录页 (authed=false)
  ├── 登录表单 (username + password)
  └── 注册切换链接

管理主页 (authed=true)
  ├── 顶栏
  │   ├── logo "GeoDRSync"
  │   ├── baseUrl 配置输入 (默认 http://127.0.0.1:8080)
  │   ├── 当前用户名
  │   └── 退出按钮
  ├── Tab: 统计监控 (activeTab='stats')
  │   ├── 同步总览卡片 (状态/主机对数/异常时间)
  │   ├── 节点与数据库列表
  │   │   ├── IP 列表 (生产中心/灾备中心标签)
  │   │   └── IP → 库展开面板
  │   ├── 同步进度分页列表
  │   │   ├── 工具栏 (筛选: IP/库名/状态/偏差状态 + 搜索/重置)
  │   │   ├── 数据表 (全选/分列/状态标签/偏差标签)
  │   │   ├── 分页器
  │   │   └── "重新同步" 批量按钮 (多选+确认弹窗)
  │   └── 详情抽屉 (右侧滑出)
  │       └── 属性表 (ID/IP/库/状态/binlog/原因/处理方法)
  └── Tab: 配置中心 (activeTab='config')
      ├── 配置方式切换 (表单/JSON)
      ├── 表单模式
      │   ├── PairCard × N (主机对配置卡片)
      │   │   ├── 源主机配置区 (host/port/user/password)
      │   │   ├── 目标主机配置区 (host/port/user/password)
      │   │   ├── 忽略整库 (多选下拉,支持正则)
      │   │   ├── 按库忽略表 (DML+DDL全忽略)
      │   │   ├── 按库忽略表 (仅DDL忽略)
      │   │   ├── 通用忽略表 (DML+DDL / 仅DDL)
      │   │   ├── 字段转换规则 (可折叠)
      │   │   ├── [测试连接] [加载源库] [加载源表]
      │   │   └── [保存并开始同步]
      │   ├── [+ 添加主机对] 按钮
      │   └── 已配置主机对列表 (table, 含运行状态/来源/移除按钮)
      └── JSON 模式
          ├── <textarea> 编辑 JSON
          ├── 错误提示
          └── [从表单生成JSON] / [从JSON加载到表单]
```

### 6.4 状态管理

所有状态通过 React `useState` + `useRef` 管理，无外部状态库。

```javascript
// 全局状态
baseUrl        // "http://127.0.0.1:8080" (可配置)
authed         // checkAuth() 后置 (false=显示登录页)
currentUser    // /auth/me 返回的用户名
activeTab      // 'stats' | 'config'

// 统计监控 Tab
list           // SyncProgressVO[] (进度列表)
total          // 总条数
page, pageSize // 分页
query          // {ip, sourceDbName, state, deviationStatus} (筛选条件)
selectedIds    // 选中行 ID 集合
drawer/current // 详情抽屉数据
status         // {status, desc, firstExceptionTime} (总状态)
ipList         // [{ip, type}] (节点列表)
databases      // [库名] (当前展开IP的数据库列表)
currentIp      // 当前展开的 IP

// 配置中心 Tab
hostPairs      // [{sourceHost, targetHost, ...}] 表单数组
mappingsList   // [{instanceKey, sourceHost, ...}] 已配置列表
configMode     // 'form' | 'json'
configJson     // JSON 字符串
adding         // 保存中锁 (防重复提交)

// 轮询定时器
// 已登录 + stats Tab → 5s 刷新 status + ipList + db/list
```

### 6.5 前端-后端接口契约表

| 前端调用 | 后端端点 | 触发时机 |
|----------|----------|----------|
| `fetch(GET /auth/me)` | AuthController.me | 页面加载首屏 |
| `fetch(POST /auth/login)` | AuthController.login | 点击登录 |
| `fetch(POST /auth/register)` | AuthController.register | 点击注册 |
| `fetch(POST /auth/logout)` | AuthController.logout | 点击退出 |
| `fetch(GET /sync/status)` | SyncProgressController.status | 进入 stats Tab + 5s 轮询 |
| `fetch(GET /sync/ipList)` | SyncProgressController.ipList | 进入 stats Tab + 5s 轮询 |
| `fetch(POST /sync/db/list)` | SyncProgressController.dbList | 加载进度列表/翻页/筛选 |
| `fetch(GET /sync/databases/{ip})` | SyncProgressController.databases | 点击"查看其下数据库" |
| `fetch(GET /sync/{id})` | SyncProgressController.getById | 点击"详情" |
| `fetch(POST /sync/resyncDatabases)` | SyncProgressController.resyncDatabases | 选中+点击"重新同步" |
| `fetch(POST /sync/mapping/test)` | SyncProgressController.testMapping | 点击"测试连接" |
| `fetch(POST /sync/sourceDatabases)` | SyncProgressController.sourceDatabases | 点击"加载源库" |
| `fetch(POST /sync/sourceTables)` | SyncProgressController.sourceTables | 选库后加载表 |
| `fetch(POST /sync/mapping/add)` | SyncProgressController.addMapping | 点击"保存并开始同步" |
| `fetch(GET /sync/mappings)` | SyncProgressController.mappings | 进入 config Tab + 操作后刷新 |
| `fetch(POST /sync/mapping/remove)` | SyncProgressController.removeMapping | 点击"移除" |
| `fetch(POST /sync/mapping/update)` | SyncProgressController.updateMapping | 修改配置后保存 |
| `fetch(POST /sync/mapping/reload)` | SyncProgressController.reloadMapping | 更新后点击"重载生效" |

### 6.6 交互流程图

```
页面加载
  |
  v
/auth/me
  |
  +-- loggedIn:true --> loadAll() --> 显示管理主页 stats Tab --> 5s定时器
  |                                                                   |
  |                                                   +---------------+---------------+
  |                                                   |               |               |
  |                                              /sync/status   /sync/ipList   /sync/db/list
  |                                                   |               |               |
  |                                                   v               v               v
  |                                              渲染总览卡     渲染IP列表      渲染进度表
  |
  +-- loggedIn:false --> 显示登录页
                           |
                           v
                      填入凭据
                           |
                           v
                     /auth/login
                           |
                     +-----+-----+
                     |           |
                   成功          失败
                     |           |
                     v           v
                 loadAll()    Toast提示错误
```

---

## 第七章 部署设计

### 7.1 Linux 一键部署（install.sh）

**源码位置**：`deploy/install.sh`

#### 7.1.1 脚本功能

- 交互式配置 MySQL 账号密码（无默认值，必须手动输入）
- 自动安装 Java 17+ / Nginx / MySQL 客户端（如未安装）
- 创建数据库、部署 jar、生成配置文件
- 配置 Nginx 反向代理、systemd 服务
- 启动服务并验证

#### 7.1.2 部署流程（8 步）

```
步骤 1/8: 前置检查
  ├── 检查 root 权限
  └── 查找 jar 包（脚本同目录 / ../target/ / bin/）

步骤 2/8: 交互式配置
  ├── MySQL 主机地址 (必填)
  ├── MySQL 端口 (1-65535)
  ├── MySQL 用户名 (必填)
  ├── MySQL 密码 (隐藏输入 + 二次确认)
  ├── 后端服务端口 (默认 8090，检查端口占用)
  ├── 同步源主机 IP (回车=使用 MySQL 主机地址)
  ├── 同步目标主机 IP (回车=使用本机 MySQL)
  └── 确认配置 (y/N)

步骤 3/8: 安装系统依赖
  ├── Java 17 (yum install java-17-openjdk-devel)
  ├── Nginx (yum install nginx)
  └── MySQL 客户端 (yum install mariadb / mysql)

步骤 4/8: 测试 MySQL 连接
  └── mysql -h{host} -P{port} -u{user} -p{pass} -e "SELECT 1;"

步骤 5/8: 创建数据库并初始化表结构
  ├── CREATE DATABASE IF NOT EXISTS geodrsync
  ├── 执行 init.sql (查找: 脚本同目录 / db/ / ../src/main/resources/db/)
  ├── 创建 operation_log 表 (install.sh 内联 DDL)
  └── ALTER TABLE sync_progress ADD COLUMN USER_ID (用户隔离)

步骤 6/8: 部署应用程序
  ├── 创建目录结构: /data/geodrsync/{bin,logs,conf,conf/db,frontend,savepoint}
  ├── 复制 jar → /data/geodrsync/bin/flink-cdc-sync.jar
  ├── 复制 init.sql → /data/geodrsync/conf/db/
  ├── 复制前端资源 → /data/geodrsync/frontend/
  └── 生成 application-linux.yml (含交互式配置的 MySQL/端口/同步源/目标)

步骤 7/8: 配置 systemd 服务
  ├── 停止旧服务 (如存在)
  ├── 生成 /etc/systemd/system/geodrsync.service
  │   ├── ExecStart: java -Xms256m -Xmx512m -jar ... --spring.profiles.active=linux
  │   ├── Restart=on-failure, RestartSec=10
  │   └── StandardOutput/Error → /data/geodrsync/logs/app.out/err
  ├── systemctl daemon-reload
  └── systemctl enable geodrsync

步骤 8/8: 配置 Nginx 并启动服务
  ├── 生成 Nginx vhost 配置 (兼容宝塔面板 /www/server/nginx 和标准 /etc/nginx)
  ├── Nginx 配置:
  │   ├── location / → 前端静态资源 (/data/geodrsync/frontend)
  │   ├── location /sync/ → proxy_pass http://127.0.0.1:{BACKEND_PORT}
  │   ├── location /auth/ → proxy_pass http://127.0.0.1:{BACKEND_PORT}
  │   ├── location /actuator/ → proxy_pass http://127.0.0.1:{BACKEND_PORT}
  │   └── location /operation-log/ → proxy_pass http://127.0.0.1:{BACKEND_PORT}
  ├── nginx -t 测试配置
  ├── systemctl reload nginx
  ├── systemctl restart geodrsync
  └── 健康检查: curl /actuator/health (等待最多 60s)
```

#### 7.1.3 部署后输出

```
============================================================
  GeoDRSync 灾备同步服务部署完成！
============================================================

  访问地址:
    前端页面:  http://{SERVER_IP}/
    后端 API:  http://{SERVER_IP}:{BACKEND_PORT}/actuator/health
    默认账号:  admin / admin123

  配置文件:    /data/geodrsync/conf/application-linux.yml
  日志目录:    /data/geodrsync/logs/
  数据目录:    /data/geodrsync/

  常用命令:
    启动:  systemctl start geodrsync
    停止:  systemctl stop geodrsync
    重启:  systemctl restart geodrsync
    状态:  systemctl status geodrsync
    日志:  journalctl -u geodrsync -f
           tail -f /data/geodrsync/logs/app.log

  卸载:
    systemctl stop geodrsync && systemctl disable geodrsync
    rm -f /etc/systemd/system/geodrsync.service /etc/nginx/conf.d/geodrsync.conf
    rm -rf /data/geodrsync
    systemctl daemon-reload && nginx -s reload
============================================================
```

### 7.2 systemd 服务配置

```ini
[Unit]
Description=GeoDRSync Disaster Recovery Sync Service
After=network.target mysqld.service

[Service]
Type=simple
User=root
WorkingDirectory=/data/geodrsync
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /data/geodrsync/bin/flink-cdc-sync.jar \
  --spring.profiles.active=linux \
  --spring.config.additional-location=/data/geodrsync/conf/
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
StandardOutput=append:/data/geodrsync/logs/app.out
StandardError=append:/data/geodrsync/logs/app.err

[Install]
WantedBy=multi-user.target
```

### 7.3 Nginx 反向代理配置

```nginx
server {
    listen 80;
    server_name _;

    # 前端静态资源
    location / {
        root /data/geodrsync/frontend;
        index index.html;
        try_files $uri $uri/ /index.html;
    }

    # 后端 API 反向代理
    location /sync/ {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 30s;
        proxy_read_timeout 120s;
    }

    location /auth/ {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /actuator/ {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /operation-log/ {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

### 7.4 配置文件结构（application-linux.yml）

```yaml
server:
  port: 8090                    # 后端端口（交互式配置）
  servlet:
    context-path: /

spring:
  application:
    name: flink-cdc-sync
  web:
    resources:
      cache:
        cachecontrol:
          no-store: true         # 禁止缓存前端静态资源
          no-cache: true
          max-age: 0
  datasource:
    url: jdbc:mysql://{MYSQL_HOST}:{MYSQL_PORT}/geodrsync?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: {MYSQL_USER}
    password: {MYSQL_PASS}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      minimum-idle: 5
      maximum-pool-size: 20
      connection-timeout: 30000
      pool-name: GeoDRSync-Meta-Pool
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: GMT+8
    default-property-inclusion: non_null

mybatis-plus:
  mapper-locations: classpath*:mapper/*.xml
  type-aliases-package: com.example.flinkcdcsync.po
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      id-type: auto
      table-prefix: ""

geodrsync:
  standby-mode: true             # 灾备中心模式（true 启动同步引擎）
  flink:
    enabled: false               # 不启用 Flink 集群（使用内嵌引擎）
    parallelism: 2
    checkpoint-interval-ms: 30000
    savepoint-path: /data/geodrsync/savepoint
  engine:
    poll-interval-ms: 2000       # 同步轮询间隔
    batch-size: 1000             # 批次大小
    max-queue-size: 20000        # 队列上限
    reconcile-interval-ms: 10000
    deviation-timeout-sec: 300
  connection:
    max-pool-size: 32            # 每个 (host:port/db) 连接池上限
    max-global-connections: 200  # 全局连接数上限（信号量）
  mappings:                      # 同步主机对配置
    - source-host: {SYNC_SOURCE_HOST}
      source-port: {MYSQL_PORT}
      source-user: {MYSQL_USER}
      source-password: {MYSQL_PASS}
      target-host: {SYNC_TARGET_HOST}
      target-port: {MYSQL_PORT}
      target-user: {MYSQL_USER}
      target-password: {MYSQL_PASS}
      ignore-databases: []
      ignore-tables: []
      ignore-ddl-tables: []
      transform-rules: []

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always

logging:
  level:
    root: INFO
    com.example.flinkcdcsync: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [traceId=%X{traceId:-NA}] %logger{36} - %msg%n"
  file:
    name: /data/geodrsync/logs/app.log
```

### 7.5 开发环境（Windows）

```bash
# 环境变量（必须设置）
export JAVA_HOME=/d/software/jdk-21.0.11

# 构建（必须用仓库内 mvn3.sh，不能直接用系统 mvn）
bash /d/WorkSpace/flink-cdc-sync/mvn3.sh -o clean package -DskipTests

# 启动（必须显式指定端口，因为环境变量 SERVER__PORT=9591 会覆盖）
unset SERVER__PORT SERVER_PORT
/d/software/jdk-21.0.11/bin/java -jar target/flink-cdc-sync.jar --server.port=8080

# 前端修改后需要重新编译 app.js + 打包
cd frontend
NODE_PATH="C:/Users/Administrator/.workbuddy/binaries/node/workspace/node_modules" \
  /path/to/node build_app.js
cd ..
bash /d/WorkSpace/flink-cdc-sync/mvn3.sh -o clean package -DskipTests
# 然后停掉旧 java 进程，再启动
```

### 7.6 环境雷区

| 陷阱 | 现象 | 解决方案 |
|------|------|----------|
| git-bash 下用 `mvn` 不指定路径 | `Could not find or load main class ...Launcher` | 必须用仓库根 `mvn3.sh` |
| `SERVER__PORT=9591` 环境变量污染 | Spring Boot 启在 9591 而非 8080 | `unset SERVER__PORT SERVER_PORT` 后显式 `--server.port=8080` |
| MySQL `max_user_connections` 过低 | `User root already has more than 'max_user_connections'` | 停掉运行态 app 释放连接池再跑测试 |
| Windows 下 git-bash 的 `/d/...` 传给 Windows Node | 路径被解析为 `D:\d\...` | 用 `cygpath -m` 转混合路径 |
| MySQL 8.0 `utf8mb4_0900_*` 排序规则 | 5.7 目标库不支持导致 CREATE TABLE 失败 | DDL 清洗自动降级为 `utf8mb4_general_ci` |
| 前端 app.js 内容变更后浏览器不更新 | 旧版本号不变导致缓存 | build_app.js 编译后自动用 MD5 改写版本号 |
| 打包时 pom.xml 的 app.js include 未生效 | Jar 内 static/ 缺少 app.js 白屏 | 确认 pom 的 `<resources>` 已含 `<include>app.js</include>` |
| 连接池泄漏（mapping 移除后连接不释放） | MySQL 连接数持续增长 | `removeConnectionPoolsByHost` 按主机前缀清理所有分片池 |

---

## 第八章 异常处理与容错

### 8.1 单库失败不中断

```
doSync()
  for (String db : dbs)
    try { syncOneDatabase(db, ...) }
    catch (Exception e) { log.error("Sync database {} failed, continuing to next db", db) }
    └── 单库同步失败不中断其他库的同步
```

| 异常类型 | 处理策略 | 恢复机制 |
|----------|----------|----------|
| 源库连接断开 | `doSync()` 内 try-catch，记录日志 | 下一轮调度（最多 2s）自动重连并从头扫描 |
| 单个库同步失败 | `doSync()` 内 try-catch，跳过该库 | 继续同步其他库，下一轮重新尝试该库 |
| 单个表同步失败 | `syncOneDatabase()` 内 try-catch，跳过该表 | 继续同步其他表，下一轮重新尝试该表 |
| 目标库连接断开 | `processBatchWithWal` 重试 5 次 | 失败 → 死信队列 → 全量重新扫描补偿 |
| 单条数据写入失败 | `degradeToSingle` → 死信队列 | 60s 定时重试 + 全量扫描兜底 |
| 目标表不存在 | `ensureTargetTable` 检测并重建 | DDL 清洗后 CREATE TABLE |
| 源表被 DROP | 已知表集合对比检测 | 删除目标表 + 从进度清除 |
| DDL 不兼容 | `sanitizeDdlForPortability` | 8.0 特有语法降级为 5.7 兼容 |
| 主键列变更 | ALTER 检测 → 重建目标表 | 清空目标表全量重新同步 |

### 8.2 enqueue 超时机制

```
enqueue(RowChange change)
  deadline = now + 60_000  // 60 秒超时上限
  while (running && !queue.offer(change))
    if (now > deadline)
      log.warn("enqueue timeout (60s) for table {}, queue full, dropping event (pk={}), "
              + "will be re-synced by next full scan")
      return  // 丢弃事件
    Thread.sleep(10)
```

**设计目的**：
- 防止目标端长时间不可用时阻塞调度线程（SyncHostJob 的 doSync 线程）
- 超时后丢弃事件，下一轮全量重扫通过幂等 upsert 补偿
- 保证最终一致且不撑爆 JVM 堆

### 8.3 断电续传

| 影响 | 恢复机制 |
|------|----------|
| 内存进度丢失 | 重启后 `SyncProgressJob` 每 10s 持久化的数据仍在 `sync_progress` 表 |
| 动态映射丢失 | `geodrsync-dynamic-mappings.json` 文件保存，重启后 `loadDynamicMappings()` 加载 |
| 内存水位丢失 | `watermarks` 是内存 Map，重启后从空白开始，下一轮自动全量重扫 |
| 表结构指纹丢失 | `knownSignatures` 是内存 Map，重启后重建，ALTER 检测从下一轮开始 |
| 队列数据丢失 | 已入队未写入的数据丢失，下一轮 doSync 通过全量重扫补回 |
| 连接池泄漏 | JVM 退出自动释放所有连接 |

**关键设计**：所有状态（水位、指纹、已知表集合）都是内存态，重启后从空白开始。依靠幂等 upsert（`INSERT ... ON DUPLICATE KEY UPDATE`）保证全量重扫不会产生重复数据，实现"重启即全量重扫，数据最终一致"。

### 8.4 断网续传

```
源库断网:
  doSync() → getAllUserDatabases() → SQLException
  → catch, log.error
  → 下一轮调度（2s 后）自动重连
  → 水位未更新，下次从旧水位重扫

目标库断网:
  TableSinkFunction.processBatchWithWal() → SQLException
  → 重试 maxWalRetries=5 次（线性退避 500ms*attempt）
  → 全部失败 → degradeToSingle() 逐条
  → 单条仍失败 → 死信队列 pendingFailedEvents
  → 60s 定时器重试死信队列
  → 仍失败 → 依赖下一轮全量对账补偿
  → enqueue 60s 超时 → 丢弃事件 → 下一轮全量重扫补回
```

**分层保障**：
1. WAL 重试（5 次线性退避：500ms, 1s, 1.5s, 2s, 2.5s）
2. 单条降级（逐条自动提交）
3. 死信队列（60s 重试，上限 100,000）
4. enqueue 超时丢弃（60s，依赖全量补偿）
5. 全量对账兜底（下一轮 doSync 全量重扫 + 删除对账）

### 8.5 OOM 防护

| 场景 | 防护机制 |
|------|----------|
| 全量扫描大数据量表 | `streamScan` 流式分页（keyset/OFFSET），每页约 5000 行，处理完即释放 |
| 删除对账百万行表 | `reconcileDeletes` 按主键分页拉取目标端 PK，每页 5000，IN 列表 1000 分片回查 |
| 队列积压 | `LinkedBlockingQueue` 上限 20000，offer 返回 false 触发反压 |
| 死信队列膨胀 | 上限 100,000，超出丢弃 + 全量对账兜底 |
| 大表首次全量对账耗时 | `isFullSync && scanned > 100_000` 时跳过 `reconcileDeletes` |

### 8.6 DDL 变更处理

#### 8.6.1 源表被 DROP

```
已知表集合(knownTablesByDb[db]) = {tableA, tableB, tableC}
本轮发现(current) = {tableA, tableC}   // tableB 消失

= syncOneDatabase 的 DROP 检测:
  for (t : prev)
    if (!current.contains(t) && !isDmlIgnored(db, t))
      sink.removeTable(t)           // 关闭表级 Sink + 清理队列
      knownSignatures.remove(db + "." + t)  // 清除指纹
      metadataService.dropTargetTable(targetDB, t)  // DROP TABLE IF EXISTS
      log.info("DDL DROP detected, removed target table")
```

#### 8.6.2 源表 ALTER TABLE

```
detectAndApplyAlter(db, table, sourceDB, targetDB):
  cur = getTableInfo(sourceDB, db, table)
  sig = signature(cur)  // "col1,col2,col3#pk1,pk2"
  prev = knownSignatures.get(db + "." + table)
  if (prev != null && !prev.equals(sig))
    metadataService.dropTargetTable(targetDB, table)   // DROP TABLE
    metadataService.ensureTargetTable(sourceDB, targetDB, table)  // 按源DDL重建
    watermarks.remove(db + "." + table)                // 重置水位
    log.info("DDL ALTER detected, recreated target table")
  knownSignatures.put(db + "." + table, sig)
  → 下一轮全量重新扫描
```

#### 8.6.3 新增表

```
本轮发现 current = {tableA, tableB, tableC, tableD}  // tableD 是新增
= ensureTable(tableD):
  getTableInfo(sourceDB, db, tableD)  // 解析表结构
  ensureTargetTable(sourceDB, targetDB, tableD)  // CREATE TABLE IF NOT EXISTS
  创建 TableSinkFunction + 启动处理器线程
  → syncTable(tableD) 全量同步
```

### 8.7 会话过期 / 鉴权失效

```
Session 有效期 8 小时 (session.setMaxInactiveInterval(8h))
→ 过期后 AuthInterceptor 返回 401 JSON
→ 前端 http() 包装器检测 401 → Toast "请重新登录"
→ 自动切换显示登录页
```

### 8.8 全局异常拦截

```
请求 → TraceIdFilter → AuthInterceptor → Controller
                                            ↓ (异常)
                                     GlobalExceptionHandler (@ControllerAdvice)
                                       ├── BusinessException → 按 code 的 httpStatus 返回
                                       ├── MethodArgumentNotValidException → 400 PARAM_ERROR
                                       ├── ConstraintViolationException → 400 PARAM_ERROR
                                       └── Exception → 500 SYNC_ENGINE_ERROR
```

---

## 第九章 测试策略

### 9.1 测试层次与覆盖矩阵

GeoDRSync 项目采用分层自动化测试策略，共计 **188 个自动化测试用例**，覆盖 API 接口、同步引擎、配置热生效、用户隔离与审计四大维度。

| 测试层次 | 框架 | 覆盖范围 | 数量 |
|----------|------|----------|------|
| API 接口测试 | Spring Boot Test + TestRestTemplate + 真实 MySQL | 15 个 REST 端点的请求/响应/错误码 | 105 |
| 同步引擎测试 | Spring Boot Test + 真实 MySQL 跨主机 | 全量/增量/DDL/对账/大表/断网 | 46 |
| 配置热生效测试 | Spring Boot Test + update+reload | ignoreDatabases/ignoreTables/transformRules 热更新 | 14 |
| 用户隔离与审计测试 | Spring Boot Test + 多用户 Session | userId 过滤/操作日志/密码脱敏 | 23 |
| **合计** | | | **188** |

### 9.2 API 接口测试（105 个）

覆盖所有 `/auth/**` 和 `/sync/**` 端点：

| 接口分组 | 测试维度 | 数量 |
|----------|----------|------|
| /auth/register | 用户名/密码校验、重复注册、注入防御、边界长度 | 12 |
| /auth/login | 正常登录、错误密码、不存在用户、Session 写入、8h 过期 | 14 |
| /auth/logout | 正常登出、未登录登出、Session 失效 | 8 |
| /auth/me | 已登录/未登录/过期 Session | 6 |
| /sync/status | 正常/异常状态、首异常时间 | 8 |
| /sync/ipList | 节点列表完整性、生产/灾备标签 | 6 |
| /sync/db/list | 分页/筛选/排序/空结果/越界页码 | 15 |
| /sync/databases/{ip} | 按IP查库、系统库过滤、不存在IP | 8 |
| /sync/{id} | 详情查询、不存在ID、字段完整性 | 6 |
| /sync/resyncDatabases | 批量重置、部分成功/部分失败、不存在主机对 | 10 |
| /sync/mapping/test | 源/目标连接成功/失败、超时、错误密码 | 8 |
| /sync/mappings | 列表查询、userId 过滤、密码脱敏 | 4 |
| **合计** | | **105** |

### 9.3 同步引擎测试（46 个）

基于真实 MySQL 跨主机环境（127.0.0.1 → 192.168.88.88）验证：

| 测试场景 | 验证点 | 数量 |
|----------|--------|------|
| 历史全量同步 | 多表全量复制、行数一致、字段值一致 | 6 |
| 实时增量同步 | INSERT/UPDATE/DELETE 实时同步、水位推进 | 8 |
| 删除对账 | 源删→目标删、多表对账、无主键跳过 | 6 |
| DDL-新增表 | 源建表→目标自动建表、DDL 清洗、列一致 | 4 |
| DDL-ALTER | 加列/删列/改主键→目标重建表、全量重扫 | 5 |
| DDL-DROP | 源删表→目标删表、队列清理、指纹清除 | 3 |
| 大表优化 | 10万+行首次全量跳过 reconcileDeletes、流式分页进度日志 | 4 |
| 多库同步 | 自动发现新库、同名库镜像、跨库一致性 | 5 |
| 字段转换 | IP 替换、值脱敏、主键不转换、多规则叠加 | 3 |
| 断网续传 | 源断网重连、目标断网 WAL 重试+死信+全量补偿 | 2 |
| **合计** | | **46** |

### 9.4 配置热生效测试（14 个）

验证 `updateMappingConfig` + `reloadMapping` 双步流程：

| 测试场景 | 验证点 | 数量 |
|----------|--------|------|
| ignoreDatabases 热更新 | 新增忽略库→下一轮跳过、移除忽略→重新纳入 | 3 |
| ignoreTables 热更新 | 表级忽略（精确/glob/正则）即时生效 | 3 |
| ignoreDdlTables 热更新 | 仅 DDL 忽略、DML 仍同步 | 2 |
| transformRules 热更新 | 新增/修改/删除转换规则即时生效 | 3 |
| reload 后水位重置 | 作业重建→全量重扫→幂等 upsert 无重复 | 2 |
| 动态映射持久化 | 重启后 JSON 文件恢复、userId 保留 | 1 |
| **合计** | | **14** |

### 9.5 用户隔离与审计测试（23 个）

| 测试场景 | 验证点 | 数量 |
|----------|--------|------|
| 用户注册隔离 | 用户A 注册的映射用户B 不可见 | 3 |
| 映射列表 userId 过滤 | 全局映射(yml)对所有用户可见、动态映射仅创建者可见 | 4 |
| 操作日志记录 | LOGIN/LOGOUT/REGISTER/RESYNC/MAPPING_* 全覆盖 | 8 |
| 密码脱敏 | operation_log 中 password/passwd 字段为 `***` | 3 |
| 未登录访问 | /sync/** 返回 401、/auth/me 返回 loggedIn:false | 3 |
| Session 过期 | 8h 后 401、前端自动跳转登录页 | 2 |
| **合计** | | **23** |

### 9.6 核心测试用例示例

参考 `GeoDRSyncConsistencyTest.java`（源码已实现的核心集成测试）：

| 测试方法 | 覆盖场景 |
|----------|----------|
| `testStatusEndpoint` | GET /sync/status 返回 status + desc |
| `testIpListEndpoint` | GET /sync/ipList 至少 2 个节点 |
| `testDbListEndpoint` | POST /sync/db/list 分页查询 |
| `testDatabasesByIp` | GET /sync/databases/127.0.0.1 包含 geo_source |
| `testResyncAndDetail` | POST /sync/resyncDatabases + GET /sync/{id} 详情 |
| `testDataConsistencyInsertAndDelete` | 源插入→灾备一致→源删除→灾备对账删除 |
| `testMultiDatabaseHistoricalAndRealtime` | 多库历史全量+实时增删改+跨库一致性 |
| `testMappingConfigEndpoints` | 连接测试+映射 CRUD+重复新增拦截 |

### 9.7 测试执行

```bash
# 一键执行全量测试（需真实 MySQL 127.0.0.1:3306 root/123456 + 192.168.88.88 可达）
cd /d/WorkSpace/flink-cdc-sync/flink-cdc-sync
bash run-tests.sh

# 单独执行集成测试
bash /d/WorkSpace/flink-cdc-sync/mvn3.sh -o test \
  -Dtest=GeoDRSyncConsistencyTest \
  -Dspring.profiles.active=test
```

测试前置条件：
- MySQL 127.0.0.1:3306 可达，root/123456
- 灾备主机 192.168.88.88:3306 可达（或修改测试常量）
- geodrsync 元数据库已初始化
- 示例库 geo_source / biz_source 存在

---

## 附录

### 附录 A：关键配置参数速查

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `geodrsync.engine.poll-interval-ms` | 2000 | 同步轮询间隔 |
| `geodrsync.engine.batch-size` | 1000 | 批次大小 |
| `geodrsync.engine.max-queue-size` | 20000 | 表级队列上限 |
| `geodrsync.connection.max-pool-size` | 32 | 每分片连接池上限 |
| `geodrsync.connection.max-global-connections` | 200 | 全局连接数上限 |
| `geodrsync.standby-mode` | true | 灾备中心模式开关 |
| `geodrsync.flink.enabled` | false | Flink 集群模式开关（当前未启用） |
| `app.auth.enabled` | true | 认证拦截器开关 |
| Session 超时 | 8h | HttpSession.setMaxInactiveInterval |
| enqueue 超时 | 60s | 队列满时等待上限 |
| WAL 重试次数 | 5 | maxWalRetries |
| WAL 退避基数 | 500ms | 线性退避 500ms * attempt |
| 死信队列上限 | 100,000 | MAX_DEAD_LETTER |
| 死信重试间隔 | 60s | ScheduledExecutorService |
| 大表阈值 | 100,000 | 首次全量跳过 reconcileDeletes |
| 流式分页大小 | batchSize * 5 | 默认 5000 |
| reconcileDeletes IN 分片 | 1000 | 避免 max_allowed_packet |
| 进度持久化间隔 | 10s | SyncProgressJob |
| 新库发现间隔 | 60s | ScanNewDatabaseJob |

### 附录 B：源码文件索引

| 模块 | 文件路径 | 行数 |
|------|----------|------|
| 同步引擎 | `sync/SyncHostJob.java` | 717 |
| 库级 Sink | `sink/DataBaseSinkFunction.java` | ~300 |
| 表级 Sink | `sink/TableSinkFunction.java` | ~450 |
| 同步管理器 | `manager/DatabaseSyncManager.java` | ~600 |
| 连接池管理 | `manager/DynamicShardedConnectionManager.java` | ~250 |
| 队列管理 | `manager/TableLevelQueueManager.java` | ~120 |
| 线程池管理 | `manager/GroupedExecutorManager.java` | ~80 |
| 元数据服务 | `service/DatabaseMetadataService.java` | ~350 |
| 认证控制器 | `controller/AuthController.java` | ~150 |
| 进度控制器 | `controller/SyncProgressController.java` | ~400 |
| 操作日志切面 | `common/OperationLogAspect.java` | ~180 |
| 认证拦截器 | `manager/AuthInterceptor.java` | ~80 |
| 用户上下文 | `common/UserContext.java` | ~50 |
| 进度持久化 | `job/SyncProgressJob.java` | ~60 |
| 新库发现 | `job/ScanNewDatabaseJob.java` | ~50 |
| 配置文件 | `resources/application.yml` | ~120 |
| 数据库初始化 | `resources/db/init.sql` | ~200 |
| Maven 依赖 | `pom.xml` | ~150 |
| 部署脚本 | `deploy/install.sh` | ~500 |
| 集成测试 | `test/.../GeoDRSyncConsistencyTest.java` | 360 |

### 附录 C：错误处理决策树

```
异常发生
  |
  +-- 源库连接异常?
  |     +-- 是 → doSync catch → 下一轮重试（2s）
  |
  +-- 目标库连接异常?
  |     +-- 是 → WAL 重试 5 次（线性退避）
  |           +-- 成功 → 继续
  |           +-- 失败 → degradeToSingle 逐条
  |                 +-- 成功 → 继续
  |                 +-- 失败 → 死信队列（60s 重试，上限 10万）
  |
  +-- DDL 异常?
  |     +-- ALTER → 重建目标表 + 重置水位
  |     +-- DROP → 删除目标表 + 清理队列
  |     +-- 新表 → CREATE TABLE IF NOT EXISTS
  |
  +-- 队列满?
  |     +-- 是 → enqueue 60s 超时 → 丢弃 → 全量重扫补偿
  |
  +-- 大表首次全量?
        +-- 是 → 跳过 reconcileDeletes
        +-- 否 → 执行 reconcileDeletes（分页 PK 对账）
```

### 附录 D：FAQ

**Q1: 为什么不用 Flink CDC 而用自研引擎？**

A: 项目预留了 `geodrsync.flink.enabled` 开关，可切换到 Flink 集群模式。当前版本使用自研内嵌引擎的原因：
- 部署简单：单 JAR 包，无需维护 Flink 集群
- 资源占用低：适合中小规模灾备场景
- 运维成本低：systemd 单服务管理
- 充分利用 MySQL 原生 JDBC 能力，避免 Flink CDC 的 binlog 权限要求

**Q2: 全量重扫会不会产生重复数据？**

A: 不会。写入使用 `INSERT ... ON DUPLICATE KEY UPDATE`（幂等 upsert），主键相同的行会被覆盖更新而非插入新行。因此重启、全量重置、配置 reload 后的全量重扫都不会产生重复。

**Q3: 如何处理无主键表？**

A: 
- 全量同步：使用 OFFSET 分页（非 keyset）
- 增量同步：无 update_time 水位时每轮全量重扫
- 删除对账：无主键表跳过（`pkCols.isEmpty()` 时 return）
- upsert：退化为 INSERT（可能产生重复，需业务侧保证）

**Q4: 配置热生效后数据会丢失吗？**

A: 不会丢失，但会全量重扫。`reloadMapping` 停止旧作业 → 清理连接池 → 启动新作业。新作业的 watermarks/knownSignatures 从空白开始，下一轮自动全量重扫。由于幂等 upsert，重扫不产生重复，只是短暂的 CPU/IO 开销。

**Q5: 多用户隔离的具体规则？**

A:
- `userId == null` 的映射：来自 yml 配置，全局可见（所有用户可见）
- `userId == 当前用户` 的映射：通过页面 `/sync/mapping/add` 动态新增时自动绑定 `UserContext.getUserId()`
- 用户只能看到全局映射和自己创建的映射
- 移除映射无 userId 限制（任何登录用户可移除任何映射，需业务侧约束）

**Q6: 死信队列满了怎么办？**

A: 死信队列上限 100,000。超出后新失败事件被丢弃并记录 ERROR 日志。丢弃的事件不会永久丢失——下一轮 `doSync` 的全量重扫 + reconcileDeletes 会通过幂等 upsert 和删除对账补偿回来。

**Q7: 为什么 LocalDateTime 要用 setObject 而不是 setTimestamp？**

A: `setTimestamp` 会经过 JVM 默认时区转换，当 JVM 时区（如 PDT）与数据库时区（如 Asia/Shanghai）不一致时，时间值会被偏移 15 小时。`setObject` 以字面量方式绑定 DATETIME，避免时区转换，保证跨时区部署时间一致。

---

*文档结束*