# GeoDRSync 安装部署指南

GeoDRSync 是 MySQL 跨主机数据库灾备同步中间件，采用 Spring Boot 单体架构，部署时需要 Java 17+ 运行环境和 MySQL 5.7+ 数据库。系统支持 MySQL 5.7 与 8.0 之间的任意方向同步（8→8、5→5、8→5、5→8），跨版本场景下自动处理 DDL 语法兼容。本文档覆盖从环境准备到服务上线的完整流程，包含一键安装、手动安装、升级、卸载四个场景。

## 环境要求

### 操作系统

GeoDRSync 基于 Java 运行，理论上支持任何能运行 JDK 17 的操作系统。已在以下环境验证：

| 操作系统 | 版本 | 状态 |
|----------|------|------|
| CentOS | 7.9 | 生产验证 |
| Ubuntu | 18.04 / 20.04 / 22.04 | 兼容 |
| Debian | 10 / 11 | 兼容 |
| Rocky Linux | 8 / 9 | 兼容 |

### 软件依赖

| 组件 | 最低版本 | 用途 | 安装方式 |
|------|----------|------|----------|
| JDK | 17 | 应用运行时 | yum / apt |
| MySQL | 5.7 | 元数据库 + 源/目标端 | 独立安装或远程连接 |
| Nginx | 1.18 | 反向代理 + 静态资源 | yum / apt / 宝塔面板 |
| MySQL Client | 5.7+ | install.sh 执行初始化 SQL | yum / apt |

### MySQL 版本兼容性

GeoDRSync 支持 MySQL 5.7 和 8.0 之间的任意方向同步。四种版本组合均已验证：

| 源端版本 | 目标端版本 | 兼容性 | DDL 处理 | 说明 |
|----------|----------|--------|----------|------|
| 8.0 | 8.0 | 完全兼容 | 无需清洗 | 同版本同步，DDL 直接复制 |
| 5.7 | 5.7 | 完全兼容 | 无需清洗 | 同版本同步，DDL 直接复制 |
| 8.0 | 5.7 | 兼容 | 自动降级 | 8.0 专有语法（`utf8mb4_0900_*` 排序规则）自动降级为 5.7 兼容的 `utf8mb4_general_ci` |
| 5.7 | 8.0 | 兼容 | 无需清洗 | 5.7 的 DDL 语法是 8.0 的子集，直接执行即可 |

跨版本同步的核心机制是 DDL 清洗函数 `sanitizeDdlForPortability`：当源端为 8.0、目标端为 5.7 时，`SHOW CREATE TABLE` 返回的 DDL 中可能包含 8.0 专有的 `utf8mb4_0900_ai_ci`、`utf8mb4_0900_as_cs` 等排序规则，这些在 5.7 上不存在，直接执行会报 "Unknown collation"。清洗函数将这些排序规则统一替换为两个版本通用的 `utf8mb4_general_ci`，保证同一 DDL 可在高低版本上执行。

### MySQL Binlog 配置要求

源端 MySQL 必须开启 binlog 并设置 `binlog_format=ROW`，这是灾备同步的基础前提。GeoDRSync 的 `SourceBinlogJob` 定时任务（每 30 秒）通过 `SHOW MASTER STATUS` 采集源端 binlog 位点（File + Position），用于：

- 记录同步进度对应的 binlog 位点，便于故障恢复时定位
- HA 主备切换检测，通过 binlog 位点变化判断是否发生切换
- 偏差监控，对比源端最新 binlog 时间与同步到位点的时间差

**必须配置的参数**（源端 `my.cnf` 的 `[mysqld]` 段）：

```ini
[mysqld]
# 开启 binlog
log-bin=mysql-bin
# 设置 binlog 格式为 ROW（必须）
binlog_format=ROW
# 完整记录行镜像（推荐）
binlog_row_image=FULL
# 设置 server_id（主备环境下需唯一）
server_id=1
```

**验证配置**：

```sql
SHOW VARIABLES LIKE 'binlog_format';
-- 预期: ROW

SHOW VARIABLES LIKE 'log_bin';
-- 预期: ON

SHOW VARIABLES LIKE 'binlog_row_image';
-- 预期: FULL

SHOW MASTER STATUS;
-- 预期: 返回 File 和 Position 列
```

