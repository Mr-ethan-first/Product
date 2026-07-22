package com.example.remotedatasync.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.remotedatasync.common.BusinessException;
import com.example.remotedatasync.common.DRPlatformErrorCodeEnum;
import com.example.remotedatasync.common.Result;
import com.example.remotedatasync.mapper.OperationLogMapper;
import com.example.remotedatasync.po.OperationLog;
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
        // 健壮性：所有入参均做安全解析，非法值返回 400（PARAM_ERROR）而非 500。
        final int MAX_PAGE_SIZE = 200;
        int page = parsePositiveInt(body.get("page"), "page", 1);
        int pageSize = parseBoundedInt(body.get("pageSize"), "pageSize", 20, 1, MAX_PAGE_SIZE);
        String username = asText(body.get("username"));
        String operationType = asText(body.get("operationType"));
        String resultStatus = asText(body.get("resultStatus"));
        String clientIp = asText(body.get("clientIp"));
        String startTime = asText(body.get("startTime"));
        String endTime = asText(body.get("endTime"));

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
            wrapper.ge(OperationLog::getCreateTime, parseDateTime(startTime, "startTime"));
        }
        if (endTime != null && !endTime.isEmpty()) {
            wrapper.le(OperationLog::getCreateTime, parseDateTime(endTime, "endTime"));
        }
        wrapper.orderByDesc(OperationLog::getCreateTime).orderByDesc(OperationLog::getId);

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

    // ===== 入参安全解析辅助方法（健壮性保障） =====

    /** 解析正整数参数；为 null 时返回默认值；0/负数降级为默认首页（兼容旧行为）；非数字/小数抛 PARAM_ERROR。 */
    private int parsePositiveInt(Object value, String name, int defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        int v = parseStrictInt(value, name);
        if (v < 1) {
            return defaultVal;
        }
        return v;
    }

    /** 解析有界整数参数；为 null 返回默认值，否则夹紧到 [min,max]，非法时抛 PARAM_ERROR。 */
    private int parseBoundedInt(Object value, String name, int defaultVal, int min, int max) {
        if (value == null) {
            return defaultVal;
        }
        int v = parseStrictInt(value, name);
        if (v < min) {
            v = min;
        } else if (v > max) {
            v = max;
        }
        return v;
    }

    /** 严格解析整型：要求能无损转成整数，浮点/布尔/对象/非数字串一律视为非法。 */
    private int parseStrictInt(Object value, String name) {
        if (value instanceof Number) {
            // 拒绝带小数部分的数字（如 1.5），避免静默截断掩盖客户端错误
            if (value instanceof Double || value instanceof Float) {
                double d = ((Number) value).doubleValue();
                if (d != Math.floor(d) || d != Math.ceil(d)) {
                    throw new BusinessException(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), name + " 必须是整数，不能是小数: " + d);
                }
            }
            return ((Number) value).intValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            throw new BusinessException(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), name + " 不能为空");
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new BusinessException(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), name + " 不是合法整数: " + s);
        }
    }

    /** 字符串化：null/空返回 null，否则返回 trim 后的字符串（避免 toString 产生 "{...}" 等污染）。 */
    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map || value instanceof java.util.Collection) {
            throw new BusinessException(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), "过滤字段不接受对象/数组");
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 解析时间参数（yyyy-MM-dd HH:mm:ss），格式错误抛 PARAM_ERROR。 */
    private java.time.LocalDateTime parseDateTime(String value, String name) {
        try {
            return java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            throw new BusinessException(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), name + " 时间格式应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
