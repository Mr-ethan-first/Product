package com.example.remotedatasync.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台管理用户（注册 / 登录）。
 *
 * @author 50707
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名（唯一） */
    private String username;

    /** 密码存储：salt:sha256Hex（明文永不落库） */
    private String passwordHash;

    private String salt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
