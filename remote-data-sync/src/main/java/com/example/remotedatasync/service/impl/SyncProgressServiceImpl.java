package com.example.remotedatasync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.remotedatasync.common.DRPlatformErrorCodeEnum;
import com.example.remotedatasync.common.PageRespVO;
import com.example.remotedatasync.dto.SyncProgressPageQuery;
import com.example.remotedatasync.dto.SyncProgressQuery;
import com.example.remotedatasync.dto.SyncProgressVO;
import com.example.remotedatasync.enums.AllSyncStateEnum;
import com.example.remotedatasync.enums.SyncStateEnum;
import com.example.remotedatasync.manager.DatabaseSyncManager;
import com.example.remotedatasync.mapper.SyncProgressMapper;
import com.example.remotedatasync.po.SyncProgress;
import com.example.remotedatasync.service.SyncProgressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步进度服务实现。
 *
 * @author 50707
 */
@Slf4j
@Service
public class SyncProgressServiceImpl extends ServiceImpl<SyncProgressMapper, SyncProgress> implements SyncProgressService {

    private final SyncProgressMapper syncProgressMapper;
    private final DatabaseSyncManager databaseSyncManager;

    public SyncProgressServiceImpl(SyncProgressMapper syncProgressMapper, DatabaseSyncManager databaseSyncManager) {
        this.syncProgressMapper = syncProgressMapper;
        this.databaseSyncManager = databaseSyncManager;
    }

    @Override
    public PageRespVO<SyncProgressVO> queryPage(SyncProgressPageQuery query) {
        query.normalize(1, 20, 100);
        SyncProgressQuery condition = query.getCondition();
        Page<SyncProgress> page = new Page<>(query.getPage(), query.getPageSize());

        LambdaQueryWrapper<SyncProgress> wrapper = new LambdaQueryWrapper<>();
        if (condition.getIp() != null && !condition.getIp().isBlank()) {
            wrapper.eq(SyncProgress::getSourceIp, condition.getIp());
        }
        if (condition.getSourceDbName() != null && !condition.getSourceDbName().isBlank()) {
            wrapper.like(SyncProgress::getSourceDbName, condition.getSourceDbName());
        }
        if (condition.getState() != null) {
            wrapper.eq(SyncProgress::getState, condition.getState());
        }
        if (condition.getDeviationStatus() != null) {
            wrapper.eq(SyncProgress::getDeviationStatus, condition.getDeviationStatus());
        }
        wrapper.orderByDesc(SyncProgress::getUpdateTime);

        IPage<SyncProgress> resultPage = syncProgressMapper.selectPage(page, wrapper);
        long totalPages = resultPage.getPages();
        int nextPage = query.getPage() < totalPages ? query.getPage() + 1 : -1;

        List<SyncProgressVO> voList = SyncProgressVO.fromPoList(resultPage.getRecords());
        return new PageRespVO<>((int) resultPage.getTotal(), nextPage, voList);
    }

    @Override
    public Map<String, List<SyncProgressQuery>> resyncDatabases(List<SyncProgressQuery> databaseList) {
        if (databaseList == null || databaseList.isEmpty()) {
            return new HashMap<>();
        }
        return databaseSyncManager.resyncDatabases(databaseList);
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        LambdaQueryWrapper<SyncProgress> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(SyncProgress::getState, SyncStateEnum.INVALID.getCode(), SyncStateEnum.SUSPENDED.getCode())
                .orderByAsc(SyncProgress::getUpdateTime)
                .last("LIMIT 1");
        List<SyncProgress> list = list(wrapper);
        if (list != null && !list.isEmpty()) {
            result.put("status", AllSyncStateEnum.ABNORMAL.getCode());
            result.put("desc", AllSyncStateEnum.ABNORMAL.getDesc());
            result.put("firstExceptionTime", list.get(0).getUpdateTime());
        } else {
            result.put("status", AllSyncStateEnum.NORMAL.getCode());
            result.put("desc", AllSyncStateEnum.NORMAL.getDesc());
            result.put("firstExceptionTime", "");
        }
        return result;
    }

    @Override
    public List<String> getDatabasesByIp(String ips) {
        return databaseSyncManager.getDatabasesByIp(ips);
    }

    @Override
    public SyncProgressVO getById(Long id) {
        if (id == null) {
            return null;
        }
        return SyncProgressVO.fromPo(syncProgressMapper.selectById(id));
    }

    @Override
    public void truncateTable() {
        syncProgressMapper.truncateTable();
    }

    @Override
    public void persistFromMemory() {
        for (SyncProgress mem : DatabaseSyncManager.syncProgressMap.values()) {
            try {
                LambdaQueryWrapper<SyncProgress> q = new LambdaQueryWrapper<>();
                q.eq(SyncProgress::getSourceIp, mem.getSourceIp())
                        .eq(SyncProgress::getSourceDbName, mem.getSourceDbName());
                SyncProgress existing = getOne(q);
                if (existing == null) {
                    save(mem);
                } else {
                    mem.setId(existing.getId());
                    updateById(mem);
                }
            } catch (Exception e) {
                log.warn("Persist progress failed for {}|{}", mem.getSourceIp(), mem.getSourceDbName(), e);
            }
        }
    }
}
