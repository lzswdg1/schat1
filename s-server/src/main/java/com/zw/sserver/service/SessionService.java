package com.zw.sserver.service;

import com.zw.sserver.model.vo.SessionVO;
import java.util.List;

public interface SessionService {
    List<SessionVO> getSessionList(Long userId);
}
