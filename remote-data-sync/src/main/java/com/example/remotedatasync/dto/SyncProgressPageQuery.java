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

    private int page = 1;
    private int pageSize = 20;
    private List<OrderItem> order;
    private SyncProgressQuery condition;

    /** 规范化分页参数 */
    public void normalize(int defaultPage, int defaultPageSize, int maxPageSize) {
        if (page <= 0) {
            page = defaultPage;
        }
        if (pageSize <= 0) {
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
