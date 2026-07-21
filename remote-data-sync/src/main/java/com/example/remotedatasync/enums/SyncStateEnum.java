package com.example.remotedatasync.enums;

import lombok.Getter;

/**
 * 同步状态：0-失效, 1-全量, 2-同步中, 3-中止
 *
 * @author 50707
 */
@Getter
public enum SyncStateEnum {
    INVALID(0, "失效"),
    FULL_SYNC(1, "全量同步"),
    SYNCING(2, "同步中"),
    SUSPENDED(3, "同步中止");

    private final int code;
    private final String desc;

    SyncStateEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static SyncStateEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        for (SyncStateEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
