package com.zw.suser.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "s-server", fallbackFactory = ServerFeignFallbackFactory.class)
public interface ServerFeignClient {

    @PostMapping("/internal/ws/send")
    String sendMessage(@RequestParam("userId") Long userId,
                       @RequestParam("message") String message);

    @DeleteMapping("/internal/ws/deleteMessages")
    String deleteMessages(@RequestParam("userId1") Long userId1,
                          @RequestParam("userId2") Long userId2);
}
