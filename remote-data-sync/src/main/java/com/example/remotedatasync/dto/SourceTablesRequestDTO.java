package com.example.remotedatasync.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
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
    @Size(max = 255, message = "源主机长度不能超过255")
    private String sourceHost;
    @Min(value = 1, message = "源端口需在 1-65535 之间")
    @Max(value = 65535, message = "源端口需在 1-65535 之间")
    private int sourcePort = 3306;
    @NotBlank(message = "源库账号不能为空")
    @Size(max = 128, message = "源库账号长度不能超过128")
    private String sourceUser;
    @NotBlank(message = "源库密码不能为空")
    @Size(max = 256, message = "源库密码长度不能超过256")
    private String sourcePassword;

    /** 要查询表清单的库名 */
    @NotBlank(message = "库名不能为空")
    @Size(max = 255, message = "库名长度不能超过255")
    private String database;
}
