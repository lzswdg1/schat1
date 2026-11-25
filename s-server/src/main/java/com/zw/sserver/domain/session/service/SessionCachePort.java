package com.zw.sserver.domain.session.service;

import com.zw.sserver.model.vo.SessionVO;

import java.util.Collection;
import java.util.List;

/**
 * 会话列表缓存端口。
 */
public interface SessionCachePort {

    List<SessionVO> get(Long userId);

    void put(Long userId, List<SessionVO> sessions);

    void evict(Long userId);

    void evictAll(Collection<Long> userIds);
}
