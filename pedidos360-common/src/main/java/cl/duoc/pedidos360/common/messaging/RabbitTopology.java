package cl.duoc.pedidos360.common.messaging;

import java.util.Map;

import cl.duoc.pedidos360.common.events.Messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Topologia RabbitMQ del Caso 0: 3 flujos de trabajo, cada uno con su cola
 * principal y su DLQ, mas tres exchanges (direct, topic y el DLX).
 *
 * Se declara desde una clase compartida para que el productor (orders) y el
 * consumidor (notify) declaren EXACTAMENTE la misma topologia. Las declaraciones
 * AMQP son idempotentes: declarar dos veces lo mismo no rompe nada, pero declarar
 * lo mismo con argumentos distintos si, y ese es el bug clasico que esto evita.
 *
 * Por que DLQ: si el consumidor hace NACK sin requeue (mensaje irreparable), el
 * broker lo manda al dead-letter-exchange en vez de perderlo o reintentarlo infinito.
 * Desde ahi se puede inspeccionar y reprocesar.
 */
@Configuration
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "rabbit-enabled", havingValue = "true")
public class RabbitTopology {

    @Bean
    DirectExchange cmdDirectExchange() {
        return new DirectExchange(Messaging.EXCHANGE_DIRECT, true, false);
    }

    @Bean
    TopicExchange cmdTopicExchange() {
        return new TopicExchange(Messaging.EXCHANGE_TOPIC, true, false);
    }

    @Bean
    DirectExchange cmdDeadLetterExchange() {
        return new DirectExchange(Messaging.EXCHANGE_DLX, true, false);
    }

    private static Queue mainQueue(String name, String deadLetterRoutingKey) {
        return QueueBuilder.durable(name)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", Messaging.EXCHANGE_DLX,
                        "x-dead-letter-routing-key", deadLetterRoutingKey))
                .build();
    }

    @Bean
    Queue emailQueue() {
        return mainQueue(Messaging.Q_EMAIL, Messaging.RK_EMAIL_SEND);
    }

    @Bean
    Queue kitchenQueue() {
        return mainQueue(Messaging.Q_KITCHEN, Messaging.RK_KITCHEN_TICKET);
    }

    @Bean
    Queue invoiceQueue() {
        return mainQueue(Messaging.Q_INVOICE, Messaging.RK_INVOICE_GEN);
    }

    @Bean
    Queue emailDlq() {
        return QueueBuilder.durable(Messaging.Q_EMAIL_DLQ).build();
    }

    @Bean
    Queue kitchenDlq() {
        return QueueBuilder.durable(Messaging.Q_KITCHEN_DLQ).build();
    }

    @Bean
    Queue invoiceDlq() {
        return QueueBuilder.durable(Messaging.Q_INVOICE_DLQ).build();
    }

    // --- bindings direct: enrutamiento exacto por routing key ---

    @Bean
    Binding emailDirectBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdDirectExchange()).with(Messaging.RK_EMAIL_SEND);
    }

    @Bean
    Binding kitchenDirectBinding() {
        return BindingBuilder.bind(kitchenQueue()).to(cmdDirectExchange()).with(Messaging.RK_KITCHEN_TICKET);
    }

    @Bean
    Binding invoiceDirectBinding() {
        return BindingBuilder.bind(invoiceQueue()).to(cmdDirectExchange()).with(Messaging.RK_INVOICE_GEN);
    }

    // --- bindings topic: variantes por patron (email.send.high, kitchen.ticket.thermal, ...) ---

    @Bean
    Binding emailTopicBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdTopicExchange()).with("email.*");
    }

    @Bean
    Binding kitchenTopicBinding() {
        return BindingBuilder.bind(kitchenQueue()).to(cmdTopicExchange()).with("kitchen.#");
    }

    @Bean
    Binding invoiceTopicBinding() {
        return BindingBuilder.bind(invoiceQueue()).to(cmdTopicExchange()).with("invoice.*");
    }

    // --- bindings DLQ ---

    @Bean
    Binding emailDlqBinding() {
        return BindingBuilder.bind(emailDlq()).to(cmdDeadLetterExchange()).with(Messaging.RK_EMAIL_SEND);
    }

    @Bean
    Binding kitchenDlqBinding() {
        return BindingBuilder.bind(kitchenDlq()).to(cmdDeadLetterExchange()).with(Messaging.RK_KITCHEN_TICKET);
    }

    @Bean
    Binding invoiceDlqBinding() {
        return BindingBuilder.bind(invoiceDlq()).to(cmdDeadLetterExchange()).with(Messaging.RK_INVOICE_GEN);
    }

    /** Los mensajes viajan como JSON, no como objetos serializados de Java:
     *  el consumidor podria estar escrito en otro lenguaje. */
    @Bean
    Jackson2JsonMessageConverter rabbitJsonConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
