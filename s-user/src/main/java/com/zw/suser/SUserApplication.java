package com.zw.suser;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient // 核心注解：开启服务注册与发现（向 Nacos 报到）
@MapperScan("com.zw.suser.mapper")
@SpringBootApplication(scanBasePackages = {"com.zw"})
@EnableFeignClients
public class SUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(SUserApplication.class, args);
    }

}
