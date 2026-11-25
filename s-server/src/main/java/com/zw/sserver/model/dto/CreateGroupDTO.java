package com.zw.sserver.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建群聊请求 DTO
 */
@Data
public class CreateGroupDTO {

    @NotBlank(message = "群名称不能为空")
    @Size(max = 50, message = "群名称最多 50 个字符")
    private String groupName;

    @Size(max = 999, message = "初始群成员不能超过 999 人")
    private List<Long> memberIds;
}
