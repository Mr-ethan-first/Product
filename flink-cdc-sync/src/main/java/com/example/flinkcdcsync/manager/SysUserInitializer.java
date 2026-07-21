package com.example.flinkcdcsync.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flinkcdcsync.common.PasswordUtil;
import com.example.flinkcdcsync.mapper.SysUserMapper;
import com.example.flinkcdcsync.po.SysUser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 启动时种子管理员：若用户表为空，则创建默认账号 admin / admin123。
 * 首次启动时若 sys_user 表尚不存在，会自动建表（CREATE TABLE IF NOT EXISTS），
 * 避免在未执行 init.sql 的环境（如测试库）下因缺表导致上下文启动失败。
 *
 * @author 50707
 */
@Slf4j
@Component
public class SysUserInitializer {

    private static final String DDL_SYS_USER =
            "CREATE TABLE IF NOT EXISTS `sys_user` ("
            + "`id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
            + "`username` VARCHAR(64) NOT NULL COMMENT '登录用户名',"
            + "`password_hash` VARCHAR(255) NOT NULL COMMENT '密码 salt:hash',"
            + "`salt` VARCHAR(64) DEFAULT NULL COMMENT '随机盐',"
            + "`create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
            + "`update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
            + "PRIMARY KEY (`id`),"
            + "UNIQUE KEY `uk_sys_user_username` (`username`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台管理用户'";

    private final SysUserMapper sysUserMapper;
    private final DataSource dataSource;

    public SysUserInitializer(SysUserMapper sysUserMapper, DataSource dataSource) {
        this.sysUserMapper = sysUserMapper;
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        ensureTable();
        Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<>());
        if (count != null && count > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setSalt(PasswordUtil.randomSalt());
        admin.setPasswordHash(PasswordUtil.encode("admin123"));
        sysUserMapper.insert(admin);
        log.warn("============================================================");
        log.warn(" 已创建默认管理员账号：admin / admin123  （请尽快修改密码）");
        log.warn("============================================================");
    }

    /** 若 sys_user 表不存在则自动建表，保证鉴权模块在任何环境下可用。 */
    private void ensureTable() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(DDL_SYS_USER);
        } catch (Exception e) {
            log.error("确保 sys_user 表存在失败", e);
        }
    }
}
