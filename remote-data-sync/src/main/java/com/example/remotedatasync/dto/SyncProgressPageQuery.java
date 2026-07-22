package com.example.remotedatasync.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 同步进度分页查询请求。
 *
 * @author 50707
 */
@Data
public class SyncProgressPageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer page = 1;
    private Integer pageSize = 20;
    private List<OrderItem> order;
    private SyncProgressQuery condition;

    /** 规范化分页参数 */
    public void normalize(int defaultPage, int defaultPageSize, int maxPageSize) {
        // 使用 Integer：JSON 浮点/非数字会被 Jackson 拒绝（400），null 在此归一化
        if (page == null || page <= 0) {
            page = defaultPage;
        }
        if (pageSize == null || pageSize <= 0) {
            pageSize = defaultPageSize;
        }
        if (pageSize > maxPageSize) {
            pageSize = maxPageSize;
        }
        if (condition == null) {
            condition = new SyncProgressQuery();
        }
    }
}
