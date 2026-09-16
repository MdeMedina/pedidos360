package cl.duoc.pedidos360.common.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Requisito tecnico obligatorio del Caso 0: "OpenAPI por servicio (Swagger)".
 * Ademas declara el esquema bearer para poder probar endpoints protegidos
 * pegando el access token directamente en Swagger UI.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI pedidos360OpenApi(@Value("${spring.application.name:pedidos360}") String appName) {
        final String scheme = "bearer-jwt";
        return new OpenAPI()
                .info(new Info()
                        .title("Pedidos360 :: " + appName)
                        .version("1.0.0")
                        .description("Microservicio del Caso 0 Pedidos360. Protegido con JWT de Microsoft Entra ID."))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token emitido por Entra ID (o por /dev/token en perfil dev)")));
    }
}
