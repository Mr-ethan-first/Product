package com.example.remotedatasync.bean;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 表元信息（表名、主键列、全部列）。
 *
 * @author 50707
 */
@Data
public class TableInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 表名 */
    private String tableName;

    /** 主键列名（用于分区路由与幂等 upsert） */
    private List<String> primaryKeyColumns = new ArrayList<>();

    /** 全部列名 */
    private List<String> columns = new ArrayList<>();

    /** 是否为新增表（CREATE TABLE 触发后标记） */
    private boolean isNew = false;

    public TableInfo() {
    }

    public TableInfo(String tableName, List<String> primaryKeyColumns, List<String> columns) {
        this.tableName = tableName;
        this.primaryKeyColumns = primaryKeyColumns == null ? new ArrayList<>() : primaryKeyColumns;
        this.columns = columns == null ? new ArrayList<>() : columns;
    }
}
