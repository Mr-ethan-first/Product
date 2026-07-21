package com.example.remotedatasync.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 排序项。
 *
 * @author 50707
 */
@Data
public class OrderItem implements Serializable {
    private static final long serialVersionUID = 1L;
    /** 排序字段（PO 属性名） */
    private String orderBy;
    /** asc / desc */
    private String order;
}