如果 `SHOW MASTER STATUS` 返回空，说明 binlog 未开启，需修改 `my.cnf` 后重启 MySQL。`binlog_format` 不是 `ROW` 时（如 `STATEMENT` 或 `MIXED`），虽然 GeoDRSync 的数据同步不直接解析 binlog 内容，但会影响灾备恢复时的数据精度和 HA 切换检测的准确性。

目标端 MySQL 不强制要求开启 binlog，但建议同样配置，以便目标端可作为下一级灾备的源端。

### 硬件建议

| 资源 | 最低配置 | 推荐配置 | 说明 |
|------|----------|----------|------|
| CPU | 2 核 | 4 核 | 同步引擎为单线程轮询，核心数影响不大 |
| 内存 | 2 GB | 4 GB | JVM 默认 -Xmx512m，连接池占用另算 |
| 磁盘 | 10 GB | 50 GB | 日志增长较快，建议独立 /data 分区 |
| 网络 | 100 Mbps | 1 Gbps | 源端与目标端之间的带宽决定同步延迟 |

### 端口规划

GeoDRSync 涉及以下端口，部署前需确认可用性：

| 端口 | 用途 | 是否必须对外开放 | 备注 |
|------|------|------------------|------|
| 8088 | Nginx 前端 + API 代理 | 是 | Web 管理后台访问入口，可改为其他端口 |
| 8090 | Spring Boot 后端 | 否 | 仅 Nginx 需访问，建议仅监听 127.0.0.1 |
| 3306 | MySQL | 否 | 应用通过内网或本机连接 |

如果服务器上已有其他应用占用 80 或 443 端口（如宝塔面板、其他 Web 应用），GeoDRSync 必须使用独立端口。8088 是推荐值，可通过 install.sh 交互配置或手动修改 Nginx 配置更改。

### MySQL 权限要求

执行 install.sh 的 MySQL 账号需要以下权限：

```sql
-- 元数据库 geodrsync 的完整权限
GRANT ALL PRIVILEGES ON geodrsync.* TO 'root'@'%';

-- 同步源端的读取权限（需要读取所有用户库表）
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'root'@'%';

-- 同步目标端的写入权限（需要写入所有用户库表）
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%';

FLUSH PRIVILEGES;
```

生产环境建议为 GeoDRSync 创建专用账号而非使用 root，但需确保该账号对源端所有用户库有 SELECT 权限、对目标端所有用户库有 CREATE/INSERT/UPDATE/DELETE/ALTER/DROP 权限。

## 部署包准备

### 获取部署包

部署包包含三个文件，需放在同一目录下：

| 文件 | 来源 | 说明 |
|------|------|------|
| `flink-cdc-sync.jar` | `mvn -DskipTests package` 构建产物 | 应用主包，约 80 MB |
| `install.sh` | `deploy/install.sh` | 一键安装脚本 |
| `init.sql` | `src/main/resources/db/init.sql` | 数据库初始化脚本 |

### 本地构建

如果从源码构建，执行以下命令：

```bash
cd D:\WorkSpace\flink-cdc-sync\flink-cdc-sync
mvn -DskipTests package -q
```

构建产物位于 `target/flink-cdc-sync.jar`。将此文件与 `deploy/install.sh`、`src/main/resources/db/init.sql` 一起上传到目标服务器。

### 上传到服务器

使用 scp 或 sftp 上传三个文件到服务器任意目录（如 `/root/deploy/`）：

```bash
# 从本地上传到服务器
scp flink-cdc-sync.jar root@192.168.88.88:/root/deploy/
scp install.sh root@192.168.88.88:/root/deploy/
scp init.sql root@192.168.88.88:/root/deploy/
```

如果使用 Windows 且没有 scp，可用 WinSCP、FileZilla 等图形化工具上传。

## 一键安装

### 安装前确认

执行安装脚本前，先确认源端 MySQL 已开启 binlog 并设置为 ROW 格式：

```bash
mysql -h <源端IP> -u root -p -e "SHOW VARIABLES LIKE 'binlog_format'; SHOW VARIABLES LIKE 'log_bin';"
```

