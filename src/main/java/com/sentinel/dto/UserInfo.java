package com.sentinel.dto;

import lombok.Data;

/**
 * 当前登录用户信息。只暴露公开字段，不包含密码。
 *
 * @author Sentinel Team
 */
@Data
public class UserInfo {

    private Long id;

    private String username;

    private String email;

    /**
     * ADMIN 或 USER
     */
    private String role;
}
