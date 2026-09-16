package cl.duoc.pedidos360.common.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Traduce un access token de Microsoft Entra ID a autoridades de Spring Security.
 *
 * Entra ID entrega dos cosas distintas y es importante no confundirlas:
 *
 *  - claim "roles": los App Roles asignados al usuario o a la aplicacion.
 *    Responde a "QUIEN eres" -> se mapea a ROLE_ADMIN, ROLE_OPERATOR, ...
 *
 *  - claim "scp" (o "scope"): los scopes delegados que el frontend pidio via MSAL.
 *    Responde a "QUE le permitio el usuario hacer a esta app en su nombre"
 *    -> se mapea a SCOPE_orders.read, SCOPE_orders.write, ...
 *
 * Se exponen ambos para poder combinar @PreAuthorize("hasRole('ADMIN')")
 * con @PreAuthorize("hasAuthority('SCOPE_orders.write')").
 */
public class JwtAuthoritiesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLES_CLAIM = "roles";
    private static final String SCOPE_CLAIM = "scp";
    private static final String SCOPE_CLAIM_ALT = "scope";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String SCOPE_PREFIX = "SCOPE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.addAll(rolesFrom(jwt));
        authorities.addAll(scopesFrom(jwt));
        return new JwtAuthenticationToken(jwt, authorities, principalNameOf(jwt));
    }

    private Collection<GrantedAuthority> rolesFrom(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(JwtAuthoritiesConverter::normalizeRole)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }

    private Collection<GrantedAuthority> scopesFrom(Jwt jwt) {
        String raw = jwt.getClaimAsString(SCOPE_CLAIM);
        if (raw == null) {
            raw = jwt.getClaimAsString(SCOPE_CLAIM_ALT);
        }
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.trim().split("\\s+")).stream()
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority(SCOPE_PREFIX + scope))
                .toList();
    }

    /**
     * En el Caso 0 los roles se enuncian en espanol (Operador, Cliente) pero en las
     * pantallas Angular en ingles (Operator, Customer). Se normaliza a un unico
     * vocabulario en mayusculas para que el backend no dependa del idioma del tenant.
     */
    private static String normalizeRole(String raw) {
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "OPERADOR" -> Roles.OPERATOR;
            case "CLIENTE" -> Roles.CUSTOMER;
            case "ADMINISTRADOR" -> Roles.ADMIN;
            default -> value.replace('-', '_').replace('.', '_');
        };
    }

    /**
     * Entra ID usa "oid" (object id, estable por usuario) y "preferred_username".
     * Se prefiere el username legible para la trazabilidad de auditoria y se cae a oid/sub.
     */
    private static String principalNameOf(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && !username.isBlank()) {
            return username;
        }
        String oid = jwt.getClaimAsString("oid");
        return (oid != null && !oid.isBlank()) ? oid : jwt.getSubject();
    }
}
