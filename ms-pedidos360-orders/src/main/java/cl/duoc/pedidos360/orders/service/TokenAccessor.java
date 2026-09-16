package cl.duoc.pedidos360.orders.service;

import cl.duoc.pedidos360.common.web.BusinessException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Recupera el access token en crudo para reenviarlo a otros microservicios. */
@Component
public class TokenAccessor {

    public String currentBearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getTokenValue();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "No hay un token JWT en el contexto de seguridad");
    }
}
