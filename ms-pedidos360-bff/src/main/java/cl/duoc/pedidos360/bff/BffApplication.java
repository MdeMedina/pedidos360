package cl.duoc.pedidos360.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-pedidos360-bff :: Backend For Frontend.
 *
 * Es el unico backend que Angular conoce. Cumple tres funciones:
 *  1. Punto de entrada unico detras de AWS API Gateway (una sola integracion HTTP
 *     que configurar en el gateway, en vez de cinco).
 *  2. Propagacion del access token hacia los microservicios (token relay).
 *  3. Agregacion: el dashboard necesita datos de orders y report; el BFF hace las
 *     dos llamadas y devuelve una sola respuesta, evitando un waterfall en el browser.
 */
@SpringBootApplication(scanBasePackages = "cl.duoc.pedidos360")
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
