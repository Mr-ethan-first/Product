package com.example.flinkcdcsync.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 同步进度查询条件 / 重新同步请求项。
 *
 * @author 50707
 */
@Data
public class SyncProgressQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    /** 生产中心 IP（重新同步 / 列表下拉用） */
    private String ip;
    /** 生产中心库名（page condition 用） */
    private String sourceDbName;
    /** 生产中心库名（重新同步请求体用，兼容 dbName） */
    private String dbName;
    private String targetIp;
    private String targetDbName;
    /** 同步状态 (0/1/2/3) */
    private Integer state;
    /** 偏差状态 (1/2) */
    private Integer deviationStatus;

    /** 统一获取库名（兼容 dbName / sourceDbName 两种入参） */
    public String getEffectiveDbName() {
        if (dbName != null && !dbName.isEmpty()) {
            return dbName;
        }
        return sourceDbName;
    }
}
