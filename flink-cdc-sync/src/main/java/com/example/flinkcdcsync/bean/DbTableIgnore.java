package com.example.flinkcdcsync.bean;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 按「库 → 表」层级组织的忽略项：一个数据库（支持正则 / 通配）下，
 * 需要忽略的若干张表（表名支持精确 / glob / <code>re:</code> 正则）。
 * <p>
 * 例如：{ "database": "sales", "tables": ["t_log", "re:^tmp_.*"] } 表示
 * 在 sales 库下忽略 t_log 表以及所有 tmp_ 前缀表；而 { "database": "re:^app_.*", "tables": ["*_log"] }
 * 表示所有 app_ 前缀库下的 *_log 表均忽略。
 * </p>
 *
 * @author 50707
 */
@Data
public class DbTableIgnore implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据库匹配规则：精确名 / glob（* ?）/ re: 开头正则 */
    private String database;

    /** 该库下需要忽略的表规则列表（精确 / glob / re: 正则），支持多个 */
    private List<String> tables = new ArrayList<>();
}
