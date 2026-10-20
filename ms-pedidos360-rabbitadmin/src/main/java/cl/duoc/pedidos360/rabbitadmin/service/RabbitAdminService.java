package cl.duoc.pedidos360.rabbitadmin.service;

import java.util.List;
import java.util.Map;

import cl.duoc.pedidos360.common.events.Messaging;
import cl.duoc.pedidos360.common.web.BusinessException;
import cl.duoc.pedidos360.rabbitadmin.api.RabbitAdminDtos.BindingRequest;
import cl.duoc.pedidos360.rabbitadmin.api.RabbitAdminDtos.CreateExchangeRequest;
import cl.duoc.pedidos360.rabbitadmin.api.RabbitAdminDtos.CreateQueueRequest;
import cl.duoc.pedidos360.rabbitadmin.api.RabbitAdminDtos.QueueInfoResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Toda la operativa contra RabbitMQ vive aqui (guia 2.3.1).
 *
 * El controlador llama a metodos de alto nivel —crearCola, eliminarExchange— y
 * no conoce RabbitAdmin, QueueBuilder ni BindingBuilder. Si manana se cambiara
 * la libreria de mensajeria, el controlador no se tocaria.
 *
 * Diferencia deliberada con el ejemplo de la guia: alli cada metodo captura la
 * excepcion y la escribe en el log, devolviendo void. Eso deja al cliente HTTP
 * creyendo que la operacion funciono cuando en realidad fallo. Aqui los errores
 * se traducen a BusinessException con su codigo HTTP, de modo que el
 * administrador recibe 409 si la cola ya existe con otra definicion y 404 si
 * pregunta por una que no existe.
 */
@Service
public class RabbitAdminService {

    private static final Logger log = LoggerFactory.getLogger(RabbitAdminService.class);

    /** Topologia del Caso 0: se protege para que la administracion no la borre. */
    private static final List<String> COLAS_PROTEGIDAS = List.of(
            Messaging.Q_EMAIL, Messaging.Q_KITCHEN, Messaging.Q_INVOICE,
            Messaging.Q_EMAIL_DLQ, Messaging.Q_KITCHEN_DLQ, Messaging.Q_INVOICE_DLQ);

    private static final List<String> EXCHANGES_PROTEGIDOS = List.of(
            Messaging.EXCHANGE_DIRECT, Messaging.EXCHANGE_TOPIC, Messaging.EXCHANGE_DLX);

    private final RabbitAdmin rabbitAdmin;

    public RabbitAdminService(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
    }

    // ---------------------------------------------------------------- colas

    public QueueInfoResponse crearCola(CreateQueueRequest request) {
        QueueBuilder builder = request.durableOrDefault()
                ? QueueBuilder.durable(request.name())
                : QueueBuilder.nonDurable(request.name());

        if (request.exclusiveOrDefault()) {
            builder.exclusive();
        }
        if (request.autoDeleteOrDefault()) {
            builder.autoDelete();
        }
        Map<String, Object> args = request.arguments();
        if (args != null && !args.isEmpty()) {
            builder.withArguments(args);
        }

        Queue queue = builder.build();
        ejecutar("cola", request.name(), () -> rabbitAdmin.declareQueue(queue));
        log.info("Cola declarada: {} (durable={})", request.name(), request.durableOrDefault());
        return consultarCola(request.name());
    }

    public QueueInfoResponse consultarCola(String nombre) {
        QueueInformation info = rabbitAdmin.getQueueInfo(nombre);
        if (info == null) {
            throw BusinessException.notFound("Cola", nombre);
        }
        return new QueueInfoResponse(info.getName(), info.getMessageCount(), info.getConsumerCount());
    }

    /** Estado de la topologia del Caso 0, para la pantalla de administracion. */
    public List<QueueInfoResponse> listarColasDelCaso() {
        return COLAS_PROTEGIDAS.stream()
                .map(rabbitAdmin::getQueueInfo)
                .filter(java.util.Objects::nonNull)
                .map(i -> new QueueInfoResponse(i.getName(), i.getMessageCount(), i.getConsumerCount()))
                .toList();
    }

    public void eliminarCola(String nombre) {
        verificarNoProtegida(nombre, COLAS_PROTEGIDAS, "cola");
        if (rabbitAdmin.getQueueInfo(nombre) == null) {
            throw BusinessException.notFound("Cola", nombre);
        }
        ejecutar("cola", nombre, () -> rabbitAdmin.deleteQueue(nombre));
        log.info("Cola eliminada: {}", nombre);
    }

