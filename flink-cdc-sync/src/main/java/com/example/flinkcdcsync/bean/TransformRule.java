package com.example.flinkcdcsync.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 字段内容转换规则：同步写入目标库前，对匹配（数据库-表-字段）的源值做替换。
 * <p>
 * dbName / tableName 支持通配符 {@code *}（表示“所有”）。
 * 仅对非主键列生效，避免破坏幂等 upsert 的主键匹配。
 * 典型场景：IP 替换、敏感字段脱敏、枚举值映射。
 * </p>
 *
 * @author 50707
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransformRule implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 源库名；* 表示任意库 */
    private String dbName;
    /** 表名；* 表示任意表 */
    private String tableName;
    /** 字段名 */
    private String fieldName;
    /** 源数据值（字符串比较，命中后替换） */
    private String sourceValue;
    /** 目标值（替换后的内容） */
    private String targetValue;

    /** 是否命中该规则（库名/表名支持通配） */
    public boolean matches(String db, String table, String field) {
        if (field == null || !field.equalsIgnoreCase(fieldName)) {
            return false;
        }
        boolean dbOk = "*".equals(dbName) || (db != null && db.equalsIgnoreCase(dbName));
        boolean tableOk = "*".equals(tableName) || (table != null && table.equalsIgnoreCase(tableName));
        return dbOk && tableOk;
    }
}
