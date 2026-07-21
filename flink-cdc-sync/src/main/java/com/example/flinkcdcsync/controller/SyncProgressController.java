package com.example.flinkcdcsync.controller;

import com.example.flinkcdcsync.bean.DatabaseConfig;
import com.example.flinkcdcsync.bean.DatabaseMapping;
import com.example.flinkcdcsync.common.BusinessException;
import com.example.flinkcdcsync.common.ConnectionTestResult;
import com.example.flinkcdcsync.common.GeoDRSyncErrorCodeEnum;
import com.example.flinkcdcsync.common.InternationalizationEnum;
import com.example.flinkcdcsync.common.PageRespVO;
import com.example.flinkcdcsync.common.Result;
import com.example.flinkcdcsync.common.annotation.OperationLogAnnotation;
import com.example.flinkcdcsync.dto.DatabaseMappingVO;
import com.example.flinkcdcsync.dto.MappingBatchResult;
import com.example.flinkcdcsync.dto.SyncMappingRequestDTO;
import com.example.flinkcdcsync.dto.SourceTablesRequestDTO;
import com.example.flinkcdcsync.dto.SyncProgressPageQuery;
import com.example.flinkcdcsync.dto.SyncProgressQuery;
import com.example.flinkcdcsync.dto.SyncProgressVO;
import com.example.flinkcdcsync.manager.DatabaseSyncManager;
import com.example.flinkcdcsync.service.DatabaseMetadataService;
import com.example.flinkcdcsync.service.SyncProgressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步进度管理 REST 接口。
 *
 * @author 50707
 */
@Slf4j
@RestController
@RequestMapping("/sync")
@Validated
public class SyncProgressController {

    private final SyncProgressService syncProgressService;
    private final DatabaseSyncManager databaseSyncManager;
    private final DatabaseMetadataService metadataService;

    public SyncProgressController(SyncProgressService syncProgressService, DatabaseSyncManager databaseSyncManager,
                                  DatabaseMetadataService metadataService) {
        this.syncProgressService = syncProgressService;
        this.databaseSyncManager = databaseSyncManager;
        this.metadataService = metadataService;
    }

    /** 分页查询同步进度 */
    @PostMapping("/db/list")
    public PageRespVO<SyncProgressVO> queryPage(@RequestBody @Valid SyncProgressPageQuery query) {
        try {
            PageRespVO<SyncProgressVO> result = syncProgressService.queryPage(query);
            if (result == null || CollectionUtils.isEmpty(result.getResults())) {
                return result;
            }
            for (SyncProgressVO vo : result.getResults()) {
                vo.setSuspensionReason(InternationalizationEnum.getMessageByCode(vo.getSuspensionReason()));
                vo.setProcessingMethod(InternationalizationEnum.getMessageByCode(vo.getProcessingMethod()));
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to query sync progress list", e);
            throw new BusinessException(GeoDRSyncErrorCodeEnum.GET_SYNC_PROGRESS_LIST_FAILED.getCode(),
                    GeoDRSyncErrorCodeEnum.GET_SYNC_PROGRESS_LIST_FAILED.getMessage());
        }
    }

    /** 重新同步数据库（全量重置） */
    @OperationLogAnnotation(type = "RESYNC", desc = "全量重置同步")
    @PostMapping("/resyncDatabases")
    public Result<Map<String, List<SyncProgressQuery>>> resyncDatabases(@RequestBody @Valid List<SyncProgressQuery> databaseList) {
        Map<String, List<SyncProgressQuery>> result;
        try {
            result = syncProgressService.resyncDatabases(databaseList);
        } catch (Exception e) {
            log.error("Failed to resync databases", e);
            throw new BusinessException(GeoDRSyncErrorCodeEnum.RESYNC_FAILED.getCode(),
                    GeoDRSyncErrorCodeEnum.RESYNC_FAILED.getMessage());
        }
        return Result.success(result);
    }

    /** 获取 IP 列表（生产中心 / 灾备中心） */
    @GetMapping("/ipList")
    public List<Map<String, String>> ipList() {
        List<Map<String, String>> result = new ArrayList<>();
        for (DatabaseMapping dbMapping : databaseSyncManager.getMappings()) {
            Map<String, String> sourceMap = new HashMap<>();
            sourceMap.put("ip", dbMapping.getSourceIP());
            sourceMap.put("type", InternationalizationEnum.translate(InternationalizationEnum.ACTIVE));
            Map<String, String> targetMap = new HashMap<>();
            targetMap.put("ip", dbMapping.getTargetIP());
            targetMap.put("type", InternationalizationEnum.translate(InternationalizationEnum.STANDBY));
            result.add(sourceMap);
            result.add(targetMap);
        }
        return result;
    }

    /**
     * 同步连接测试：对源库（生产中心）与目标库（灾备中心）服务端分别做连通性测试。
     * 不依赖具体库名，仅验证账号/密码/网络是否可达。返回两端结果便于页面定位问题。
     */
    @OperationLogAnnotation(type = "TEST_CONNECTION", desc = "测试数据库连接")
    @PostMapping("/mapping/test")
    public ResponseEntity<Result<Map<String, ConnectionTestResult>>> testMapping(@RequestBody @Valid SyncMappingRequestDTO dto) {
        DatabaseConfig sourceCfg = new DatabaseConfig(dto.getSourceHost(), dto.getSourcePort(),
                dto.getSourceUser(), dto.getSourcePassword(), "");
        DatabaseConfig targetCfg = new DatabaseConfig(dto.getTargetHost(), dto.getTargetPort(),
                dto.getTargetUser(), dto.getTargetPassword(), "");
        ConnectionTestResult source = sourceCfg.testServerConnection();
        ConnectionTestResult target = targetCfg.testServerConnection();
        Map<String, ConnectionTestResult> data = new LinkedHashMap<>();
        data.put("source", source);
        data.put("target", target);
        if (!source.isOk() || !target.isOk()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error(GeoDRSyncErrorCodeEnum.DB_CONNECTION_TEST_FAILED.getCode(),
                            "连接测试未通过（源库:" + (source.isOk() ? "OK" : "失败") + "，目标库:" + (target.isOk() ? "OK" : "失败") + "）", data));
        }
        return ResponseEntity.ok(Result.success(data));
    }