如果 `binlog_format` 不是 `ROW` 或 `log_bin` 不是 `ON`，需先修改源端 MySQL 的 `my.cnf`（见 [MySQL Binlog 配置要求](#mysql-binlog-配置要求)）并重启 MySQL。

### 执行安装

确保三个文件在同一目录下，以 root 身份执行：

```bash
cd /root/deploy
sudo bash install.sh
```

### 交互式配置

脚本启动后依次提示输入以下信息，MySQL 凭据无默认值，必须手动输入：

```
========== 步骤 1/8: 前置检查 ==========
[INFO] root 权限确认 OK
[INFO] 找到 JAR 包: /root/deploy/flink-cdc-sync.jar

========== 步骤 2/8: 交互式配置 ==========

请配置 MySQL 数据库连接信息（无默认值，必须手动输入）：

MySQL 主机地址: 192.168.88.88
MySQL 端口: 3306
MySQL 用户名: root
MySQL 密码: ********
确认密码: ********

GeoDRSync 后端服务端口配置：
后端服务端口 (默认 8090): 8090

数据库同步映射配置（源主机数据 → 目标主机）：
  提示: 灾备中心本机部署时，源和目标通常都指向本机 MySQL
同步源主机 IP (回车=使用 MySQL 主机地址 192.168.88.88):
同步目标主机 IP (回车=使用本机 MySQL 192.168.88.88):

配置汇总:
  MySQL: root@192.168.88.88:3306
  后端端口: 8090
  同步源: 192.168.88.88:3306
  同步目标: 192.168.88.88:3306

确认以上配置正确? (y/N): y
```

### 安装流程

确认配置后，脚本自动执行剩余 6 个步骤：

**步骤 3/8 — 安装系统依赖**：检测 Java 17+、Nginx、MySQL 客户端，缺失时自动通过 yum 安装。

**步骤 4/8 — 测试 MySQL 连接**：使用输入的凭据执行 `SELECT 1` 验证连通性。失败则中止安装。

**步骤 5/8 — 创建数据库并初始化表结构**：创建 `geodrsync` 元数据库，执行 `init.sql` 创建 6 张管控表（sync_progress、dbha_switch_status、source_latest_binlog_info、sync_restart_task、sys_user、operation_log），并为 sync_progress 添加 user_id 列。

**步骤 6/8 — 部署应用程序**：创建 `/data/geodrsync/` 目录结构，复制 jar 包和前端资源，根据输入的凭据生成 `application-linux.yml` 配置文件。

**步骤 7/8 — 配置 systemd 服务**：创建 `/etc/systemd/system/geodrsync.service`，配置开机自启和故障自动重启。

**步骤 8/8 — 配置 Nginx 并启动服务**：创建 Nginx vhost 配置文件，重载 Nginx，启动 systemd 服务，执行健康检查（最多等待 60 秒）。

### 安装完成

安装成功后输出：

```
============================================================
  GeoDRSync 灾备同步服务部署完成！
============================================================

  访问地址:
    前端页面:  http://192.168.88.88/
    后端 API:  http://192.168.88.88:8090/actuator/health
    默认账号:  admin / admin123

  配置文件:    /data/geodrsync/conf/application-linux.yml
  日志目录:    /data/geodrsync/logs/
  数据目录:    /data/geodrsync/
============================================================
```

## 宝塔面板环境特殊配置

如果服务器使用宝塔面板管理 Nginx，install.sh 生成的配置文件路径可能不正确。宝塔面板的 Nginx vhost include 路径是 `/www/server/panel/vhost/nginx/*.conf`，而非 `/www/server/nginx/conf/vhost/`。

### 症状

执行 install.sh 后 Nginx 配置测试通过，但 80 端口访问返回 404 或 301 重定向到其他应用。

### 诊断

```bash
# 检查 Nginx 实际 include 的 vhost 目录
grep -n 'include.*vhost' /www/server/nginx/conf/nginx.conf

# 输出类似:
# 103:include /www/server/panel/vhost/nginx/*.conf;

# 检查配置文件是否在正确位置
ls -la /www/server/panel/vhost/nginx/geodrsync.conf
ls -la /www/server/nginx/conf/vhost/geodrsync.conf
```

### 修复

将配置文件移动到宝塔面板的 vhost 目录：

```bash
mv /www/server/nginx/conf/vhost/geodrsync.conf \
   /www/server/panel/vhost/nginx/geodrsync.conf
nginx -t && systemctl restart nginx
```

### 端口冲突处理

宝塔面板环境下 80 和 443 端口通常已被其他应用占用。如果 `geodrsync.conf` 中 `listen 80;` 与其他 vhost 的 `server_name _;` 冲突，Nginx 会发出警告：

```
[warn] conflicting server name "_" on 0.0.0.0:80, ignored
```

此时 GeoDRSync 的配置会被忽略，80 端口的请求被其他应用拦截。解决方案是让 GeoDRSync 使用独立端口：

```nginx
# 编辑 /www/server/panel/vhost/nginx/geodrsync.conf
server {
    listen 8088;          # 改为独立端口
    server_name _;
    # ... 其余配置不变
}
```

修改后重载 Nginx 并开放防火墙：

```bash
nginx -t && systemctl restart nginx
firewall-cmd --add-port=8088/tcp --permanent
firewall-cmd --reload
```

## 手动安装

如果一键脚本不适用（如离线环境、非 root 用户），可按以下步骤手动部署。

### 0. 配置源端 MySQL Binlog

源端 MySQL 必须开启 binlog 并设置 `binlog_format=ROW`。编辑源端 MySQL 的 `my.cnf`（通常位于 `/etc/my.cnf` 或 `/etc/mysql/my.cnf`），在 `[mysqld]` 段添加：

```ini
[mysqld]
log-bin=mysql-bin
binlog_format=ROW
binlog_row_image=FULL
server_id=1
```

修改后重启 MySQL：

```bash
# CentOS
systemctl restart mysqld

# Ubuntu
systemctl restart mysql
```

验证：

```bash
mysql -u root -p -e "SHOW VARIABLES LIKE 'binlog_format'; SHOW VARIABLES LIKE 'log_bin'; SHOW MASTER STATUS;"
# binlog_format 应为 ROW，log_bin 应为 ON，SHOW MASTER STATUS 应返回 File 和 Position
```

### 1. 安装 Java 17

```bash
# CentOS / RHEL
yum install -y java-17-openjdk-devel

# Ubuntu / Debian
apt update && apt install -y openjdk-17-jdk

# 验证
java -version
# 输出应包含 "17"
```

### 2. 安装 Nginx

```bash
# CentOS / RHEL
yum install -y nginx
systemctl enable nginx

# Ubuntu / Debian
apt install -y nginx
systemctl enable nginx
```

### 3. 创建数据库

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p < init.sql
```

init.sql 会创建 `geodrsync` 元数据库及 6 张管控表，同时创建示例业务库 `geo_source`、`geo_target`、`biz_source`、`biz_target`。

手动创建 operation_log 表（init.sql 不含此表，由 install.sh 动态创建）：

```sql
USE geodrsync;

CREATE TABLE IF NOT EXISTS operation_log (
    ID              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    USER_ID         BIGINT       DEFAULT NULL COMMENT '操作用户ID',
    USERNAME        VARCHAR(64)  DEFAULT NULL COMMENT '操作用户名',
    OPERATION_TYPE  VARCHAR(64)  NOT NULL    COMMENT '操作类型',
    OPERATION_DESC  VARCHAR(255) DEFAULT NULL COMMENT '操作描述',
    REQUEST_URL     VARCHAR(512) DEFAULT NULL COMMENT '请求URL',
    REQUEST_METHOD  VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP方法',
    REQUEST_PARAMS  TEXT         DEFAULT NULL COMMENT '请求参数(密码已脱敏)',
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

ALTER TABLE sync_progress ADD COLUMN USER_ID BIGINT DEFAULT NULL COMMENT '所属用户ID' AFTER TARGET_DB_NAME;
```

### 4. 部署应用

```bash
# 创建目录结构
mkdir -p /data/geodrsync/{bin,logs,conf,conf/db,frontend,savepoint}

# 复制文件
cp flink-cdc-sync.jar /data/geodrsync/bin/
cp init.sql /data/geodrsync/conf/db/

# 上传前端资源（index.html 和 app.js）
# 从构建机复制 frontend/ 目录下的 index.html 和 app.js
cp index.html app.js /data/geodrsync/frontend/
```

### 5. 创建配置文件

创建 `/data/geodrsync/conf/application-linux.yml`，将占位符替换为实际值：

```yaml
server:
  port: 8090
  servlet:
    context-path: /

spring:
  application:
    name: flink-cdc-sync
  web:
    resources:
      cache:
        cachecontrol:
          no-store: true
          no-cache: true
          max-age: 0
  datasource:
    url: jdbc:mysql://<MYSQL_HOST>:<MYSQL_PORT>/geodrsync?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: <MYSQL_USER>
    password: <MYSQL_PASS>
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
  global-config:
    db-config:
      id-type: auto
      table-prefix: ""

geodrsync:
  standby-mode: true
  flink:
    enabled: false
    parallelism: 2
    checkpoint-interval-ms: 30000
    savepoint-path: /data/geodrsync/savepoint
  engine:
    poll-interval-ms: 2000
    batch-size: 1000
    max-queue-size: 20000
    reconcile-interval-ms: 10000
    deviation-timeout-sec: 300
  connection:
    max-pool-size: 32
    max-global-connections: 200
  mappings:
    - source-host: <SYNC_SOURCE_HOST>
      source-port: <MYSQL_PORT>
      source-user: <MYSQL_USER>
      source-password: <MYSQL_PASS>
      target-host: <SYNC_TARGET_HOST>
      target-port: <MYSQL_PORT>
      target-user: <MYSQL_USER>
      target-password: <MYSQL_PASS>
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

### 6. 配置 systemd 服务

创建 `/etc/systemd/system/geodrsync.service`：

```ini
[Unit]
Description=GeoDRSync Disaster Recovery Sync Service
After=network.target mysqld.service

[Service]
Type=simple
User=root
WorkingDirectory=/data/geodrsync
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /data/geodrsync/bin/flink-cdc-sync.jar --spring.profiles.active=linux --spring.config.additional-location=/data/geodrsync/conf/
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
StandardOutput=append:/data/geodrsync/logs/app.out
StandardError=append:/data/geodrsync/logs/app.err

[Install]
WantedBy=multi-user.target
```

启用并启动：

```bash
systemctl daemon-reload
systemctl enable geodrsync
systemctl start geodrsync
```

### 7. 配置 Nginx 反向代理

创建 Nginx vhost 配置文件。路径取决于 Nginx 安装方式：

| 安装方式 | vhost 目录 |
|----------|-----------|
| 宝塔面板 | `/www/server/panel/vhost/nginx/` |
| yum / apt 标准安装 | `/etc/nginx/conf.d/` |

创建 `geodrsync.conf`：

```nginx
server {
    listen 8088;
    server_name _;

    location / {
        root /data/geodrsync/frontend;
        index index.html;
        try_files $uri $uri/ /index.html;
    }

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

测试并重载：

```bash
nginx -t
systemctl restart nginx

# 开放防火墙
firewall-cmd --add-port=8088/tcp --permanent
firewall-cmd --reload
```

## 安装验证

### 服务状态

```bash
systemctl status geodrsync
# 应显示 active (running)
```

### 端口监听

```bash
ss -tlnp | grep -E ':(8088|8090) '
# 应看到 nginx 监听 8088，java 监听 8090
```

### 健康检查

```bash
curl http://127.0.0.1:8090/actuator/health
# 应返回 {"status":"UP",...}
```

### 前端访问

浏览器打开 `http://<服务器IP>:8088/`，应显示登录页面。默认账号 `admin` / `admin123`。

### 登录测试

```bash
curl -X POST http://127.0.0.1:8088/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# 应返回 {"code":"0","message":"success","data":{"username":"admin"},"success":true}
```

### 同步引擎

登录后在 Web 后台查看"同步进度"页面，应显示引擎已自动发现源端用户库并开始同步。也可通过 API 检查：

```bash
# 先登录获取 Cookie
curl -c cookies.txt -X POST http://127.0.0.1:8088/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 查看同步状态
curl -b cookies.txt http://127.0.0.1:8088/sync/status
# 应返回 {"status":"NORMAL","desc":"正常"}

# 查看同步进度
curl -b cookies.txt http://127.0.0.1:8088/sync/progress
```

## 目录结构

安装完成后的目录布局：

```
/data/geodrsync/
├── bin/
│   └── flink-cdc-sync.jar      # 应用主包
├── conf/
│   ├── application-linux.yml   # 运行配置（含 MySQL 凭据）
│   └── db/
│       └── init.sql            # 数据库初始化脚本
├── frontend/
│   ├── index.html              # 前端入口
│   └── app.js                  # 前端应用（JSX 预编译产物）
├── logs/
│   ├── app.log                 # 应用主日志
│   ├── app.out                 # 标准输出
│   └── app.err                 # 错误输出
└── savepoint/                  # Savepoint 目录（Flink 模式使用）
```

关键系统文件：

| 文件 | 用途 |
|------|------|
| `/etc/systemd/system/geodrsync.service` | systemd 服务定义 |
| `/www/server/panel/vhost/nginx/geodrsync.conf` | Nginx vhost（宝塔面板） |
| `/etc/nginx/conf.d/geodrsync.conf` | Nginx vhost（标准安装） |

## 服务管理

```bash
# 启动
systemctl start geodrsync

# 停止
systemctl stop geodrsync

# 重启（修改配置后需要重启生效）
systemctl restart geodrsync

# 查看状态
systemctl status geodrsync

# 查看实时系统日志
journalctl -u geodrsync -f

# 查看应用日志
tail -f /data/geodrsync/logs/app.log

# Nginx 重载（修改 Nginx 配置后）
nginx -t && systemctl reload nginx
```

## 配置说明

主配置文件 `/data/geodrsync/conf/application-linux.yml` 在安装时由 install.sh 根据用户输入生成。核心配置项：

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

### 修改配置

修改 `application-linux.yml` 后有两种生效方式：

**方式一：重启服务**（修改 datasource、port 等核心配置时必须重启）

```bash
systemctl restart geodrsync
```

**方式二：配置热生效**（修改忽略规则、字段映射等同步配置时无需重启）

```bash
# 先登录获取 Cookie
curl -c cookies.txt -X POST http://127.0.0.1:8088/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 增量更新单个主机对配置
curl -b cookies.txt -X POST http://127.0.0.1:8088/sync/mapping/update \
  -H "Content-Type: application/json" \
  -d '{"sourceHost":"192.168.88.88","ignoreDatabases":["day01","day02"]}'

# 全量重载所有配置
curl -b cookies.txt -X POST http://127.0.0.1:8088/sync/mapping/reload
```

### 忽略规则语法

三种匹配语法可在同一规则集中混用：

| 语法 | 示例 | 说明 |
|------|------|------|
| 精确匹配 | `db_name.table_name` | 完全匹配库表名 |
| Glob 通配 | `db_*.log_*` | 支持 `*` 通配符 |
| 正则匹配 | `re:^shard_\d+\..*` | `re:` 前缀触发正则模式 |

## 升级

### 升级应用（不改动配置和数据库）

```bash
# 1. 停止服务
systemctl stop geodrsync

# 2. 备份旧 jar
cp /data/geodrsync/bin/flink-cdc-sync.jar /data/geodrsync/bin/flink-cdc-sync.jar.bak

# 3. 替换 jar 包
cp /root/deploy/flink-cdc-sync.jar /data/geodrsync/bin/

# 4. 如有新版前端资源，一并更新
cp app.js index.html /data/geodrsync/frontend/

# 5. 启动服务
systemctl start geodrsync

# 6. 验证
systemctl status geodrsync
curl http://127.0.0.1:8090/actuator/health
```

### 升级数据库结构

如果新版本包含数据库结构变更，先备份数据库再执行升级 SQL：

```bash
# 备份
mysqldump -u root -p geodrsync > /tmp/geodrsync_backup_$(date +%Y%m%d).sql

# 执行升级 SQL（参考新版本的 release notes）
mysql -u root -p geodrsync < upgrade_v2.1.sql
```

## 故障排查

### 服务无法启动

```bash
# 查看 systemd 日志
journalctl -u geodrsync -n 100

# 查看应用错误日志
tail -100 /data/geodrsync/logs/app.err
```

常见原因：

| 现象 | 原因 | 解决方案 |
|------|------|----------|
| `Communications link failure` | MySQL 不可达 | 检查 application-linux.yml 中的 host/port/凭据 |
| `Port 8090 already in use` | 端口被占用 | `ss -tlnp \| grep 8090` 查看占用进程 |
| `Unsupported class file version` | Java 版本过低 | `java -version` 确认 >= 17 |
| `Access denied for user` | MySQL 权限不足 | 检查账号是否有 geodrsync.* 的 ALL 权限 |

### 8088 端口无法访问

```bash
# 1. 确认 Nginx 监听 8088
ss -tlnp | grep 8088

# 2. 确认防火墙已开放
firewall-cmd --list-ports | grep 8088

# 3. 如果未监听，检查 Nginx 配置是否在正确目录
# 宝塔面板: /www/server/panel/vhost/nginx/geodrsync.conf
# 标准安装: /etc/nginx/conf.d/geodrsync.conf

# 4. 测试 Nginx 配置
nginx -t

# 5. 重启 Nginx
systemctl restart nginx
```

### 前端页面白屏

```bash
# 检查前端文件是否存在
ls -la /data/geodrsync/frontend/
# 应包含 index.html 和 app.js

# 如果缺失，从部署包重新上传
cp index.html app.js /data/geodrsync/frontend/
```

### 同步引擎不工作

```bash
# 查看同步状态
curl -b cookies.txt http://127.0.0.1:8088/sync/status

# 查看应用日志中的同步引擎日志
grep -E "SyncHostJob|ScanNewDatabaseJob" /data/geodrsync/logs/app.log | tail -50

# 常见原因：
# 1. standby-mode 为 false — 同步引擎不启动
# 2. 源端 MySQL 不可达 — 检查 mappings 中的 source-host
# 3. 所有库被 ignore-databases 排除 — 检查忽略规则
# 4. 大表阻塞 — 查看日志是否有 "streamScan progress" 卡在某张表
# 5. 源端 binlog 未开启 — SHOW VARIABLES LIKE 'log_bin' 应为 ON
# 6. binlog_format 不是 ROW — SHOW VARIABLES LIKE 'binlog_format' 应为 ROW
```

### DDL 同步失败

跨版本同步时，如果目标端执行 DDL 报错，通常是 DDL 清洗未能覆盖某些 8.0 专有语法：

```bash
# 查看应用日志中的 DDL 相关错误
grep -E "DDL|sanitize|ALTER|CREATE TABLE" /data/geodrsync/logs/app.log | grep -i error | tail -20

# 常见错误:
# Error 1273 (HY000): Unknown collation: 'utf8mb4_0900_ai_ci'
#   → 清洗函数已处理，如果仍出现说明 DDL 中包含未被正则匹配到的变体
# Error 1064 (42000): You have an error in your SQL syntax
#   → 8.0 新语法未被清洗函数覆盖，需查看日志中的原始 DDL 文本
```

### 忘记管理员密码

```sql
-- 连接到 geodrsync 元数据库
USE geodrsync;

-- 删除 admin 用户
DELETE FROM sys_user WHERE USERNAME = 'admin';
```

重启服务后，`SysUserInitializer` 会自动重新创建默认 admin 账号（admin / admin123）：

```bash
systemctl restart geodrsync
```

## 卸载

```bash
# 1. 停止并禁用服务
systemctl stop geodrsync
systemctl disable geodrsync

# 2. 删除 systemd 服务文件
rm -f /etc/systemd/system/geodrsync.service
systemctl daemon-reload

# 3. 删除 Nginx 配置
rm -f /www/server/panel/vhost/nginx/geodrsync.conf   # 宝塔面板
# 或
rm -f /etc/nginx/conf.d/geodrsync.conf                # 标准安装
systemctl restart nginx

# 4. 删除应用目录
rm -rf /data/geodrsync

# 5. 删除元数据库（谨慎操作，会丢失所有同步进度）
mysql -u root -p -e "DROP DATABASE geodrsync;"
```

## 部署检查清单

安装完成后逐项确认：

| 检查项 | 命令 | 预期结果 |
|--------|------|----------|
| systemd 服务 | `systemctl is-active geodrsync` | `active` |
| 后端端口 | `ss -tlnp \| grep 8090` | java 进程监听 |
| Nginx 端口 | `ss -tlnp \| grep 8088` | nginx 进程监听 |
| 健康检查 | `curl http://127.0.0.1:8090/actuator/health` | `{"status":"UP"}` |
| 前端页面 | `curl -I http://127.0.0.1:8088/` | `HTTP/1.1 200 OK` |
| 登录接口 | `curl -X POST http://127.0.0.1:8088/auth/login ...` | `{"success":true}` |
| 同步状态 | Web 后台或 `/sync/status` | `{"status":"NORMAL"}` |
| 数据库表 | `SHOW TABLES FROM geodrsync;` | 6 张管控表 |
| 防火墙 | `firewall-cmd --list-ports` | 包含 8088/tcp |
| 源端 binlog | `SHOW VARIABLES LIKE 'binlog_format';` | `ROW` |
| 源端 log_bin | `SHOW VARIABLES LIKE 'log_bin';` | `ON` |
| 源端 MASTER STATUS | `SHOW MASTER STATUS;` | 返回 File 和 Position |

全部通过后部署完成，可正常使用。
