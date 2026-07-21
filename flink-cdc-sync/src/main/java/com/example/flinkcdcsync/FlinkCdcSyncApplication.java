package com.example.flinkcdcsync;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * GeoDRSync 地理数据库灾备同步服务 启动类。
 * <p>
 * 本地（Windows）仅启动后端管理服务 + 内嵌同步引擎即可运行，无需 Flink 集群；
 * 生产（Linux CentOS7）通过 --spring.profiles.active=linux 切换数据库地址。
 * </p>
 *
 * @author 50707
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.example.flinkcdcsync.mapper")
public class FlinkCdcSyncApplication {

    public static void main(String[] args) {
        // 本系统为中国异地容灾同步，统一使用 Asia/Shanghai 时区，
        // 避免 LocalDateTime.now() 等元数据时间戳落为本机其它时区（如 PDT）。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(FlinkCdcSyncApplication.class, args);
    }
}
