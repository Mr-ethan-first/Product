package com.example.remotedatasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.remotedatasync.po.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 后台管理用户 Mapper。
 *
 * @author 50707
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
