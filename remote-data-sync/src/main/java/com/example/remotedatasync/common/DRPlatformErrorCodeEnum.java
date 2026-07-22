package com.example.remotedatasync.common;

import lombok.Getter;

/**
 * 错误码规范：10 位数字码，格式 [组件编码][模块编码][具体编码]。
 * <p>
 * 组件编码 280700 固定，模块/具体编码自定义。
 * </p>
 *
 * @author 50707
 */
@Getter
public enum DRPlatformErrorCodeEnum {

    /** 成功 */
    SUCCESS("0", "success", 200, false),

    /** 参数错误 */
    PARAM_ERROR("2807002001", "Parameter error", 400, false),
    /** 数据不存在 */
    DATA_NOT_FOUND("2807002003", "Data not found", 404, false),
    /** 接口路径不存在（访问未映射的 URL） */
    PATH_NOT_FOUND("2807002007", "Interface path not found", 404, false),

    /** 分页查询同步进度失败 */
    GET_SYNC_PROGRESS_LIST_FAILED("2807002002", "Failed to query sync progress list", 500, false),
    /** 获取同步总状态失败 */
    GET_SYNC_PROGRESS_ALL_STATE_FAILED("2807002004", "Failed to get overall sync status", 500, false),
    /** 重新同步失败 */
    RESYNC_FAILED("2807002005", "Failed to resync databases", 500, false),

    /** 灾备中心数据批量提交异常 */
    BATCH_COMMIT_FAILED("2807005001", "Disaster recovery center batch commit exception", 500, true),
    /** 数据同步到目标库异常 */
    DATA_SYNC_TO_TARGET_FAILED("2807005007", "Failed to sync data to target database", 500, true),

    /** 同步引擎异常 */
    SYNC_ENGINE_ERROR("2807005008", "Sync engine internal error", 500, true),
    /** 数据库映射未配置 */
    MAPPING_NOT_FOUND("2807002006", "Database mapping not found", 400, false),

    /** 新增同步映射失败 */
    MAPPING_ADD_FAILED("2807002011", "Failed to add database mapping", 500, false),
    /** 数据库连接测试未通过 */
    DB_CONNECTION_TEST_FAILED("2807002012", "Database connection test failed", 400, false),
    /** 同步映射已存在（重复添加） */
    MAPPING_ALREADY_EXISTS("2807002013", "Database mapping already exists", 400, false),
    /** 待移除的同步映射不存在 */
    MAPPING_REMOVE_NOT_FOUND("2807002014", "Database mapping to remove not found", 404, false),

    /** 登录失败（用户名或密码错误）—— 返回 200，用 success=false 表达业务失败，避免 401 控制台噪声 */
    AUTH_FAIL("2807003001", "用户名或密码错误", 200, false),
    /** 用户已存在 */
    AUTH_DUPLICATE("2807003002", "用户已存在", 409, false),
    /** 未登录 / 登录过期 */
    AUTH_REQUIRED("2807003003", "未登录或登录已过期", 401, false),
    /** 登录参数错误 */
    AUTH_PARAM("2807003004", "用户名或密码格式不正确", 400, false);

    private final String code;
    private final String message;
    private final int httpStatus;
    private final boolean retryable;

    DRPlatformErrorCodeEnum(String code, String message, int httpStatus, boolean retryable) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public static String getMessageByCode(String code) {
        if (code == null) {
            return null;
        }
        for (DRPlatformErrorCodeEnum e : values()) {
            if (e.code.equals(code)) {
                return e.message;
            }
        }
        return code;
    }

    public static DRPlatformErrorCodeEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (DRPlatformErrorCodeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }

    /** 抛出业务异常 */
    public static void throwException(DRPlatformErrorCodeEnum e) {
        throw new BusinessException(e.getCode(), e.getMessage());
    }

    /** 抛出业务异常（带自定义消息） */
    public static void throwException(DRPlatformErrorCodeEnum e, String customMessage) {
        throw new BusinessException(e.getCode(), customMessage);
    }
}
