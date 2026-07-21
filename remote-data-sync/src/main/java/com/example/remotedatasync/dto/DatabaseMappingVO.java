package com.example.remotedatasync.dto;

import com.example.remotedatasync.bean.DatabaseMapping;
import com.example.remotedatasync.bean.DbTableIgnore;
import com.example.remotedatasync.bean.TransformRule;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步主机对展示对象：密码脱敏，避免明文回传到前端。
 *
 * @author 50707
 */
@Data
public class DatabaseMappingVO {

    /** 所属用户 ID（null 表示全局/yml 配置，所有用户可见） */
    private Long userId;

    private String instanceKey;
    private String sourceHost;
    private int sourcePort;
    private String sourceUser;
    private String sourcePasswordMasked;

    private String targetHost;
    private int targetPort;
    private String targetUser;
    private String targetPasswordMasked;

    /** 作业是否正在运行 */
    private boolean running;

    /** 来源：yaml（配置文件）/ dynamic（页面动态添加） */
    private String source;

    /** 整库忽略 */
    private List<String> ignoreDatabases = new ArrayList<>();
    /** 表忽略（DML + DDL，旧式扁平） */
    private List<String> ignoreTables = new ArrayList<>();
    /** 仅忽略 DDL 的表（旧式扁平） */
    private List<String> ignoreDdlTables = new ArrayList<>();
    /** 按库层级忽略（DML + DDL） */
    private List<DbTableIgnore> ignoreTablesByDb = new ArrayList<>();
    /** 按库层级忽略（仅 DDL） */
    private List<DbTableIgnore> ignoreDdlTablesByDb = new ArrayList<>();
    /** 通用忽略（DML + DDL，所有库） */
    private List<String> commonIgnoreTables = new ArrayList<>();
    /** 通用忽略（仅 DDL，所有库） */
    private List<String> commonDdlIgnoreTables = new ArrayList<>();
    /** 字段转换规则 */
    private List<TransformRule> transformRules = new ArrayList<>();

    public static DatabaseMappingVO from(DatabaseMapping m, boolean running, String origin) {
        DatabaseMappingVO vo = new DatabaseMappingVO();
        vo.userId = m.getUserId();
        vo.instanceKey = m.getInstanceKey();
        vo.sourceHost = m.getSourceHost();
        vo.sourcePort = m.getSourcePort();
        vo.sourceUser = m.getSourceUser();
        vo.sourcePasswordMasked = mask(m.getSourcePassword());
        vo.targetHost = m.getTargetHost();
        vo.targetPort = m.getTargetPort();
        vo.targetUser = m.getTargetUser();
        vo.targetPasswordMasked = mask(m.getTargetPassword());
        vo.running = running;
        vo.source = origin;
        vo.ignoreDatabases = m.getIgnoreDatabases() == null ? new ArrayList<>() : new ArrayList<>(m.getIgnoreDatabases());
        vo.ignoreTables = m.getIgnoreTables() == null ? new ArrayList<>() : new ArrayList<>(m.getIgnoreTables());
        vo.ignoreDdlTables = m.getIgnoreDdlTables() == null ? new ArrayList<>() : new ArrayList<>(m.getIgnoreDdlTables());
        vo.ignoreTablesByDb = m.getIgnoreTablesByDb() == null ? new ArrayList<>() : new ArrayList<>(m.getIgnoreTablesByDb());
        vo.ignoreDdlTablesByDb = m.getIgnoreDdlTablesByDb() == null ? new ArrayList<>() : new ArrayList<>(m.getIgnoreDdlTablesByDb());
        vo.commonIgnoreTables = m.getCommonIgnoreTables() == null ? new ArrayList<>() : new ArrayList<>(m.getCommonIgnoreTables());
        vo.commonDdlIgnoreTables = m.getCommonDdlIgnoreTables() == null ? new ArrayList<>() : new ArrayList<>(m.getCommonDdlIgnoreTables());
        vo.transformRules = m.getTransformRules() == null ? new ArrayList<>() : new ArrayList<>(m.getTransformRules());
        return vo;
    }

    private static String mask(String pwd) {
        if (pwd == null) {
            return null;
        }
        return pwd.isEmpty() ? "" : "******";
    }
}
