package com.example.remotedatasync.enums;

import lombok.Getter;

/**
 * 偏差状态：1-正常, 2-异常
 *
 * @author 50707
 */
@Getter
public enum DelivationStatusEnum {
    NORMAL(1, "正常"),
    ABNORMAL(2, "异常");

    private final int code;
    private final String desc;

    DelivationStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static DelivationStatusEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        for (DelivationStatusEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
