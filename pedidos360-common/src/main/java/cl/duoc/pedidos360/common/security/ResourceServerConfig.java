package cl.duoc.pedidos360.common.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;

/**
 * Convierte cada microservicio en un OAuth2 Resource Server.
 *
 * Guia 1.3.3: el backend NO inicia sesion ni pide credenciales. Solo recibe el
 * access token que el frontend obtuvo con MSAL y decide si lo acepta.
 *
 * Cadena de confianza completa del Caso 0:
 *   Angular + MSAL  -> obtiene el token de Entra ID
 *   AWS API Gateway -> JWT Authorizer valida firma/issuer/audiencia ANTES de enrutar
 *   Spring Security -> vuelve a validar y ademas aplica autorizacion por rol
 *
 * El gateway protege el perimetro; Spring protege el recurso. Si alguien alcanza
 * el microservicio por dentro de la VPC saltandose el gateway, esta clase sigue
 * exigiendo un token valido.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class ResourceServerConfig {

    private final SecurityProperties properties;

    public ResourceServerConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                // Es una API sin estado: no hay sesion ni formulario de login, por lo que
                // CSRF no aplica (no existe cookie de sesion que un tercero pueda reutilizar).
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(properties.getPublicPaths().toArray(String[]::new)).permitAll()
                        // Todo lo demas exige token valido. La autorizacion fina por rol
                        // se resuelve con @PreAuthorize en cada controlador.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(new JwtAuthoritiesConverter())));
        return http.build();
    }

    /**
     * Perfil por defecto (azure/prod): valida firma, issuer, expiracion y audiencia.
     *
     * Hay dos formas de obtener las claves publicas y la diferencia importa en el
     * arranque de un contenedor:
     *
     *  - Con jwk-set-uri (lo que se usa aqui por defecto): el decoder se construye
     *    sin tocar la red. Las claves se descargan la PRIMERA vez que llega un token
     *    y quedan cacheadas. Si Microsoft no responde en el instante del arranque,
     *    el servicio levanta igual.
     *
     *  - Con descubrimiento OIDC (JwtDecoders.fromIssuerLocation): Spring llama a
     *    .well-known/openid-configuration DURANTE el arranque. Es mas comodo porque
     *    deduce el JWKS solo, pero convierte al emisor en una dependencia dura del
     *    arranque: un fallo de DNS de un segundo tumba el contenedor. Con seis
     *    servicios levantando a la vez detras de un Docker Compose, eso pasa.
     *
     * En ambos casos la validacion resultante es identica.
     */
    @Bean
    @Profile("!dev")
    JwtDecoder entraIdJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {

        String jwkSetUri = properties.getJwkSetUri();
        NimbusJwtDecoder decoder = (jwkSetUri != null && !jwkSetUri.isBlank())
                ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : JwtDecoders.fromIssuerLocation(issuerUri);

        // Con jwk-set-uri, Spring no sabe que issuer esperar: hay que exigirlo aqui
        // explicitamente o se aceptarian tokens de cualquier tenant firmados con
        // una clave de ese JWKS.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator(properties.getAudiences())));
        return decoder;
    }

    /**
     * Perfil dev: valida tokens firmados con HMAC por el propio BFF
     * (endpoint /dev/token). Permite levantar y defender toda la solucion sin
     * depender de la disponibilidad del tenant de Azure ni de AWS Academy.
     * Nunca debe activarse en un entorno real.
     */
    @Bean
    @Profile("dev")
    JwtDecoder devJwtDecoder() {
        SecretKeySpec key = new SecretKeySpec(
                properties.getDevSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getDevIssuer()),
                new AudienceValidator(properties.getAudiences())));
        return decoder;
    }

    /**
     * CORS lo resuelve UN SOLO componente de la cadena.
     *
     * En local lo resuelve la aplicacion. Cuando la solucion queda publicada detras
     * de AWS API Gateway, el gateway ya responde el preflight y agrega las cabeceras,
     * asi que aqui hay que apagarlo con pedidos360.security.cors-enabled=false.
     * Si los dos las agregan, la respuesta viaja con dos Access-Control-Allow-Origin
     * y el navegador la descarta.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        if (!properties.isCorsEnabled()) {
            // Sin reglas registradas, Spring no agrega ninguna cabecera CORS.
            return new UrlBasedCorsConfigurationSource();
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(properties.getCorsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        /**
         * WWW-Authenticate lleva el motivo EXACTO por el que se rechazo un token
         * (issuer que no cuadra, audiencia incorrecta, firma invalida, expirado).
         * Por defecto el navegador no deja que JavaScript lo lea, asi que el
         * frontend solo veria "401" y tendria que adivinar. Exponerlo convierte
         * un fallo mudo en un mensaje accionable en pantalla.
         */
        config.setExposedHeaders(List.of("X-Correlation-Id", "WWW-Authenticate"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
