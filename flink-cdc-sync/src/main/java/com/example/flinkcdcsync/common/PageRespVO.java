package com.example.flinkcdcsync.common;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页响应结构。
 *
 * @param <T> 行数据类型
 * @author 50707
 */
@Data
public class PageRespVO<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private long total;

    /** 下一页页码；-1 表示没有下一页 */
    private int nextPage;

    /** 当前页数据 */
    private List<T> results;

    public PageRespVO() {
        this.results = new ArrayList<>();
    }

    public PageRespVO(long total, int nextPage, List<T> results) {
        this.total = total;
        this.nextPage = nextPage;
        this.results = results == null ? new ArrayList<>() : results;
    }
}
