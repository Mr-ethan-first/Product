# GeoDRSync — MySQL 跨主机数据库灾备同步中间件

GeoDRSync 将生产中心 MySQL 数据库的数据实时同步到异地灾备中心。采用"主机对模型 + 自动库发现"设计，运维人员只需配置一组源主机与目标主机，引擎自动发现源端全部用户库表并启动同步，新增库表无需人工介入。

## 核心能力

| 能力 | 说明 |
|------|------|
| 自动库发现 | 60 秒扫描周期自动发现源端新增库表并纳入同步 |
| 全量 + 增量同步 | 首次全量流式分页扫描，后续按 `update_time` 增量同步，P99 延迟 < 3 秒 |
| 双模式忽略规则 | DML+DDL 忽略与仅 DDL 忽略独立配置，支持精确/glob/正则三种匹配 |
| 跨版本 DDL 清洗 | MySQL 8.0 专有语法自动降级为 5.7 兼容语法 |
| 字段转换 | 支持 IP 替换、字段脱敏、枚举映射，全局映射与私有映射分层 |
| 配置热生效 | 修改配置无需重启，通过 API 即时更新引擎 |
| 断电断网续传 | 水位丢失自动全量重扫，幂等 upsert 保证最终一致 |
| 多用户隔离 | 配置按用户隔离，私有配置仅创建者可见 |
| 操作审计 | AOP 切面自动记录所有操作，密码脱敏后入库 |
| HA 高可用 | 检测 MySQL 主备切换，自动重建连接池并恢复同步 |
| Web 管理后台 | React 单页应用，可视化监控同步进度与异常 |

## 技术栈

| 层级 | 选型 |
|------|------|
| 后端 | Spring Boot 3.4.3 + MyBatis-Plus 3.5.9 + Java 17 |
| 前端 | React 18 UMD + JSX 构建期预编译（无 Node 运行时依赖） |
| 数据库 | MySQL 8.0（源端）→ MySQL 5.7（目标端），跨大版本兼容 |
| 连接池 | HikariCP + 全局信号量限流 |
| 部署 | systemd + Nginx 反向代理 |

## 快速部署

### 环境要求

- Linux（CentOS 7 / Ubuntu 18.04+ / 其他主流发行版）
- root 权限
- MySQL 5.7+（源端与目标端可同机或异机）
- 网络可达源端与目标端 MySQL 的 3306 端口

### 一键安装

将 `flink-cdc-sync.jar`、`install.sh`、`init.sql` 放在同一目录下，执行：

```bash
sudo bash install.sh
```

脚本会依次完成 8 个步骤：

1. **前置检查** — 验证 root 权限、定位 jar 包
2. **交互式配置** — 输入 MySQL 主机、端口、用户名、密码（无默认值，密码需二次确认）、后端端口、同步源/目标主机
3. **安装依赖** — 自动安装 Java 17+、Nginx、MySQL 客户端（如未安装）
4. **测试 MySQL 连接** — 验证输入的凭据可连通
5. **创建数据库** — 创建 `geodrsync` 元数据库并初始化表结构
6. **部署应用** — 复制 jar 包、前端资源到 `/data/geodrsync/`，生成 `application-linux.yml`
7. **配置 systemd** — 创建 `geodrsync.service`，开机自启
8. **配置 Nginx** — 创建反向代理 vhost，启动服务并健康检查

安装完成后输出访问地址与常用命令。

### 交互式配置示例

```
请配置 MySQL 数据库连接信息（无默认值，必须手动输入）：

MySQL 主机地址: 192.168.88.88
MySQL 端口: 3306
MySQL 用户名: root
MySQL 密码: ********
确认密码: ********

GeoDRSync 后端服务端口配置：
后端服务端口 (默认 8090): 8090

数据库同步映射配置（源主机数据 → 目标主机）：
同步源主机 IP (回车=使用 MySQL 主机地址 192.168.88.88):
同步目标主机 IP (回车=使用本机 MySQL 192.168.88.88):
```

## 目录结构

安装后的目录布局：

```
/data/geodrsync/
├── bin/
│   └── flink-cdc-sync.jar      # 应用主包
├── conf/
│   ├── application-linux.yml   # 运行配置（由 install.sh 生成）
│   └── db/
│       └── init.sql            # 数据库初始化脚本
├── frontend/                   # 前端静态资源
│   ├── index.html
│   └── app.js
├── logs/
│   ├── app.log                 # 应用日志
│   ├── app.out                 # 标准输出
│   └── app.err                 # 错误输出
└── savepoint/                  # Savepoint 目录（Flink 模式）
```

## 配置说明

主配置文件：`/data/geodrsync/conf/application-linux.yml`

### 核心配置项

