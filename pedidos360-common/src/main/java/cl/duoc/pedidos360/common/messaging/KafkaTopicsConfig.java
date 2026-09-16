package cl.duoc.pedidos360.common.messaging;

import java.util.Map;

import cl.duoc.pedidos360.common.events.Messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Topicos Kafka del Caso 0.
 *
 * orders.events (3 particiones, delete, 7 dias): flujo canonico de eventos de negocio.
 *   Se particiona por orderId -> todos los eventos del mismo pedido caen en la misma
 *   particion y por lo tanto se consumen EN ORDEN. Sin esa clave, "OrderDelivered"
 *   podria procesarse antes que "OrderAccepted".
 *
 * audit.timeline (compact + delete): compactado por clave para conservar el ultimo
 *   estado por pedido, y ademas con retencion por tiempo para el historial.
 */
@Configuration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "kafka-enabled", havingValue = "true")
public class KafkaTopicsConfig {

    @Bean
    NewTopic ordersEventsTopic() {
        return TopicBuilder.name(Messaging.TOPIC_ORDERS_EVENTS)
                .partitions(3)
                .replicas(1)
                .configs(Map.of(
                        TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
                        TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7L * 24 * 60 * 60 * 1000)))
                .build();
    }

    @Bean
    NewTopic auditTimelineTopic() {
        return TopicBuilder.name(Messaging.TOPIC_AUDIT_TIMELINE)
                .partitions(3)
                .replicas(1)
                .configs(Map.of(
                        TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_COMPACT + "," + TopicConfig.CLEANUP_POLICY_DELETE,
                        TopicConfig.RETENTION_MS_CONFIG, String.valueOf(30L * 24 * 60 * 60 * 1000)))
                .build();
    }
}
