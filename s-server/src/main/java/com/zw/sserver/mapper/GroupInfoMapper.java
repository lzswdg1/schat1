package com.zw.sserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zw.sserver.model.entity.GroupInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GroupInfoMapper extends BaseMapper<GroupInfo> {

    @Select("SELECT * FROM tb_group_info WHERE group_id = #{groupId} FOR UPDATE")
    GroupInfo selectForUpdate(@Param("groupId") Long groupId);
}
