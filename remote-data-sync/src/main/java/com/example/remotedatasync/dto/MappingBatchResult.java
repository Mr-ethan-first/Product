package com.example.remotedatasync.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量新增同步映射的结果：成功创建 / 已跳过（重复）/ 失败 三类归类。
 *
 * @author 50707
 */
@Data
public class MappingBatchResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成功创建并开始同步的 instanceKey 列表 */
    private List<String> created = new ArrayList<>();

    /** 已存在被跳过的 instanceKey 列表 */
    private List<String> skipped = new ArrayList<>();

    /** 创建失败的说明（instanceKey:原因） */
    private List<String> failed = new ArrayList<>();
}
