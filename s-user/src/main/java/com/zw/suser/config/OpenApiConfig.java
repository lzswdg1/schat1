package com.zw.suser.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI userOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SChat 用户服务 API")
                        .description("用户注册/登录、好友申请/关系管理")
                        .version("v2.0")
                        .contact(new Contact().name("zw")));
    }
}
