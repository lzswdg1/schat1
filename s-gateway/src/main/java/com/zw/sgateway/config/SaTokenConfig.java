package com.zw.sgateway.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaHttpMethod;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.zw.scommon.api.CommonResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 权限认证配置类 (针对 WebFlux/Gateway 响应式编程环境)
 */
@Configuration
public class SaTokenConfig {

    /**
     * 注册 Sa-Token 全局过滤器
     */
    @Bean
    public SaReactorFilter getSaReactorFilter() {
        return new SaReactorFilter()
                // 1. 拦截所有请求路径
                .addInclude("/**")

                // 2. 绝对放行的白名单（比如登录、注册、获取别人公钥等）
                .addExclude(
                        "/favicon.ico",
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/ws/**",  // WebSocket 握手放行，ChatEndpoint 自行通过 token query param 鉴权
                        // Knife4j API 文档相关路径放行
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/v3/api-docs/**",
                        "/*/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                )

                // 3. 核心鉴权路由配置
                .setAuth(obj -> {
                    SaRouter.match(SaHttpMethod.OPTIONS).stop();
                    // 所有请求必须验证 Token
                    SaRouter.match("/**", r -> StpUtil.checkLogin());

                    // 以后如果要做后台管理系统，可以这样玩：
                    // SaRouter.match("/api/admin/**", r -> StpUtil.checkRole("admin"));
                })

                // 4. 异常处理：当没有 Token 或 Token 失效时
                .setError(e -> {
                    SaHolder.getResponse()
                            .setStatus(401)
                            .setHeader("Content-Type", "application/json;charset=UTF-8");
                    return cn.hutool.json.JSONUtil.toJsonStr(CommonResult.unauthorized(e.getMessage()));
                });
    }

    @Bean
    public StpLogic getStpLogicJwt() {
        return new StpLogicJwtForStateless();
    }
}
