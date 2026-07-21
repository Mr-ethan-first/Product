package com.example.flinkcdcsync.dto;

import com.example.flinkcdcsync.enums.DelivationStatusEnum;
import com.example.flinkcdcsync.enums.SyncStateEnum;
import com.example.flinkcdcsync.po.SyncProgress;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 同步进度视图对象。
 *
 * @author 50707
 */
public class SyncProgressVO {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Long id;
    private String sourceIp;
    private String sourceDbName;
    private String targetIp;
    private String targetDbName;
    private Integer state;
    private String stateDesc;
    private String syncStartTime;
    private String sourceBinlogFile;
    private String sourceBinlogTime;
    private String syncBinlogFile;
    private String syncBinlogTime;
    private Long deviationTimes;
    private Integer deviationStatus;
    private String deviationStatusDesc;
    private String suspensionReason;
    private String processingMethod;
    private String createTime;
    private String updateTime;

    public static SyncProgressVO fromPo(SyncProgress po) {
        if (po == null) {
            return null;
        }
        SyncProgressVO vo = new SyncProgressVO();
        vo.setId(po.getId());
        vo.setSourceIp(po.getSourceIp());
        vo.setSourceDbName(po.getSourceDbName());
        vo.setTargetIp(po.getTargetIp());
        vo.setTargetDbName(po.getTargetDbName());
        vo.setState(po.getState());
        SyncStateEnum stateEnum = SyncStateEnum.of(po.getState());
        vo.setStateDesc(stateEnum == null ? null : stateEnum.getDesc());
        vo.setSyncStartTime(fmt(po.getSyncStartTime()));
        vo.setSourceBinlogFile(po.getSourceBinlogFile());
        vo.setSourceBinlogTime(fmt(po.getSourceBinlogTime()));
        vo.setSyncBinlogFile(po.getSyncBinlogFile());
        vo.setSyncBinlogTime(fmt(po.getSyncBinlogTime()));
        vo.setDeviationTimes(po.getDeviationTimes());
        vo.setDeviationStatus(po.getDeviationStatus());
        DelivationStatusEnum dev = DelivationStatusEnum.of(po.getDeviationStatus());
        vo.setDeviationStatusDesc(dev == null ? null : dev.getDesc());
        vo.setSuspensionReason(po.getSuspensionReason());
        vo.setProcessingMethod(po.getProcessingMethod());
        vo.setCreateTime(fmt(po.getCreateTime()));
        vo.setUpdateTime(fmt(po.getUpdateTime()));
        return vo;
    }

    public static List<SyncProgressVO> fromPoList(List<SyncProgress> list) {
        List<SyncProgressVO> result = new ArrayList<>();
        if (list != null) {
            for (SyncProgress po : list) {
                result.add(fromPo(po));
            }
        }
        return result;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(FMT);
    }

    // getters & setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }
    public String getSourceDbName() { return sourceDbName; }
    public void setSourceDbName(String sourceDbName) { this.sourceDbName = sourceDbName; }
    public String getTargetIp() { return targetIp; }
    public void setTargetIp(String targetIp) { this.targetIp = targetIp; }
    public String getTargetDbName() { return targetDbName; }
    public void setTargetDbName(String targetDbName) { this.targetDbName = targetDbName; }
    public Integer getState() { return state; }
    public void setState(Integer state) { this.state = state; }
    public String getStateDesc() { return stateDesc; }
    public void setStateDesc(String stateDesc) { this.stateDesc = stateDesc; }
    public String getSyncStartTime() { return syncStartTime; }
    public void setSyncStartTime(String syncStartTime) { this.syncStartTime = syncStartTime; }
    public String getSourceBinlogFile() { return sourceBinlogFile; }
    public void setSourceBinlogFile(String sourceBinlogFile) { this.sourceBinlogFile = sourceBinlogFile; }
    public String getSourceBinlogTime() { return sourceBinlogTime; }
    public void setSourceBinlogTime(String sourceBinlogTime) { this.sourceBinlogTime = sourceBinlogTime; }
    public String getSyncBinlogFile() { return syncBinlogFile; }
    public void setSyncBinlogFile(String syncBinlogFile) { this.syncBinlogFile = syncBinlogFile; }
    public String getSyncBinlogTime() { return syncBinlogTime; }
    public void setSyncBinlogTime(String syncBinlogTime) { this.syncBinlogTime = syncBinlogTime; }
    public Long getDeviationTimes() { return deviationTimes; }
    public void setDeviationTimes(Long deviationTimes) { this.deviationTimes = deviationTimes; }
    public Integer getDeviationStatus() { return deviationStatus; }
    public void setDeviationStatus(Integer deviationStatus) { this.deviationStatus = deviationStatus; }
    public String getDeviationStatusDesc() { return deviationStatusDesc; }
    public void setDeviationStatusDesc(String deviationStatusDesc) { this.deviationStatusDesc = deviationStatusDesc; }
    public String getSuspensionReason() { return suspensionReason; }
    public void setSuspensionReason(String suspensionReason) { this.suspensionReason = suspensionReason; }
    public String getProcessingMethod() { return processingMethod; }
    public void setProcessingMethod(String processingMethod) { this.processingMethod = processingMethod; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
}
