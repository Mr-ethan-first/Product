package com.example.flinkcdcsync.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.flinkcdcsync.common.Result;
import com.example.flinkcdcsync.mapper.OperationLogMapper;
import com.example.flinkcdcsync.po.OperationLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作审计日志查询接口。
 * <p>
 * 提供分页查询操作日志的能力，支持按用户名、操作类型、结果状态过滤。
 * 所有登录用户均可查询操作日志（审计透明）。
 * </p>
 *
 * @author 50707
 */
@RestController
@RequestMapping("/operation-log")
public class OperationLogController {

    private final OperationLogMapper operationLogMapper;

    public OperationLogController(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 分页查询操作日志。
     * <p>
     * 请求体：{ page, pageSize, username?, operationType?, resultStatus?, clientIp?, startTime?, endTime? }
     * </p>
     */
    @PostMapping("/list")
    public Result<Map<String, Object>> list(@RequestBody Map<String, Object> body) {
        int page = body.get("page") != null ? Integer.parseInt(body.get("page").toString()) : 1;
        int pageSize = body.get("pageSize") != null ? Integer.parseInt(body.get("pageSize").toString()) : 20;
        String username = body.get("username") != null ? body.get("username").toString() : null;
        String operationType = body.get("operationType") != null ? body.get("operationType").toString() : null;
        String resultStatus = body.get("resultStatus") != null ? body.get("resultStatus").toString() : null;
        String clientIp = body.get("clientIp") != null ? body.get("clientIp").toString() : null;
        String startTime = body.get("startTime") != null ? body.get("startTime").toString() : null;
        String endTime = body.get("endTime") != null ? body.get("endTime").toString() : null;

        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.isEmpty()) {
            wrapper.eq(OperationLog::getUsername, username);
        }
        if (operationType != null && !operationType.isEmpty()) {
            wrapper.eq(OperationLog::getOperationType, operationType);
        }
        if (resultStatus != null && !resultStatus.isEmpty()) {
            wrapper.eq(OperationLog::getResultStatus, resultStatus);
        }
        if (clientIp != null && !clientIp.isEmpty()) {
            wrapper.like(OperationLog::getClientIp, clientIp);
        }
        if (startTime != null && !startTime.isEmpty()) {
            wrapper.ge(OperationLog::getCreateTime, java.time.LocalDateTime.parse(startTime, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        if (endTime != null && !endTime.isEmpty()) {
            wrapper.le(OperationLog::getCreateTime, java.time.LocalDateTime.parse(endTime, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        wrapper.orderByDesc(OperationLog::getCreateTime);

        IPage<OperationLog> pageResult = operationLogMapper.selectPage(
                new Page<>(page, pageSize), wrapper);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("results", pageResult.getRecords());
        data.put("total", pageResult.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return Result.success(data);
    }

    /** 获取所有操作类型（用于前端筛选下拉框） */
    @GetMapping("/types")
    public Result<Map<String, Object>> types() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("types", java.util.Arrays.asList(
                "LOGIN", "LOGOUT", "REGISTER",
                "MAPPING_ADD", "MAPPING_REMOVE", "MAPPING_RELOAD", "MAPPING_UPDATE",
                "RESYNC", "TEST_CONNECTION", "LOAD_DATABASES", "LOAD_TABLES",
                "SYNC_STATUS", "SYNC_LIST"
        ));
        return Result.success(data);
    }
}
