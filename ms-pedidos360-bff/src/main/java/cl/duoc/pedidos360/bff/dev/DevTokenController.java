package cl.duoc.pedidos360.bff.dev;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import cl.duoc.pedidos360.common.security.SecurityProperties;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SOLO PERFIL dev. Emite un access token firmado con HMAC que imita la forma de
 * un token de Microsoft Entra ID (mismos claims: iss, aud, sub, roles, scp).
 *
 * Para que sirve:
 *  - Permite desarrollar, probar y DEFENDER toda la solucion aunque el tenant de
 *    Azure o la cuenta de AWS Academy no esten disponibles.
 *  - Permite cambiar de rol en un segundo para demostrar que la autorizacion
 *    realmente bloquea (un Cliente pidiendo /api/report recibe 403).
 *
 * Lo que NO es: un reemplazo de Entra ID. No hay login, ni consentimiento, ni
 * rotacion de claves, ni MFA. Nunca se activa el perfil dev fuera del laboratorio.
 */
@RestController
@Profile("dev")
@RequestMapping("/dev")
@Tag(name = "Dev", description = "Emision de tokens locales para demo sin Azure (solo perfil dev)")
public class DevTokenController {

    private final SecurityProperties properties;

    public DevTokenController(SecurityProperties properties) {
        this.properties = properties;
    }

    public record TokenRequest(String username, List<String> roles, Integer minutes) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                String username, List<String> roles) {
    }

    @PostMapping("/token")
    @Operation(summary = "Emite un access token local con los roles indicados")
    public TokenResponse token(@RequestBody TokenRequest request) throws Exception {
        String username = (request.username() == null || request.username().isBlank())
                ? "demo@pedidos360.cl" : request.username();
        List<String> roles = (request.roles() == null || request.roles().isEmpty())
                ? List.of("ADMIN") : request.roles();
        int minutes = request.minutes() == null ? 120 : request.minutes();
        return mint(username, roles, minutes);
    }

    @GetMapping("/token")
    @Operation(summary = "Variante por query string, comoda para probar con el navegador o curl")
    public TokenResponse token(@RequestParam(defaultValue = "demo@pedidos360.cl") String username,
                               @RequestParam(defaultValue = "ADMIN") List<String> roles,
                               @RequestParam(defaultValue = "120") int minutes) throws Exception {
        return mint(username, roles, minutes);
    }

    @GetMapping("/roles")
    @Operation(summary = "Roles disponibles en la demo")
    public List<String> roles() {
        return List.of("ADMIN", "OPERATOR", "CUSTOMER", "AUDITOR");
    }

    private TokenResponse mint(String username, List<String> roles, int minutes) throws Exception {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofMinutes(minutes));

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.getDevIssuer())
                .audience(properties.getAudiences().isEmpty()
                        ? List.of("api://pedidos360-api") : properties.getAudiences())
                .subject(username)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim("preferred_username", username)
                .claim("name", username.split("@")[0])
                .claim("oid", UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8)).toString())
                // Mismo nombre de claim que usa Entra ID para los App Roles.
                .claim("roles", roles)
                // Mismo nombre de claim que usa Entra ID para los scopes delegados.
                .claim("scp", "orders.read orders.write catalog.read catalog.write report.read audit.read")
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(properties.getDevSecret().getBytes(StandardCharsets.UTF_8)));

        return new TokenResponse(jwt.serialize(), "Bearer",
                Duration.between(now, exp).toSeconds(), username, roles);
    }
}
