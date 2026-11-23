package com.labroute.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI labRouteOpenApi() {
        SecurityScheme operator = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-Operator-Token")
                .description("Control-plane token. Demo value: labroute-operator-demo");
        SecurityScheme sourceCode = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Source-Code")
                .description("Sample source system code, must be bound to the tenant's credential");
        SecurityScheme keyId = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Key-Id");
        SecurityScheme nonce = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Nonce")
                .description("Unique per request; exact replays must reuse Idempotency-Key");
        SecurityScheme signedAt = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Signed-At")
                .description("ISO-8601 timestamp inside the tenant policy window");
        SecurityScheme signature = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Signature")
                .description("hex HMAC-SHA256(secret, POST\\npath\\ntenant\\nsource\\nkeyId\\nnonce\\nsignedAt\\nsha256(canonicalBody))");
        SecurityScheme idempotency = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("Idempotency-Key");
        return new OpenAPI()
                .info(new Info()
                        .title("LabRoute 多租户检验数据搬迁与路由控制平台")
                        .version("1.0.0")
                        .description("""
                                持续接收样本、仪器结果、报告修订；连续发布只返回已验证旧空间版本、
                                已验证新空间版本或显式 PENDING_VERIFICATION。两条迁移路线：
                                FENCE_REPLAY（接入缓冲 + 一次性路由闸门）与 WATERMARK_ONLINE（双写 + 水位增量复制 + 分阶段读写路由）。
                                控制面端点需 X-Operator-Token；接入端点需来源 HMAC 签名五件套 + Idempotency-Key。
                                """)
                        .contact(new Contact().name("LabRoute")))
                .addServersItem(new Server().url("/").description("current host"))
                .components(new Components()
                        .addSecuritySchemes("operatorToken", operator)
                        .addSecuritySchemes("sourceCode", sourceCode)
                        .addSecuritySchemes("keyId", keyId)
                        .addSecuritySchemes("nonce", nonce)
                        .addSecuritySchemes("signedAt", signedAt)
                        .addSecuritySchemes("signature", signature)
                        .addSecuritySchemes("idempotencyKey", idempotency))
                .addSecurityItem(new SecurityRequirement().addList("operatorToken"));
    }
}
