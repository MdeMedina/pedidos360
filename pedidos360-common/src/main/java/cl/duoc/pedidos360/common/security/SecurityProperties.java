package cl.duoc.pedidos360.common.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de seguridad compartida por todos los microservicios.
 *
 * pedidos360.security.audiences -> valores aceptados en el claim "aud".
 *   Es el control que impide que un token emitido para OTRA API del mismo tenant
 *   sirva para entrar aqui. API Gateway tambien lo valida, pero el microservicio
 *   NO debe confiar ciegamente en el gateway (defensa en profundidad).
 *
 * pedidos360.security.public-paths -> rutas sin token (health, swagger, docs).
 *
 * pedidos360.security.dev-secret -> SOLO perfil dev: clave HMAC para firmar/validar
 *   tokens locales y poder demostrar la app sin depender de Azure.
 *
 * pedidos360.security.cors-enabled -> debe quedar en FALSE cuando la aplicacion
 *   queda publicada detras de AWS API Gateway, porque el gateway ya agrega sus
 *   propias cabeceras CORS. Si ambos las agregan, la respuesta llega con dos
 *   Access-Control-Allow-Origin y el navegador la rechaza con un mensaje que no
 *   menciona la duplicacion, asi que el error es dificil de encontrar.
 *
 * pedidos360.security.cors-allowed-origins -> origenes permitidos cuando el CORS
 *   lo resuelve la aplicacion (desarrollo local).
 */
@ConfigurationProperties(prefix = "pedidos360.security")
public class SecurityProperties {

    private List<String> audiences = List.of();

    private List<String> publicPaths = List.of(
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    );

    private String devSecret = "pedidos360-dev-secret-cambia-esto-en-produccion-1234567890";

    private String devIssuer = "http://localhost/dev-issuer";

    /**
     * URI del JWKS (claves publicas del emisor).
     *
     * Si se define, el decoder se construye directamente contra este endpoint y NO
     * hace ninguna llamada de red al arrancar: las claves se descargan la primera vez
     * que llega un token y se cachean. Si se deja vacio, Spring hace el descubrimiento
     * OIDC (.well-known/openid-configuration) DURANTE el arranque, y si el emisor no
     * responde en ese instante el servicio no levanta.
     *
     * Para Microsoft Entra ID es deterministico:
     *   https://login.microsoftonline.com/<TENANT_ID>/discovery/v2.0/keys
     */
    private String jwkSetUri = "";

    private boolean corsEnabled = true;

    private List<String> corsAllowedOrigins = List.of("http://localhost:*", "https://localhost:*");

    public List<String> getAudiences() {
        return audiences;
    }

    public void setAudiences(List<String> audiences) {
        this.audiences = audiences;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public String getDevSecret() {
        return devSecret;
    }

    public void setDevSecret(String devSecret) {
        this.devSecret = devSecret;
    }

    public String getDevIssuer() {
        return devIssuer;
    }

    public void setDevIssuer(String devIssuer) {
        this.devIssuer = devIssuer;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public boolean isCorsEnabled() {
        return corsEnabled;
    }

    public void setCorsEnabled(boolean corsEnabled) {
        this.corsEnabled = corsEnabled;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }
}
