package cl.duoc.pedidos360.common.web;

import org.springframework.http.HttpStatus;

/** Error de regla de negocio (p.ej. "no se puede despachar sin aceptar"). */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(String message) {
        this(HttpStatus.CONFLICT, message);
    }

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BusinessException notFound(String what, Object id) {
        return new BusinessException(HttpStatus.NOT_FOUND, what + " no encontrado: " + id);
    }
}