```yaml
geodrsync:
  standby-mode: true              # true=启动同步引擎，false=仅管理后台
  engine:
    poll-interval-ms: 2000        # 同步轮询间隔（毫秒）
    batch-size: 1000              # 单批次行数
    max-queue-size: 20000         # 死信队列上限
    reconcile-interval-ms: 10000  # 对账间隔（毫秒）
    deviation-timeout-sec: 300    # 偏差超时告警（秒）
  connection:
    max-pool-size: 32             # 单主机连接池上限
    max-global-connections: 200   # 全局连接数信号量
  mappings:
    - source-host: 192.168.88.88  # 源端 MySQL 主机
      source-port: 3306
      source-user: root
      source-password: ********
      target-host: 192.168.88.88  # 目标端 MySQL 主机
      target-port: 3306
      target-user: root
      target-password: ********
      ignore-databases: ["day01"] # 整库忽略（支持正则 re:^tmp_.*）
      ignore-tables: []           # 表忽略（DML+DDL 均跳过）
      ignore-ddl-tables: []       # 仅忽略 DDL（数据仍同步）
      transform-rules: []         # 字段转换规则
```

### 忽略规则语法

三种匹配语法可在同一规则集中混用：

| 语法 | 示例 | 说明 |
|------|------|------|
| 精确匹配 | `db_name.table_name` | 完全匹配库表名 |
| Glob 通配 | `db_*.log_*` | 支持 `*` 通配符 |
| 正则匹配 | `re:^shard_\d+\..*` | `re:` 前缀触发正则模式 |

### 字段转换规则

```yaml
transform-rules:
  - database: geo_source
    table: t_user
    column: phone
    type: MASK         # MASK=脱敏, REPLACE=替换, MAP=枚举映射
    oldValue: ""
    newValue: "138****0000"
```

### 配置热生效

修改配置后无需重启服务，调用 API 即时生效：

```bash
# 增量更新单个主机对配置
curl -X POST http://localhost:8090/sync/mapping/update \
  -H "Content-Type: application/json" \
  -H "Cookie: JSESSIONID=<your-session-id>" \
  -d '{"sourceHost":"192.168.88.88","ignoreDatabases":["day01","day02"]}'

# 全量重载所有配置
curl -X POST http://localhost:8090/sync/mapping/reload \
  -H "Cookie: JSESSIONID=<your-session-id>"
```

## 服务管理

```bash
# 启动
systemctl start geodrsync

# 停止
systemctl stop geodrsync

# 重启
systemctl restart geodrsync

# 查看状态
systemctl status geodrsync

# 查看实时日志
journalctl -u geodrsync -f

# 查看应用日志
tail -f /data/geodrsync/logs/app.log
```

## API 接口

所有接口需登录后携带 `JSESSIONID` Cookie 访问（除 `/auth/**` 和 `/actuator/**`）。

### 鉴权

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/auth/login` | 登录，返回 JSESSIONID |
| POST | `/auth/register` | 注册新用户 |
| POST | `/auth/logout` | 登出 |

### 同步配置

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/sync/mappings` | 查询当前用户可见的主机对列表 |
| POST | `/sync/mapping/add` | 新增主机对 |
| POST | `/sync/mapping/remove` | 移除主机对 |
| POST | `/sync/mapping/update` | 增量更新主机对配置（热生效） |
| POST | `/sync/mapping/reload` | 全量重载配置 |

### 同步监控

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/sync/progress` | 查询同步进度 |
| GET | `/sync/status` | 查询引擎整体状态 |
| POST | `/sync/resync` | 触发指定表全量重同步 |
| POST | `/sync/connection-test` | 测试源/目标端连通性 |

### 元数据查询

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/sync/source-databases` | 查询源端用户库列表 |
| GET | `/sync/source-tables` | 查询指定库的用户表列表 |

### 审计日志

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/operation-log/list` | 分页查询操作日志 |
| GET | `/operation-log/types` | 查询操作类型枚举 |

### 健康检查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/actuator/health` | 服务健康检查 |
| GET | `/actuator/metrics` | 运行指标 |

### 登录示例

```bash
# 登录获取 Cookie
curl -c cookies.txt -X POST http://localhost:8090/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 使用 Cookie 查询同步进度
curl -b cookies.txt http://localhost:8090/sync/progress
```

默认管理员账号：`admin` / `admin123`，首次部署后请立即修改密码。

## 数据库表结构

元数据库 `geodrsync` 包含以下管控表：

| 表名 | 用途 |
|------|------|
| `sync_progress` | 同步进度与水位记录 |
| `dbha_switch_status` | MySQL 主备切换状态 |
| `source_latest_binlog_info` | 源端最新 Binlog 位点 |
| `sync_restart_task` | 重启任务队列 |
| `sys_user` | 后台管理用户 |
| `operation_log` | 操作审计日志 |

## 测试

项目包含 188 个自动化测试用例，覆盖前后端全链路：

