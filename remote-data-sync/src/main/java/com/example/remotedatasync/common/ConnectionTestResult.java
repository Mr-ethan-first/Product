package com.example.remotedatasync.common;

import lombok.Data;

/**
 * 数据库连接测试结果（含成功标志与失败原因，便于前端精确展示）。
 *
 * @author 50707
 */
@Data
public class ConnectionTestResult {

    /** 是否连接成功 */
    private boolean ok;

    /** 结果描述：成功时为 "OK"，失败时为异常原因 */
    private String message;

    /** 被测试的 JDBC URL（脱敏，不含密码） */
    private String url;

    public static ConnectionTestResult ok(String url) {
        ConnectionTestResult r = new ConnectionTestResult();
        r.ok = true;
        r.message = "OK";
        r.url = url;
        return r;
    }

    public static ConnectionTestResult fail(String url, String reason) {
        ConnectionTestResult r = new ConnectionTestResult();
        r.ok = false;
        r.message = reason == null ? "unknown error" : reason;
        r.url = url;
        return r;
    }
}
