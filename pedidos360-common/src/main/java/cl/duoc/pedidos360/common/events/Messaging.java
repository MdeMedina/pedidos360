package cl.duoc.pedidos360.common.events;

/**
 * Nombres de la topologia de mensajeria del Caso 0, centralizados para que
 * productor y consumidor no se desincronicen por un typo.
 */
public final class Messaging {

    private Messaging() {
    }

    // ---- RabbitMQ (commands: "haz esto") ----
    public static final String EXCHANGE_DIRECT = "cmd.direct";
    public static final String EXCHANGE_TOPIC = "cmd.topic";
    public static final String EXCHANGE_DLX = "cmd.dead.dlx";

    public static final String Q_EMAIL = "q.cmd.email";
    public static final String Q_KITCHEN = "q.cmd.kitchen";
    public static final String Q_INVOICE = "q.cmd.invoice";
    public static final String Q_EMAIL_DLQ = "q.cmd.email.dlq";
    public static final String Q_KITCHEN_DLQ = "q.cmd.kitchen.dlq";
    public static final String Q_INVOICE_DLQ = "q.cmd.invoice.dlq";

    public static final String RK_EMAIL_SEND = "email.send";
    public static final String RK_KITCHEN_TICKET = "kitchen.ticket";
    public static final String RK_INVOICE_GEN = "invoice.gen";

    // ---- Kafka (events: "esto ya paso") ----
    public static final String TOPIC_ORDERS_EVENTS = "orders.events";
    public static final String TOPIC_AUDIT_TIMELINE = "audit.timeline";
}
