package com.example.remotedatasync.enums;

import lombok.Getter;

/**
 * 重启模式：全量重置 / Savepoint 恢复
 *
 * @author 50707
 */
@Getter
public enum RestartMode {
    FULL_RESET("FULL_RESET", "全量重置（清空目标库，从头同步）"),
    SAVEPOINT("SAVEPOINT", "从 Savepoint 恢复");

    private final String code;
    private final String desc;

    RestartMode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
