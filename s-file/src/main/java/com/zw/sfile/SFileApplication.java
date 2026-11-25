package com.zw.sfile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {"com.zw"}, exclude = {DataSourceAutoConfiguration.class})
public class SFileApplication {

    public static void main(String[] args) {
        SpringApplication.run(SFileApplication.class, args);
        System.out.println(" [s-file] 文件微服务启动成功，随时准备接收图片！");
    }

}
