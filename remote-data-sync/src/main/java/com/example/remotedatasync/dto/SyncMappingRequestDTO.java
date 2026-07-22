package com.example.remotedatasync.dto;

import com.example.remotedatasync.bean.DatabaseMapping;
import com.example.remotedatasync.bean.DbTableIgnore;
import com.example.remotedatasync.bean.TransformRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 页面新增同步主机对请求：源/目标主机连接信息 + 忽略配置（库/表/DDL表）+ 字段转换规则。
 * <p>
 * 后端按"主机对"创建一条 DatabaseMapping（源主机 -> 目标主机，1:1 灾备），
 * 该源主机下所有用户库与表将被自动同步（忽略项除外），无需逐项勾选。
 * </p>
 *
 * @author 50707
 */
@Data
public class SyncMappingRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 源主机（生产中心） */
    @NotBlank(message = "源主机不能为空")
    @Size(max = 255, message = "源主机长度不能超过255")
    @Pattern(regexp = "^[a-zA-Z0-9.\\-:]+$", message = "源主机格式不合法（仅允许字母/数字/./-/:/:）")
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

    /** 目标主机（灾备中心） */
    @NotBlank(message = "目标主机不能为空")
    @Size(max = 255, message = "目标主机长度不能超过255")
    @Pattern(regexp = "^[a-zA-Z0-9.\\-:]+$", message = "目标主机格式不合法（仅允许字母/数字/./-/:/:）")
    private String targetHost;
    @Min(value = 1, message = "目标端口需在 1-65535 之间")
    @Max(value = 65535, message = "目标端口需在 1-65535 之间")
    private int targetPort = 3306;
    @NotBlank(message = "目标库账号不能为空")
    @Size(max = 128, message = "目标库账号长度不能超过128")
    private String targetUser;
    @NotBlank(message = "目标库密码不能为空")
    @Size(max = 256, message = "目标库密码长度不能超过256")
    private String targetPassword;

    /** 整库忽略（支持多个 + 正则） */
    private List<String> ignoreDatabases = new ArrayList<>();

    /** 表忽略：DML + DDL 均跳过（旧式扁平写法，支持多个 + 正则） */
    private List<String> ignoreTables = new ArrayList<>();

    /** 仅忽略 DDL 的表（旧式扁平写法，支持多个 + 正则） */
    private List<String> ignoreDdlTables = new ArrayList<>();

    /** 按「库 → 表」层级忽略（DML + DDL）：每个库可配置不同忽略表 */
    private List<DbTableIgnore> ignoreTablesByDb = new ArrayList<>();

    /** 按「库 → 表」层级忽略（仅 DDL）：每个库可配置不同忽略表 */
    private List<DbTableIgnore> ignoreDdlTablesByDb = new ArrayList<>();

    /** 通用忽略（DML + DDL），所有库生效，精确 + 正则 */
    private List<String> commonIgnoreTables = new ArrayList<>();

    /** 通用忽略（仅 DDL），所有库生效，精确 + 正则 */
    private List<String> commonDdlIgnoreTables = new ArrayList<>();

    /** 字段内容转换规则 */
    private List<TransformRuleDTO> transformRules = new ArrayList<>();

    /** 由请求构建主机对映射（1:1） */
    public List<DatabaseMapping> toMappings() {
        DatabaseMapping m = new DatabaseMapping();
        m.setSourceHost(sourceHost);
        m.setSourcePort(sourcePort);
        m.setSourceUser(sourceUser);
        m.setSourcePassword(sourcePassword);
        m.setTargetHost(targetHost);
        m.setTargetPort(targetPort);
        m.setTargetUser(targetUser);
        m.setTargetPassword(targetPassword);
        m.setIgnoreDatabases(ignoreDatabases == null ? new ArrayList<>() : new ArrayList<>(ignoreDatabases));
        m.setIgnoreTables(ignoreTables == null ? new ArrayList<>() : new ArrayList<>(ignoreTables));
        m.setIgnoreDdlTables(ignoreDdlTables == null ? new ArrayList<>() : new ArrayList<>(ignoreDdlTables));
        m.setIgnoreTablesByDb(ignoreTablesByDb == null ? new ArrayList<>() : new ArrayList<>(ignoreTablesByDb));
        m.setIgnoreDdlTablesByDb(ignoreDdlTablesByDb == null ? new ArrayList<>() : new ArrayList<>(ignoreDdlTablesByDb));
        m.setCommonIgnoreTables(commonIgnoreTables == null ? new ArrayList<>() : new ArrayList<>(commonIgnoreTables));
        m.setCommonDdlIgnoreTables(commonDdlIgnoreTables == null ? new ArrayList<>() : new ArrayList<>(commonDdlIgnoreTables));
        List<TransformRule> rules = new ArrayList<>();
        if (transformRules != null) {
            for (TransformRuleDTO dto : transformRules) {
                rules.add(dto.toRule());
            }
        }
        m.setTransformRules(rules);
        return List.of(m);
    }
}
