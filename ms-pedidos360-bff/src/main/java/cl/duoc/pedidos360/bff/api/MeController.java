package cl.duoc.pedidos360.bff.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import cl.duoc.pedidos360.common.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El frontend necesita saber QUE ROL tiene el usuario para dibujar el menu.
 *
 * Podria leerlo del ID Token en el navegador, pero eso es solo una pista de UI:
 * un usuario puede editar el localStorage. Esta respuesta la calcula el backend a
 * partir del access token ya validado, y es la unica fuente confiable.
 * Aun asi, la decision de seguridad real la toma siempre el backend, nunca el menu.
 */
@RestController
@Tag(name = "Sesion", description = "Identidad del usuario autenticado")
public class MeController {

    @GetMapping("/api/me")
    @Operation(summary = "Devuelve el usuario y los roles resueltos desde el access token")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        Set<String> roles = CurrentUser.roles();
        return new MeResponse(
                CurrentUser.username(),
                jwt.getClaimAsString("name"),
                roles,
                jwt.getAudience(),
                jwt.getIssuer() == null ? null : jwt.getIssuer().toString(),
                jwt.getExpiresAt(),
                scopesOf(jwt));
    }

    private List<String> scopesOf(Jwt jwt) {
        String scp = jwt.getClaimAsString("scp");
        if (scp == null) {
            scp = jwt.getClaimAsString("scope");
        }
        return scp == null || scp.isBlank() ? List.of() : List.of(scp.trim().split("\\s+"));
    }

    public record MeResponse(String username, String displayName, Set<String> roles,
                             List<String> audience, String issuer, Instant expiresAt, List<String> scopes) {
    }
}