    /** Vacia la cola sin borrarla. Util para limpiar una DLQ ya revisada. */
    public int purgarCola(String nombre) {
        if (rabbitAdmin.getQueueInfo(nombre) == null) {
            throw BusinessException.notFound("Cola", nombre);
        }
        int purgados = rabbitAdmin.purgeQueue(nombre);
        log.info("Cola purgada: {} ({} mensajes eliminados)", nombre, purgados);
        return purgados;
    }

    // ----------------------------------------------------------- exchanges

    public void crearExchange(CreateExchangeRequest request) {
        Exchange exchange = construirExchange(request);
        ejecutar("exchange", request.name(), () -> rabbitAdmin.declareExchange(exchange));
        log.info("Exchange declarado: {} (tipo={})", request.name(), request.type());
    }

    public void eliminarExchange(String nombre) {
        verificarNoProtegida(nombre, EXCHANGES_PROTEGIDOS, "exchange");
        ejecutar("exchange", nombre, () -> rabbitAdmin.deleteExchange(nombre));
        log.info("Exchange eliminado: {}", nombre);
    }

    private Exchange construirExchange(CreateExchangeRequest r) {
        boolean durable = r.durableOrDefault();
        boolean autoDelete = r.autoDeleteOrDefault();
        return switch (r.type().toLowerCase()) {
            case "direct" -> new DirectExchange(r.name(), durable, autoDelete);
            case "topic" -> new TopicExchange(r.name(), durable, autoDelete);
            case "fanout" -> new FanoutExchange(r.name(), durable, autoDelete);
            case "headers" -> new CustomExchange(r.name(), "headers", durable, autoDelete);
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Tipo de exchange no soportado: " + r.type());
        };
    }

    // ------------------------------------------------------------ bindings

    public void crearBinding(BindingRequest request) {
        Binding binding = BindingBuilder
                .bind(new Queue(request.queue()))
                .to(new DirectExchange(request.exchange()))
                .with(request.routingKeyOrEmpty());
        ejecutar("binding", request.queue() + " -> " + request.exchange(),
                () -> rabbitAdmin.declareBinding(binding));
        log.info("Binding declarado: {} -> {} con rk '{}'",
                request.queue(), request.exchange(), request.routingKeyOrEmpty());
    }

    public void eliminarBinding(BindingRequest request) {
        Binding binding = new Binding(request.queue(), Binding.DestinationType.QUEUE,
                request.exchange(), request.routingKeyOrEmpty(), null);
        ejecutar("binding", request.queue() + " -> " + request.exchange(),
                () -> rabbitAdmin.removeBinding(binding));
        log.info("Binding eliminado: {} -> {}", request.queue(), request.exchange());
    }

    // ------------------------------------------------------------ internos

    private void verificarNoProtegida(String nombre, List<String> protegidas, String tipo) {
        if (protegidas.contains(nombre)) {
            String articulo = "cola".equals(tipo) ? "La cola" : "El exchange";
            throw new BusinessException(HttpStatus.CONFLICT,
                    articulo + " '" + nombre + "' forma parte de la topologia base de Pedidos360 "
                            + "y no puede eliminarse desde la API. Borrarlo dejaria el sistema "
                            + "de notificaciones sin funcionar.");
        }
    }

    /**
     * Traduce los fallos de AMQP a respuestas HTTP con sentido, en vez de
     * tragarselos en el log como hace el ejemplo de la guia.
     */
    private void ejecutar(String recurso, String nombre, Runnable operacion) {
        try {
            operacion.run();
        } catch (org.springframework.amqp.AmqpIOException | org.springframework.amqp.AmqpConnectException ex) {
            log.error("RabbitMQ no responde al operar sobre {} '{}'", recurso, nombre, ex);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RabbitMQ no esta disponible en este momento.");
        } catch (RuntimeException ex) {
            log.error("Fallo la operacion sobre {} '{}'", recurso, nombre, ex);
            throw new BusinessException(HttpStatus.CONFLICT,
                    "No se pudo operar sobre el " + recurso + " '" + nombre + "': "
                            + causaRaiz(ex));
        }
    }

    private static String causaRaiz(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? ex.getClass().getSimpleName() : t.getMessage();
    }
}
