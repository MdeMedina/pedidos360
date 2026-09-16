package cl.duoc.pedidos360.common.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Acceso al usuario autenticado sin tener que inyectar el token en cada firma.
 * Lo usan orders (para filtrar los pedidos del cliente) y audit (para el "quien").
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : auth.getName();
    }

    public static Set<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toSet());
    }

    public static boolean hasRole(String role) {
        return roles().contains(role);
    }

    /** Admin y Operador ven todos los pedidos; Cliente solo los propios. */
    public static boolean canSeeAllOrders() {
        return hasRole(Roles.ADMIN) || hasRole(Roles.OPERATOR) || hasRole(Roles.AUDITOR);
    }
}
