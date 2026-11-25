package com.zw.sserver.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI serverOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SChat 聊天服务 API")
                        .description("聊天消息、群聊管理、历史记录、已读回执")
                        .version("v2.0")
                        .contact(new Contact().name("zw")));
    }
}
