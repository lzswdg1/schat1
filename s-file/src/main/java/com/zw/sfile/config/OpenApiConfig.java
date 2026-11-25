package com.zw.sfile.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fileOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SChat 文件服务 API")
                        .description("图片上传、静态资源访问")
                        .version("v2.0")
                        .contact(new Contact().name("zw")));
    }
}
