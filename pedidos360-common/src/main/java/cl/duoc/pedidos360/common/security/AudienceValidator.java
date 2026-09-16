package cl.duoc.pedidos360.common.security;

import java.util.List;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Valida el claim "aud" del access token.
 *
 * Spring valida por defecto firma, issuer y expiracion, pero NO la audiencia.
 * Sin esta clase, cualquier token valido del mismo tenant (por ejemplo uno emitido
 * para Microsoft Graph) seria aceptado por esta API. Es el equivalente en el backend
 * al campo "audiences" del JWT Authorizer de AWS API Gateway.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final List<String> expectedAudiences;

    public AudienceValidator(List<String> expectedAudiences) {
        this.expectedAudiences = expectedAudiences == null ? List.of() : expectedAudiences;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (expectedAudiences.isEmpty()) {
            return OAuth2TokenValidatorResult.success();
        }
        List<String> audience = token.getAudience();
        boolean ok = audience != null && audience.stream().anyMatch(expectedAudiences::contains);
        if (ok) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "La audiencia del token no corresponde a esta API. Esperado: " + expectedAudiences
                        + ", recibido: " + audience,
                "https://tools.ietf.org/html/rfc6750#section-3.1"));
    }
}
