package com.example.remotedatasync.dto;

import com.example.remotedatasync.bean.TransformRule;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 字段转换规则请求体：指定（数据库-表-字段）的源值 -> 目标值替换。
 *
 * @author 50707
 */
@Data
public class TransformRuleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 源库名；* 表示任意库 */
    @NotBlank(message = "规则库名不能为空")
    private String dbName;

    /** 表名；* 表示任意表 */
    @NotBlank(message = "规则表名不能为空")
    private String tableName;

    /** 字段名 */
    @NotBlank(message = "规则字段名不能为空")
    private String fieldName;

    /** 源数据值（命中后替换） */
    @NotBlank(message = "源值不能为空")
    private String sourceValue;

    /** 目标值（可空，表示置空） */
    private String targetValue;

    public TransformRule toRule() {
        TransformRule r = new TransformRule();
        r.setDbName(dbName);
        r.setTableName(tableName);
        r.setFieldName(fieldName);
        r.setSourceValue(sourceValue);
        r.setTargetValue(targetValue);
        return r;
    }
}
