package com.zw.sserver;

import com.zw.sserver.config.SchatProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient // 开启 Nacos 服务发现
@EnableScheduling
@EnableConfigurationProperties(SchatProperties.class)
@MapperScan("com.zw.sserver.mapper")
public class SServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SServerApplication.class, args);
    }

}
