package com.example.flinkcdcsync.enums;

import lombok.Getter;

/**
 * 整体同步状态：NORMAL / ABNORMAL
 *
 * @author 50707
 */
@Getter
public enum AllSyncStateEnum {
    NORMAL("NORMAL", "正常"),
    ABNORMAL("ABNORMAL", "异常");

    private final String code;
    private final String desc;

    AllSyncStateEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
