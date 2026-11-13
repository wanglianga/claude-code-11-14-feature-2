package com.example.flightops.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flightOpsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("航班正常性统计服务 API")
                        .version("1.0")
                        .description("实例主键(航班号+执行日期+航段) + 状态机校验 + 正负号变更日志冲抵 "
                                + "+ 放行判定绑定计划版本 + 日报版本/更正/差异定位；"
                                + "多口径并行计算与发布仲裁（口径注册库版本化、并行物化、逐口径补偿、"
                                + "发布差额归因拦截、切换评审、下游绑定台账）。统计口径见 GET /api/caliber。"))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
                .components(new Components()
                        .addSecuritySchemes("basicAuth",
                                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
    }
}
