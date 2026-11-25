package com.zw.sserver.domain.session.model;

/**
 * 会话类型。
 */
public enum SessionType {

    PRIVATE("private"),
    GROUP("group");

    private final String code;

    SessionType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