| 测试模块 | 文件 | 用例数 | 覆盖范围 |
|----------|------|--------|----------|
| 鉴权 | test_01_auth | 22 | 登录/注册/登出/会话过期 |
| 同步读取 | test_02_sync_read | 22 | 进度查询/状态查询/分页 |
| 连接测试 | test_03_sync_connection | 17 | 源/目标端连通性验证 |
| 同步映射 | test_04_sync_mapping | 11 | 主机对增删改查 |
| 重同步 | test_05_sync_resync | 5 | 手动触发全量重扫 |
| 安全 | test_06_security | 13 | 未授权访问/SQL 注入防护 |
| 同步引擎 | test_07_sync_engine | 11 | INSERT/UPDATE/DELETE/DDL |
| 定时任务 | test_08_scheduled_jobs | 7 | 库发现/Binlog/进度/HA |
| 元数据 | test_09_metadata_service | 5 | 库表列表查询 |
| 连接池 | test_10_connection_pool | 5 | 并发连接/信号量限流 |
| 忽略与转换 | test_11_ignore_transform | 9 | 忽略规则/字段转换 |
| HA 与重启 | test_12_ha_restart | 9 | 主备切换/进程重启恢复 |
| 配置热生效 | test_13_config_hot_reload | 14 | 动态配置/断电续传 |
| 用户隔离与审计 | test_14_user_isolation_audit | 23 | 用户隔离/操作日志/IP 记录 |

运行测试：

```bash
# 确保 MySQL 与应用服务已启动
cd D:\WorkSpace\flink-cdc-sync
python -m pytest tests/ -v

# 运行单个测试模块
python -m pytest tests/test_01_auth.py -v

# 跳过破坏性测试
python -m pytest tests/ -v -m "not destructive"
```

测试环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `GEODRSYNC_BASE_URL` | `http://127.0.0.1:8080` | 被测服务地址 |
| `GEODRSYNC_ADMIN_USER` | `admin` | 管理员账号 |
| `GEODRSYNC_ADMIN_PASS` | `admin123` | 管理员密码 |

## 本地开发

### 环境准备

- JDK 17+
- Maven 3.6+
- Node.js 16+（仅前端构建需要，运行时不需要）
- MySQL 5.7+ 或 8.0

### 构建与运行

```bash
# 编译打包（跳过测试）
mvn -DskipTests package -q

# 启动后端（使用默认配置）
java -jar target/flink-cdc-sync.jar

# 前端构建（将 JSX 预编译为 app.js）
cd frontend
node build_app.js
```

前端静态资源位于 `frontend/` 目录，构建产物为 `app.js` 和 `index.html`，由 Spring Boot 静态资源服务直接提供。

### 配置文件

| 文件 | 用途 |
|------|------|
| `application.yml` | 默认配置（本地开发） |
| `application-linux.yml` | Linux 部署配置（由 install.sh 生成） |
| `application-test.yml` | 测试环境配置 |

## 故障排查

### 服务无法启动

```bash
# 查看 systemd 日志
journalctl -u geodrsync -n 100

# 查看应用错误日志
tail -100 /data/geodrsync/logs/app.err

# 常见原因：
# 1. MySQL 连接失败 — 检查 application-linux.yml 中的凭据
# 2. 端口被占用 — netstat -tlnp | grep 8090
# 3. Java 版本过低 — java -version 确认 >= 17
```

### 同步延迟高

```bash
# 查看同步状态
curl -b cookies.txt http://localhost:8090/sync/status

# 查看同步进度
curl -b cookies.txt http://localhost:8090/sync/progress

# 常见原因：
# 1. 大表全量同步阻塞 — 配置 ignore-databases 跳过大库
# 2. 目标端 MySQL 压力高 — 降低 engine.batch-size
# 3. 网络延迟 — 检查源/目标端之间的网络质量
```

### Nginx 301 重定向

如果通过 80 端口访问返回 301，通常是 Nginx 配置中 `server_name` 冲突。编辑 `/www/server/nginx/conf/vhost/geodrsync.conf`（宝塔面板）或 `/etc/nginx/conf.d/geodrsync.conf`，将 `server_name _;` 改为实际 IP 或域名：

```nginx
server {
    listen 80;
    server_name 192.168.88.88;  # 改为实际 IP
    ...
}
```

然后执行 `nginx -s reload`。

### 忘记管理员密码

直接通过 MySQL 重置：

```sql
-- 连接到 geodrsync 元数据库
USE geodrsync;

-- 查看现有用户
SELECT ID, USERNAME FROM sys_user;

-- 重置 admin 密码为 admin123
-- 密码哈希格式: salt:sha256，需通过应用提供的 PasswordUtil 生成
-- 或直接删除用户重新注册：
DELETE FROM sys_user WHERE USERNAME = 'admin';
```

重启服务后，`SysUserInitializer` 会自动重新创建默认 admin 账号。

## 卸载

```bash
systemctl stop geodrsync
systemctl disable geodrsync
rm -f /etc/systemd/system/geodrsync.service
rm -f /www/server/nginx/conf/vhost/geodrsync.conf   # 宝塔面板
# 或 rm -f /etc/nginx/conf.d/geodrsync.conf           # 标准安装
rm -rf /data/geodrsync
systemctl daemon-reload
nginx -s reload
```

元数据库 `geodrsync` 需手动删除：

```sql
DROP DATABASE geodrsync;
```

## 相关文档

| 文档 | 说明 |
|------|------|
| [GeoDRSync-PRD.md](GeoDRSync-PRD.md) | 产品需求文档 — 功能定义、用户场景、成功指标 |
| [GeoDRSync-详细设计说明书.md](GeoDRSync-详细设计说明书.md) | 详细设计 — 模块设计、数据库设计、API 设计、测试策略 |
