package com.example.flinkcdcsync.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 按源库连接信息 + 库名，查询该库下所有用户表（供页面「按库忽略表」下拉选择使用）。
 *
 * @author 50707
 */
@Data
public class SourceTablesRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "源主机不能为空")
    private String sourceHost;
    private int sourcePort = 3306;
    @NotBlank(message = "源库账号不能为空")
    private String sourceUser;
    @NotBlank(message = "源库密码不能为空")
    private String sourcePassword;

    /** 要查询表清单的库名 */
    @NotBlank(message = "库名不能为空")
    private String database;
}