    /**
     * 按源库连接信息列出可同步的数据库（服务端自动排除 MySQL 系统库）。
     * 供页面"忽略整库"多选下拉使用。
     */
    @PostMapping("/sourceDatabases")
    public Result<List<String>> sourceDatabases(@RequestBody SyncMappingRequestDTO dto) {
        // sourceDatabases 只需源库信息，手动校验源字段（不校验目标字段）
        if (dto == null || !StringUtils.hasText(dto.getSourceHost())) {
            throw new BusinessException(GeoDRSyncErrorCodeEnum.PARAM_ERROR.getCode(), "源主机不能为空");
        }
        if (!StringUtils.hasText(dto.getSourceUser())) {
            throw new BusinessException(GeoDRSyncErrorCodeEnum.PARAM_ERROR.getCode(), "源库账号不能为空");
        }
        if (!StringUtils.hasText(dto.getSourcePassword())) {
            throw new BusinessException(GeoDRSyncErrorCodeEnum.PARAM_ERROR.getCode(), "源库密码不能为空");
        }
        DatabaseConfig sourceCfg = new DatabaseConfig(dto.getSourceHost(), dto.getSourcePort(),
                dto.getSourceUser(), dto.getSourcePassword(), "");
        ConnectionTestResult sourceTest = sourceCfg.testServerConnection();
        if (!sourceTest.isOk()) {
            throw new BusinessException(GeoDRSyncErrorCodeEnum.DB_CONNECTION_TEST_FAILED.getCode(),
                    "源库连接测试未通过: " + sourceTest.getMessage());
        }
        List<String> dbs = databaseSyncManager.listSourceDatabases(sourceCfg);
        return Result.success(dbs);
    }

    /**
     * 按源库连接信息 + 库名，列出该库下所有用户表（服务端自动排除系统表 / 视图）。
     * 供页面"按库忽略表"多选下拉使用（下拉选择或手动正则均可基于该清单）。
     */
    @PostMapping("/sourceTables")
    public Result<List<String>> sourceTables(@RequestBody @Valid SourceTablesRequestDTO dto) {
        DatabaseConfig sourceCfg = new DatabaseConfig(dto.getSourceHost(), dto.getSourcePort(),
                dto.getSourceUser(), dto.getSourcePassword(), dto.getDatabase());
        List<String> tables = metadataService.listTables(sourceCfg, dto.getDatabase());
        return Result.success(tables);
    }

