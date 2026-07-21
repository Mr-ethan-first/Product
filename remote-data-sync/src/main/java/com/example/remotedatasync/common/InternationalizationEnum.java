package com.example.remotedatasync.common;

import lombok.Getter;

/**
 * 国际化/枚举翻译。本实现以中文为默认语言，translate/getMessageByCode 返回对应中文文案。
 * <p>
 * 用于：
 * 1. IP 列表的 type 字段（生产中心 / 灾备中心）
 * 2. 同步进度中的 SUSPENSION_REASON（中止原因）与 PROCESSING_METHOD（建议处理方法）
 * </p>
 *
 * @author 50707
 */
@Getter
public enum InternationalizationEnum {

    ACTIVE("ACTIVE", "生产中心"),
    STANDBY("STANDBY", "灾备中心"),

    SUSPENSION_CONNECTION("SUSPENSION_CONNECTION", "目标数据库连接失败"),
    SUSPENSION_TABLE_MISMATCH("SUSPENSION_TABLE_MISMATCH", "源库与目标库表结构不匹配"),
    SUSPENSION_DDL("SUSPENSION_DDL", "DDL 执行失败且重试无果"),
    SUSPENSION_UNKNOWN("SUSPENSION_UNKNOWN", "发生未知错误，同步已中止"),

    PROCESSING_RESTART("PROCESSING_RESTART", "请通过接口手动重启该数据库同步任务"),
    PROCESSING_RECREATE("PROCESSING_RECREATE", "请重建该同步作业并重新全量同步"),
    PROCESSING_CHECK("PROCESSING_CHECK", "请检查网络与目标库状态后重试");

    private final String code;
    private final String message;

    InternationalizationEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public static String translate(InternationalizationEnum e) {
        return e == null ? null : e.message;
    }

    public static String getMessageByCode(String code) {
        if (code == null) {
            return null;
        }
        for (InternationalizationEnum e : values()) {
            if (e.code.equals(code)) {
                return e.message;
            }
        }
        return code;
    }
}
