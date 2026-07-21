package com.example.remotedatasync.enums;

import lombok.Getter;

/**
 * 主备切换标志：生产切灾备 / 灾备切生产 / 无
 *
 * @author 50707
 */
@Getter
public enum SwitchFlagEnum {
    NONE("NONE", "无切换"),
    TO_BACKUP("TO_BACKUP", "生产中心切换至灾备中心"),
    TO_PRODUCTION("TO_PRODUCTION", "灾备中心切换至生产中心");

    private final String code;
    private final String desc;

    SwitchFlagEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