    /**
     * 页面批量新增同步映射：根据源/目标连接 + 选中的多个源库，扇出为多个映射并开始同步。
     * 支持忽略表与字段转换规则。已存在的映射自动跳过。
     */
    @OperationLogAnnotation(type = "MAPPING_ADD", desc = "新增同步映射")
    @PostMapping("/mapping/add")
    public Result<MappingBatchResult> addMapping(@RequestBody @Valid SyncMappingRequestDTO dto) {
        try {
            // 目标库连通性预检：不可达时直接拒绝，避免创建无效映射
            DatabaseConfig targetCfg = new DatabaseConfig(dto.getTargetHost(), dto.getTargetPort(),
                    dto.getTargetUser(), dto.getTargetPassword(), "");
            ConnectionTestResult targetTest = targetCfg.testServerConnection();
            if (!targetTest.isOk()) {
                throw new BusinessException(GeoDRSyncErrorCodeEnum.DB_CONNECTION_TEST_FAILED.getCode(),
                        "目标库连接测试未通过: " + targetTest.getMessage());
            }
            List<DatabaseMapping> mappings = dto.toMappings();
            if (mappings.isEmpty()) {
                throw new BusinessException(GeoDRSyncErrorCodeEnum.PARAM_ERROR.getCode(), "未解析到有效的同步主机对");
            }
            MappingBatchResult result = databaseSyncManager.addMappings(mappings);
            if (!result.getCreated().isEmpty()) {
                return Result.success(result);
            }
            // 全部跳过或全部失败
            if (!result.getFailed().isEmpty()) {
                throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getCode(),
                        "同步映射创建失败：" + String.join("; ", result.getFailed()));
            }
            // 全部已存在
            return Result.error(GeoDRSyncErrorCodeEnum.MAPPING_ALREADY_EXISTS.getCode(),
                    "所选数据库均已存在同步映射（已跳过）", result);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to add mappings", e);
            throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getCode(),
                    GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getMessage());
        }
    }

    /** 查询当前所有已配置的同步映射（含 YAML 配置与页面动态新增，密码已脱敏） */
    @GetMapping("/mappings")
    public Result<List<DatabaseMappingVO>> listMappings() {
        // 按用户过滤：用户只能看到全局映射（yml 配置）和自己创建的映射
        Long userId = com.example.flinkcdcsync.common.UserContext.getUserId();
        return Result.success(databaseSyncManager.listMappingsVO(userId));
    }

    /** 移除页面动态新增的同步映射（停止作业并释放连接池） */
    @OperationLogAnnotation(type = "MAPPING_REMOVE", desc = "移除同步映射")
    @PostMapping("/mapping/remove")
    public Result<Void> removeMapping(@RequestBody Map<String, String> body) {
        String instanceKey = body == null ? null : body.get("instanceKey");
        if (instanceKey == null || instanceKey.isEmpty()) {
            throw new BusinessException(GeoDRSyncErrorCodeEnum.PARAM_ERROR.getCode(), "instanceKey 不能为空");
        }
        try {
            databaseSyncManager.removeMapping(instanceKey);
            return Result.success();
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("MAPPING_NOT_FOUND")) {
                throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_REMOVE_NOT_FOUND.getCode(),
                        GeoDRSyncErrorCodeEnum.MAPPING_REMOVE_NOT_FOUND.getMessage());
            }
            throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Failed to remove mapping {}", instanceKey, e);
            throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getCode(),
                    GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getMessage());
        }
    }

    /**
     * 重新加载指定主机对的配置并重建同步作业（配置热生效）。
     * <p>
     * 场景：通过 {@code /sync/mapping/add} 或直接修改 application.yml 后，
     * 需要让新的 ignoreDatabases / ignoreTables / transformRules 等配置立即生效，
     * 无需重启服务。调用此接口会停止旧作业、清理旧连接池、用最新 mapping 重建作业。
     * </p>
     * <p>
     * 注意：yml 中静态配置的 mapping 修改后，需配合 {@code /sync/mapping/update} 接口
     * 更新内存中的 mapping 对象，再调用本接口生效。动态新增的 mapping 可直接调用本接口。
     * </p>
     */
    @OperationLogAnnotation(type = "MAPPING_RELOAD", desc = "重载映射配置")
    @PostMapping("/mapping/reload")
    public Result<Map<String, Object>> reloadMapping(@RequestBody Map<String, String> body) {
        String instanceKey = body == null ? null : body.get("instanceKey");
        if (instanceKey == null || instanceKey.isEmpty()) {
            throw new BusinessException(GeoDRSyncErrorCodeEnum.PARAM_ERROR.getCode(), "instanceKey 不能为空");
        }
        try {
            boolean ok = databaseSyncManager.reloadMapping(instanceKey);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("instanceKey", instanceKey);
            data.put("reloaded", ok);
            if (ok) {
                return Result.success(data);
            }
            throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getCode(),
                    "重新加载映射配置失败");
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("MAPPING_NOT_FOUND")) {
                throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_REMOVE_NOT_FOUND.getCode(),
                        GeoDRSyncErrorCodeEnum.MAPPING_REMOVE_NOT_FOUND.getMessage());
            }
            throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getCode(), e.getMessage());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to reload mapping {}", instanceKey, e);
            throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getCode(),
                    GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getMessage());
        }
    }

    /**
     * 更新指定主机对的配置（运行时修改 ignoreDatabases / ignoreTables 等字段）。
     * <p>
     * 修改后需调用 {@code /sync/mapping/reload} 让新配置对同步作业生效。
     * 仅支持动态新增的 mapping（yml 配置的 mapping 需改 yml 后重启）。
     * </p>
     */
    @OperationLogAnnotation(type = "MAPPING_UPDATE", desc = "更新映射配置")
    @PostMapping("/mapping/update")
    public Result<Map<String, Object>> updateMapping(@RequestBody Map<String, Object> body) {
        String instanceKey = body == null ? null : (String) body.get("instanceKey");
        if (instanceKey == null || instanceKey.isEmpty()) {
            throw new BusinessException(GeoDRSyncErrorCodeEnum.PARAM_ERROR.getCode(), "instanceKey 不能为空");
        }
        try {
            boolean ok = databaseSyncManager.updateMappingConfig(instanceKey, body);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("instanceKey", instanceKey);
            data.put("updated", ok);
            return Result.success(data);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("MAPPING_NOT_FOUND")) {
                throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_REMOVE_NOT_FOUND.getCode(),
                        GeoDRSyncErrorCodeEnum.MAPPING_REMOVE_NOT_FOUND.getMessage());
            }
            throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getCode(), e.getMessage());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update mapping {}", instanceKey, e);
            throw new BusinessException(GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getCode(),
                    GeoDRSyncErrorCodeEnum.MAPPING_ADD_FAILED.getMessage());
        }
    }

    /** 根据 IP 获取其下用户数据库列表 */
    @GetMapping("/databases/{ips}")
    public List<String> getDatabasesByIp(@PathVariable String ips) {
        return syncProgressService.getDatabasesByIp(ips);
    }

    /** 根据 ID 查询同步详情 */
    @GetMapping("/{id}")
    public Result<SyncProgressVO> getById(@PathVariable Long id) {
        SyncProgressVO result = syncProgressService.getById(id);
        if (result == null) {
            throw new BusinessException(GeoDRSyncErrorCodeEnum.DATA_NOT_FOUND.getCode(),
                    GeoDRSyncErrorCodeEnum.DATA_NOT_FOUND.getMessage());
        }
        result.setSuspensionReason(InternationalizationEnum.getMessageByCode(result.getSuspensionReason()));
        result.setProcessingMethod(InternationalizationEnum.getMessageByCode(result.getProcessingMethod()));
        return Result.success(result);
    }

    /** 获取同步总状态 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        try {
            return syncProgressService.status();
        } catch (Exception e) {
            log.error("Failed to retrieve overall sync status", e);
            GeoDRSyncErrorCodeEnum.throwException(GeoDRSyncErrorCodeEnum.GET_SYNC_PROGRESS_ALL_STATE_FAILED);
            return new HashMap<>();
        }
    }
}
