package cl.duoc.pedidos360.common.security;

/**
 * Roles de negocio del Caso 0. Se publican en Entra ID como "App Roles" y viajan
 * dentro del access token en el claim {@code roles}.
 *
 * Spring Security espera el prefijo ROLE_ para que funcionen hasRole()/@PreAuthorize("hasRole(...)"),
 * por eso {@link JwtAuthoritiesConverter} antepone ese prefijo al leer el claim.
 */
public final class Roles {

    private Roles() {
    }

    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String AUDITOR = "AUDITOR";

    public static final String HAS_ADMIN = "hasRole('" + ADMIN + "')";
    public static final String HAS_OPERATOR = "hasRole('" + OPERATOR + "')";
    public static final String HAS_ADMIN_OR_OPERATOR = "hasAnyRole('" + ADMIN + "','" + OPERATOR + "')";
    public static final String HAS_ANY_BUSINESS_ROLE =
            "hasAnyRole('" + ADMIN + "','" + OPERATOR + "','" + CUSTOMER + "','" + AUDITOR + "')";
    public static final String HAS_AUDIT_READ = "hasAnyRole('" + ADMIN + "','" + AUDITOR + "')";
}
