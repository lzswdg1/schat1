package com.zw.sgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * SecureChat 统一网关启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
public class SGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SGatewayApplication.class, args);
    }
}