package com.example.flinkcdcsync.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.flinkcdcsync.common.PageRespVO;
import com.example.flinkcdcsync.dto.SyncProgressPageQuery;
import com.example.flinkcdcsync.dto.SyncProgressQuery;
import com.example.flinkcdcsync.dto.SyncProgressVO;
import com.example.flinkcdcsync.po.SyncProgress;

import java.util.List;
import java.util.Map;

/**
 * 同步进度服务接口。
 *
 * @author 50707
 */
public interface SyncProgressService extends IService<SyncProgress> {

    PageRespVO<SyncProgressVO> queryPage(SyncProgressPageQuery query);

    Map<String, List<SyncProgressQuery>> resyncDatabases(List<SyncProgressQuery> databaseList);

    Map<String, Object> status();

    List<String> getDatabasesByIp(String ips);

    SyncProgressVO getById(Long id);

    void truncateTable();

    /** 将内存进度缓存持久化到数据库（供定时任务调用） */
    void persistFromMemory();
}
