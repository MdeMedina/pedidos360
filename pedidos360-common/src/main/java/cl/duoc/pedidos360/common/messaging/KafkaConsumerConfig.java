package cl.duoc.pedidos360.common.messaging;

import cl.duoc.pedidos360.common.events.EventEnvelope;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumo de eventos con la politica de errores que pide el Caso 0:
 * tras N reintentos, el mensaje se publica en el Dead Letter Topic "<topico>.DLT"
 * con el mensaje original mas los metadatos del error.
 *
 * Detalle importante: ErrorHandlingDeserializer. Si un mensaje llega con JSON
 * corrupto, la deserializacion falla ANTES de entrar al listener y el consumidor
 * entra en un bucle infinito de "poison pill" que bloquea la particion completa.
 * Envolviendo el deserializador, el fallo se convierte en un registro con error que
 * el error handler puede mandar a la DLT y seguir avanzando.
 */
@Configuration
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "kafka-enabled", havingValue = "true")
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    ConsumerFactory<String, EventEnvelope> envelopeConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, EventEnvelope.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "cl.duoc.pedidos360.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, EventEnvelope> envelopeListenerFactory(
            ConsumerFactory<String, EventEnvelope> consumerFactory,
            KafkaTemplate<?, ?> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        // 3 intentos separados por 2 segundos; despues, a la DLT.
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 2L));
        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        factory.setCommonErrorHandler(errorHandler);

        log.info("Kafka listener configurado con reintentos + Dead Letter Topic");
        return factory;
    }
}
