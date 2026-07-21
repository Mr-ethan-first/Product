# 十库跨主机同步一致性测试报告（模板）

> 源：127.0.0.1:3306 (MySQL 8.0.12)  目标：192.168.88.88:3306 (MySQL 5.7.44)
> 同步引擎：remote-data-sync 内嵌引擎（application.yml 默认 profile，10 条映射）

## 一、环境准备
- 源端创建 10 个业务库 sync10_01..sync10_10，每库 t_biz 表（utf8mb4_general_ci，兼容 5.7），数据量 80/100/120/140/160/180/200/220/240/260 行。
- 目标端创建 10 个同名空库，表结构由同步引擎按 SHOW CREATE TABLE 自动镜像。
- 目标 MySQL 5.7 调高连接上限：max_connections=200、max_user_connections=100（默认仅 30/25，10 个跨主机作业即占满）。

## 二、发现并修复的关键缺陷：跨时区 datetime 偏移 +15h
- 现象：行数、金额汇总均一致，但 create_time / update_time 比源端多 15 小时。
- 根因：Connector/J 8.0 的 ResultSet.getObject() 对 DATETIME 列返回 java.time.LocalDateTime（无时区）；
  TableSinkFunction.setParam 将其经 Timestamp.valueOf(localDateTime) 按 JVM 默认时区（本机 PDT=UTC-7）转换，
  再经 ps.setTimestamp 按连接时区（Asia/Shanghai=UTC+8）写出，净偏移 = 8 − (−7) = 15h。
- 修复：
  1. TableSinkFunction：LocalDateTime 改为 ps.setObject(index, value) 字面量绑定（时区无关）。
  2. SyncDataBase.selectChanged：增量水位改为 ps.setObject(1, since) 字面量绑定。
  3. SyncDataBase：水位更新兼容 LocalDateTime 类型。
  4. RemoteDataSyncApplication：启动设置 JVM 默认时区 Asia/Shanghai（元数据时间戳也归中国时区）。

## 三、验证结果
（由 check_consistency.py 填充）
