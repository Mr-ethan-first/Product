package com.example.remotedatasync;

import com.example.remotedatasync.common.PageRespVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DRPlatform 全链路自动化测试（主机对模型 / 纯忽略模式）：
 * <p>
 * 本环境 192.168.88.88:3306 真实可达，测试用默认主机对 127.0.0.1 -> 192.168.88.88 做
 * "跨主机灾备"验证：源主机 127.0.0.1 下所有用户库会以<b>同名库</b>镜像方式实时同步到灾备主机
 * 192.168.88.88（新增库 / 新增表 / 增量 / 删除对账均在下一轮自动纳入，无需逐项配置）。
 * 覆盖 6 个 REST 接口 + 数据一致性 + 映射 CRUD。
 *
 * @author 50707
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DRPlatformConsistencyTest {

    /** 生产中心（源主机） */
    private static final String SRC_HOST = "127.0.0.1";
    /** 灾备中心（目标主机，本环境真实可达） */
    private static final String TGT_HOST = "192.168.88.88";
    private static final String USER = "root";
    private static final String PASS = "123456";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ----------------------------- REST 接口测试 -----------------------------

    @Test
    void testStatusEndpoint() {
        ResponseEntity<Map> r = rest.getForEntity(base() + "/sync/status", Map.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), "status 接口应 200");
        Map body = r.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("status"), "应返回 status 字段");
        assertTrue(body.containsKey("desc"), "应返回 desc 字段");
    }

    @Test
    void testIpListEndpoint() {
        ResponseEntity<List> r = rest.getForEntity(base() + "/sync/ipList", List.class);
        assertTrue(r.getStatusCode().is2xxSuccessful());
        List<?> list = r.getBody();
        assertNotNull(list);
        assertTrue(list.size() >= 2, "至少应包含生产中心与灾备中心两个节点");
    }

    @Test
    void testDbListEndpoint() {
        String body = "{\"page\":1,\"pageSize\":10,\"condition\":{}}";
        ResponseEntity<PageRespVO> r = rest.exchange(base() + "/sync/db/list", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), PageRespVO.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), "db/list 应 200");
        assertNotNull(r.getBody());
    }

    @Test
    void testDatabasesByIp() {
        ResponseEntity<List> r = rest.getForEntity(base() + "/sync/databases/127.0.0.1", List.class);
        assertTrue(r.getStatusCode().is2xxSuccessful());
        List<?> list = r.getBody();
        assertNotNull(list);
        assertTrue(list.contains("geo_source"), "应至少发现源主机下的 geo_source 用户库");
    }

    @Test
    void testResyncAndDetail() {
        // 先触发一次重新同步（全量重置），按源主机 IP 匹配主机对
        String payload = "[{\"ip\":\"127.0.0.1\",\"dbName\":\"geo_source\"}]";
        ResponseEntity<Map> r = rest.exchange(base() + "/sync/resyncDatabases", HttpMethod.POST,
                new HttpEntity<>(payload, jsonHeaders()), Map.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), "resync 应 200");

        // 等待同步完成后，查询进度列表取首个 ID 做详情校验
        sleep(6000);
        String listBody = "{\"page\":1,\"pageSize\":10,\"condition\":{}}";
        ResponseEntity<PageRespVO> lr = rest.exchange(base() + "/sync/db/list", HttpMethod.POST,
                new HttpEntity<>(listBody, jsonHeaders()), PageRespVO.class);
        PageRespVO<?> page = lr.getBody();
        assertNotNull(page);
        if (page.getResults() != null && !page.getResults().isEmpty()) {
            Object first = page.getResults().get(0);
            Long id = ((Number) ((Map<?, ?>) first).get("id")).longValue();
            ResponseEntity<Map> dr = rest.getForEntity(base() + "/sync/" + id, Map.class);
            assertTrue(dr.getStatusCode().is2xxSuccessful(), "detail 应 200");
        }
    }

    // ----------------------------- 数据一致性测试（同名库跨主机镜像） -----------------------------

    @Test
    void testDataConsistencyInsertAndDelete() {
        String marker = "IT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String orderNo = marker + "_ORD";
        String username = marker + "_USR";

        // 1) 源库插入一行 order + 一行 user
        execute(SRC_HOST, "geo_source",
                "INSERT INTO t_order (order_no,user_id,amount,status,remark) VALUES ('" + orderNo + "',7777,12.34,1,'it') "
                        + "ON DUPLICATE KEY UPDATE amount=12.34");
        execute(SRC_HOST, "geo_source",
                "INSERT INTO t_user (username,age,email) VALUES ('" + username + "',30,'it@x.com') "
                        + "ON DUPLICATE KEY UPDATE age=30");

        // 2) 等待同步（同名库镜像到灾备主机 192.168.88.88）
        boolean inserted = waitFor(() -> {
            long src = count(SRC_HOST, "geo_source", "t_order", "order_no='" + orderNo + "'")
                    + count(SRC_HOST, "geo_source", "t_user", "username='" + username + "'");
            long tgt = count(TGT_HOST, "geo_source", "t_order", "order_no='" + orderNo + "'")
                    + count(TGT_HOST, "geo_source", "t_user", "username='" + username + "'");
            return src == 2 && tgt == 2;
        }, Duration.ofSeconds(30));
        assertTrue(inserted, "插入后灾备主机应与源主机一致(各 1 行)");

        // 3) 整体行数一致性：源 == 灾备（同名库）
        boolean consistent = waitFor(() ->
                count(SRC_HOST, "geo_source", "t_order", null) == count(TGT_HOST, "geo_source", "t_order", null)
                        && count(SRC_HOST, "geo_source", "t_user", null) == count(TGT_HOST, "geo_source", "t_user", null),
                Duration.ofSeconds(30));
        assertTrue(consistent, "t_order / t_user 源/灾备整体行数应一致");

        // 4) 源库删除这两行 -> 灾备库应通过对账删除
        execute(SRC_HOST, "geo_source", "DELETE FROM t_order WHERE order_no='" + orderNo + "'");
        execute(SRC_HOST, "geo_source", "DELETE FROM t_user WHERE username='" + username + "'");

        boolean deleted = waitFor(() ->
                count(TGT_HOST, "geo_source", "t_order", "order_no='" + orderNo + "'") == 0
                        && count(TGT_HOST, "geo_source", "t_user", "username='" + username + "'") == 0,
                Duration.ofSeconds(30));
        assertTrue(deleted, "源库删除后，灾备库应通过删除对账移除对应行");

        // 5) 清理（确保测试可重复）
        execute(SRC_HOST, "geo_source", "DELETE FROM t_order WHERE order_no='" + orderNo + "'");
        execute(SRC_HOST, "geo_source", "DELETE FROM t_user WHERE username='" + username + "'");
        execute(TGT_HOST, "geo_source", "DELETE FROM t_order WHERE order_no='" + orderNo + "'");
        execute(TGT_HOST, "geo_source", "DELETE FROM t_user WHERE username='" + username + "'");
    }

    // ----------------------------- 多库同步测试（自动发现新库/新表 + 历史 + 实时） -----------------------------

    @Test
    void testMultiDatabaseHistoricalAndRealtime() {
        // 准备第二个库（生产中心 biz_source），灾备端由引擎按同名自动建库建表
        execute(SRC_HOST, "mysql", "CREATE DATABASE IF NOT EXISTS biz_source");
        execute(SRC_HOST, "biz_source",
                "CREATE TABLE IF NOT EXISTS t_customer ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(64), phone VARCHAR(32), "
                        + "balance DECIMAL(10,2), create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
                        + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        execute(SRC_HOST, "biz_source", "TRUNCATE TABLE t_customer");

        // 1) 历史全量：预置 3 行，等待引擎首次全量扫描同步到灾备主机同名库
        for (int i = 1; i <= 3; i++) {
            execute(SRC_HOST, "biz_source", "INSERT INTO t_customer (name,phone,balance) VALUES ('cust" + i + "','1380000000" + i + "'," + (100 * i) + ")");
        }
        boolean historical = waitFor(() ->
                count(SRC_HOST, "biz_source", "t_customer", null) == 3
                        && count(TGT_HOST, "biz_source", "t_customer", null) == 3, Duration.ofSeconds(40));
        assertTrue(historical, "历史全量同步：biz_source(127.0.0.1) 与 biz_source(192.168.88.88) 行数应一致(各 3)");

        // 2) 实时插入
        execute(SRC_HOST, "biz_source", "INSERT INTO t_customer (name,phone,balance) VALUES ('rt_live','13900000000',999.99)");
        boolean inserted = waitFor(() -> count(TGT_HOST, "biz_source", "t_customer", "name='rt_live'") == 1, Duration.ofSeconds(30));
        assertTrue(inserted, "实时插入：新增行应同步到灾备主机");

        // 3) 实时更新（通过 update_time 水位增量捕获）
        execute(SRC_HOST, "biz_source", "UPDATE t_customer SET balance=888.88, update_time=NOW() WHERE name='rt_live'");
        boolean updated = waitFor(() -> {
            double b = queryDecimal(TGT_HOST, "biz_source", "t_customer", "name='rt_live'", "balance");
            return Math.abs(b - 888.88) < 0.01;
        }, Duration.ofSeconds(30));
        assertTrue(updated, "实时更新：balance 变更应同步到灾备主机");

        // 4) 实时删除（通过主键对账删除多余行）
        execute(SRC_HOST, "biz_source", "DELETE FROM t_customer WHERE name='rt_live'");
        boolean deleted = waitFor(() -> count(TGT_HOST, "biz_source", "t_customer", "name='rt_live'") == 0, Duration.ofSeconds(30));
        assertTrue(deleted, "实时删除：源库删除后灾备主机应通过对账删除移除");

        // 5) 多个库整体一致性：geo_source 各自源==灾备
        boolean geoConsistent = waitFor(() ->
                (count(SRC_HOST, "geo_source", "t_order", null) + count(SRC_HOST, "geo_source", "t_user", null))
                        == (count(TGT_HOST, "geo_source", "t_order", null) + count(TGT_HOST, "geo_source", "t_user", null)),
                Duration.ofSeconds(30));
        assertTrue(geoConsistent, "geo 库对源/灾备应一致");
        assertEquals(3L, count(TGT_HOST, "biz_source", "t_customer", null), "biz 库对灾备应保留 3 行历史数据");

        // 6) 清理
        execute(SRC_HOST, "biz_source", "TRUNCATE TABLE t_customer");
        execute(TGT_HOST, "biz_source", "TRUNCATE TABLE t_customer");
    }

    // ----------------------------- 页面动态配置 / 连接测试端点（主机对模型） -----------------------------

    @Test
    void testMappingConfigEndpoints() {
        // 1) 连接测试（有效账号：127.0.0.1 -> 192.168.88.88 真实跨主机）
        String testPayload = "{\"sourceHost\":\"127.0.0.1\",\"sourcePort\":3306,\"sourceUser\":\"root\",\"sourcePassword\":\"123456\","
                + "\"targetHost\":\"192.168.88.88\",\"targetPort\":3306,\"targetUser\":\"root\",\"targetPassword\":\"123456\"}";
        ResponseEntity<Map> tr = rest.exchange(base() + "/sync/mapping/test", HttpMethod.POST,
                new HttpEntity<>(testPayload, jsonHeaders()), Map.class);
        assertTrue(tr.getStatusCode().is2xxSuccessful(), "mapping/test 应 200");
        Map tbody = tr.getBody();
        assertEquals("0", tbody.get("code"), "连接测试应通过");
        Map tdata = (Map) tbody.get("data");
        assertEquals(Boolean.TRUE, ((Map) tdata.get("source")).get("ok"), "源库连接应成功");
        assertEquals(Boolean.TRUE, ((Map) tdata.get("target")).get("ok"), "目标库连接应成功");

        // 2) 列表应包含配置中的默认主机对
        ResponseEntity<Map> lr = rest.getForEntity(base() + "/sync/mappings", Map.class);
        assertEquals("0", lr.getBody().get("code"));
        List<Map> list = (List<Map>) lr.getBody().get("data");
        assertTrue(list.stream().anyMatch(m -> "127.0.0.1->192.168.88.88".equals(m.get("instanceKey"))),
                "列表应包含默认主机对 127.0.0.1->192.168.88.88");

        // 3) 新增一个唯一主机对（127.0.0.1 -> 127.0.0.1，与默认不冲突且 localhost 可达）
        String addPayload = "{\"sourceHost\":\"127.0.0.1\",\"sourcePort\":3306,\"sourceUser\":\"root\",\"sourcePassword\":\"123456\","
                + "\"targetHost\":\"127.0.0.1\",\"targetPort\":3306,\"targetUser\":\"root\",\"targetPassword\":\"123456\"}";
        ResponseEntity<Map> ar = rest.exchange(base() + "/sync/mapping/add", HttpMethod.POST,
                new HttpEntity<>(addPayload, jsonHeaders()), Map.class);
        assertTrue(ar.getStatusCode().is2xxSuccessful(), "mapping/add 应 200");
        assertEquals("0", ar.getBody().get("code"), "新增主机对应成功");
        List<String> created = (List<String>) ((Map) ar.getBody().get("data")).get("created");
        assertNotNull(created);
        assertTrue(created.contains("127.0.0.1->127.0.0.1"), "created 应包含新主机对 key");
        String key = "127.0.0.1->127.0.0.1";

        // 4) 重复新增应被拦截
        ResponseEntity<Map> ar2 = rest.exchange(base() + "/sync/mapping/add", HttpMethod.POST,
                new HttpEntity<>(addPayload, jsonHeaders()), Map.class);
        assertEquals("2807002013", ar2.getBody().get("code"), "重复映射应返回已存在错误码");

        // 5) 列表应已包含新映射
        ResponseEntity<Map> lr2 = rest.getForEntity(base() + "/sync/mappings", Map.class);
        List<Map> list2 = (List<Map>) lr2.getBody().get("data");
        assertTrue(list2.stream().anyMatch(m -> key.equals(m.get("instanceKey"))), "列表应包含新增主机对");

        // 6) 移除新映射
        ResponseEntity<Map> rr = rest.exchange(base() + "/sync/mapping/remove", HttpMethod.POST,
                new HttpEntity<>("{\"instanceKey\":\"" + key + "\"}", jsonHeaders()), Map.class);
        assertTrue(rr.getStatusCode().is2xxSuccessful(), "mapping/remove 应 200");
        assertEquals("0", rr.getBody().get("code"), "移除应成功");

        // 7) 移除后列表不再包含该映射
        ResponseEntity<Map> lr3 = rest.getForEntity(base() + "/sync/mappings", Map.class);
        List<Map> list3 = (List<Map>) lr3.getBody().get("data");
        assertTrue(list3.stream().noneMatch(m -> key.equals(m.get("instanceKey"))), "移除后列表不应再包含该映射");
    }

    // ----------------------------- JDBC 工具（支持指定主机） -----------------------------

    private String url(String host, String db) {
        return "jdbc:mysql://" + host + ":3306/" + db
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    }

    private void execute(String host, String db, String sql) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection c = DriverManager.getConnection(url(host, db), USER, PASS);
                 Statement st = c.createStatement()) {
                st.execute(sql);
            }
        } catch (Exception e) {
            throw new RuntimeException("execute failed: " + sql, e);
        }
    }

    private long count(String host, String db, String table, String where) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String sql = "SELECT COUNT(1) FROM `" + table + "`" + (where == null ? "" : " WHERE " + where);
            try (Connection c = DriverManager.getConnection(url(host, db), USER, PASS);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            throw new RuntimeException("count failed: " + table, e);
        }
    }

    private double queryDecimal(String host, String db, String table, String where, String column) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String sql = "SELECT `" + column + "` FROM `" + table + "`" + (where == null ? "" : " WHERE " + where);
            try (Connection c = DriverManager.getConnection(url(host, db), USER, PASS);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getDouble(1) : Double.NaN;
            }
        } catch (Exception e) {
            throw new RuntimeException("queryDecimal failed: " + table, e);
        }
    }

    private boolean waitFor(java.util.function.BooleanSupplier cond, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                if (cond.getAsBoolean()) {
                    return true;
                }
            } catch (Exception ignore) {
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {
        }
    }
}
