package cl.duoc.pedidos360.audit.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    boolean existsByEventId(String eventId);

    List<AuditEvent> findByAggregateIdOrderByOccurredAtAsc(String aggregateId);

    /**
     * Filtros opcionales del Caso 0: usuario, rango de fechas y tipo de evento.
     * Cada parametro nulo desactiva su condicion, evitando escribir cuatro consultas.
     */
    @Query("""
            select e from AuditEvent e
            where (:actor is null or lower(e.actor) like lower(concat('%', :actor, '%')))
              and (:type is null or e.type = :type)
              and (:aggregateId is null or e.aggregateId = :aggregateId)
              and (:from is null or e.occurredAt >= :from)
              and (:to is null or e.occurredAt <= :to)
            order by e.occurredAt desc
            """)
    List<AuditEvent> search(@Param("actor") String actor,
                            @Param("type") String type,
                            @Param("aggregateId") String aggregateId,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Pageable pageable);

    @Query("select distinct e.type from AuditEvent e order by e.type")
    List<String> findDistinctTypes();
}
