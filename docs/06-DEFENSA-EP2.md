# Defensa EP2 · Mensajería asíncrona con RabbitMQ

Guion de la interrogación individual: qué responder y **dónde mostrarlo**.
Los 11 indicadores de la planilla, en orden.

---

## Datos que vas a necesitar abiertos

| Qué | Dónde |
|---|---|
| Consola `rabbit1` | http://34.229.10.208:15672 · `pedidos360` / `pedidos360` |
| Consola `rabbit2` | http://34.229.10.208:15673 · mismas credenciales |
| Frontend | http://localhost:4200 (`ng serve`) |
| API Gateway | https://9owu4w9iml.execute-api.us-east-1.amazonaws.com |
| SSH a ec2-mq | `ssh -i parPrueba.pem ec2-user@34.229.10.208` |
| SSH a ec2-apps | `ssh -i parPrueba.pem ec2-user@32.196.64.16` |

Instancias: `ec2-mq` 34.229.10.208 (privada 172.31.31.49) ·
`ec2-apps` 32.196.64.16 (privada 172.31.30.59) ·
`ec2-kafka` 13.218.240.104 (privada 172.31.30.126).

**Ten tres terminales abiertas antes de empezar**: una con SSH a `ec2-mq`, otra
con SSH a `ec2-apps` mostrando `docker logs -f pedidos360-notify`, y otra libre.
Abrir sesiones en medio de la demostración es el que más tiempo pierde.

---

## P1 · Dos nodos de RabbitMQ · 5%

> *¿Qué parámetros deben ser iguales y cuáles distintos entre rabbit1 y rabbit2?*

**Muestra** `infra/mq/compose.yml`. La tabla está escrita en la cabecera del
archivo, así que puedes leerla en pantalla.

| | Qué | Por qué |
|---|---|---|
| **Igual** | `RABBITMQ_ERLANG_COOKIE` | secreto compartido; sin él los nodos no se reconocen y el clúster nunca se forma |
| | imagen y versión | nodos de versiones distintas no agrupan |
| | usuario y clave | el clúster comparte usuarios y permisos |
| | `rabbitmq.conf` | los dos leen la misma lista de miembros |
| **Distinto** | `hostname` | define el nombre de nodo `rabbit@<hostname>` |
| | puertos publicados | comparten host: `5672/15672` y `5673/15673` |
| | volumen de datos | cada nodo necesita su propia base Mnesia |

Usé el bloque YAML `x-rabbit-comun` con anclas (`&` y `<<:`) justamente para que
lo común esté escrito **una sola vez**. Si la cookie estuviera copiada en los dos
servicios, el día que alguien cambie una y no la otra el clúster deja de formarse
y el error no dice por qué.

**Y luego** en la terminal:

```bash
docker ps --filter name=pedidos360-rabbit
```

Verás `pedidos360-rabbit1` con `0.0.0.0:5672->5672` y `pedidos360-rabbit2` con
`0.0.0.0:5673->5672`. Fíjate: **dentro** del contenedor los dos usan 5672; lo que
cambia es el puerto publicado en el host. Eso es lo correcto — el nodo no tiene
por qué saber en qué puerto lo exponen.

---

## P2 · Clúster de dos nodos · 8%

> *Demuestre que rabbit1 y rabbit2 forman un clúster.*

```bash
docker exec pedidos360-rabbit1 rabbitmqctl cluster_status
```

Señala estas cuatro cosas de la salida:

```
Cluster name: pedidos360-cluster
Disc Nodes:     rabbit@rabbit1, rabbit@rabbit2
Running Nodes:  rabbit@rabbit1, rabbit@rabbit2
Alarms: (none)          Network Partitions: (none)
```

**Disc Nodes vs Running Nodes no es lo mismo y conviene que lo digas tú**: Disc
son los miembros del clúster, Running los que están arriba ahora. Si tiras un
nodo, sigue apareciendo en Disc pero desaparece de Running — y eso es lo correcto,
porque al volver se reincorpora en vez de tener que unirlo de nuevo.

**Luego la consola web**: http://34.229.10.208:15672 → *Overview* → *Nodes*. Los
dos nodos, en verde. Y entra a http://34.229.10.208:15673 para enseñar que es el
**otro** nodo y ve exactamente lo mismo: usuarios, colas y políticas compartidos.
Esa es la prueba visual más contundente de que es un clúster y no dos brokers
sueltos.

**Si pregunta cómo se unieron**, abre `infra/mq/rabbitmq.conf`:

```
cluster_formation.peer_discovery_backend = rabbit_peer_discovery_classic_config
cluster_formation.classic_config.nodes.1 = rabbit@rabbit1
cluster_formation.classic_config.nodes.2 = rabbit@rabbit2
```

No hay ningún `rabbitmqctl join_cluster` en ningún script. La lista de miembros
es fija y conocida, cada nodo la lee al arrancar y se une solo. Es declarativo:
el estado deseado está en un archivo versionado, no en comandos que alguien
ejecutó una vez.

> **Dato que suma**: la guía 2.3.1 trae `queue_master_location = all`. Eso está
> mal por partida doble — el parámetro se llama `queue_master_locatOR` y `all`
> no es un valor válido (acepta `client-local`, `random`, `min-masters`). Tal
> cual viene en la guía el nodo **no arranca**: `Conf file attempted to set
> unknown variable`. Usé `min-masters`, que reparte los masters de cola entre
> los dos nodos. Menciónalo: demuestra que lo probaste, no que lo copiaste.

---

## P3 · Clúster en la nube con Docker Compose · 17%

> *Muéstreme en la consola de AWS dónde se ejecuta el clúster y cómo lo levantó.*

Es el indicador que más pesa. Cuatro pantallas, en este orden:

**1 · La instancia.** Consola AWS → EC2 → Instances → `ec2-mq` en estado
`running`. Pestaña **Networking**: IP pública 34.229.10.208, IP privada
172.31.31.49.

**2 · El Security Group.** Pestaña **Security** → `sg-pedidos360-mq` →
*Inbound rules*:

| Puerto | Origen | Para qué |
|---|---|---|
| 22 | tu IP | SSH |
| 5672 | SG de `ec2-apps` | AMQP de `rabbit1` |
| 5673 | SG de `ec2-apps` | AMQP de `rabbit2` |
| 15672 / 15673 | tu IP | consolas web |

Di por qué: los microservicios entran **por IP privada**, nunca por internet.
Solo tu IP ve las consolas. El puerto AMQP no está abierto al mundo.

**3 · Cómo se levanta.** En la terminal con SSH:

```bash
cd ~/mq && cat compose.yml        # el archivo
docker compose up -d              # cómo se levantó
docker compose ps                 # los dos servicios arriba
```

**4 · Que funciona.** `docker exec pedidos360-rabbit1 rabbitmqctl cluster_status`
y la consola web en el navegador.

> **Si te pregunta por la arquitectura completa**, son tres instancias:
> `ec2-mq` (el clúster RabbitMQ), `ec2-kafka` (Kafka + ZooKeeper) y `ec2-apps`
> (los 7 microservicios en Docker). Delante va un **API Gateway HTTP API** con
> JWT Authorizer contra Microsoft Entra ID. Ningún microservicio está expuesto
> a internet: el único punto de entrada es el gateway.

---

## P4 · Tres colas para funcionalidades diferentes · 7%

> *Muéstreme las tres colas y qué caso de uso resuelve cada una.*

Consola → **Queues**. Hay seis: tres de negocio y tres DLQ.

| Cola | Caso de uso | Cuándo se dispara |
|---|---|---|
| `q.cmd.email` | avisar al cliente | en **toda** transición de estado |
| `q.cmd.kitchen` | imprimir la comanda | solo al **aceptar** |
| `q.cmd.invoice` | emitir la boleta | solo al **entregar** |

**Productor** — uno solo, en `OrderService` (`ms-pedidos360-orders`):

- `publishNotification()` → `q.cmd.email`
- `publishWorkflowCommands()` → `q.cmd.kitchen` y `q.cmd.invoice`

**Consumidores** — una clase por dominio en `ms-pedidos360-notify`:

- `consumer/email/EmailListener.java`
- `consumer/kitchen/KitchenListener.java`
- `consumer/invoice/InvoiceListener.java`

Si te preguntan por qué una clase por cola y no un `switch` en una sola: porque
cada dominio tiene su propia política de error. El correo sin destinatario es
irrecuperable, pero un timeout de la impresora de cocina sí se reintenta. Meter
las tres en un método obliga a un condicional que crece con cada caso nuevo.

El momento de disparo tampoco es decorativo: la boleta se emite **contra la
entrega efectiva**. Emitirla antes sería facturar algo que todavía puede
cancelarse.

---

## P5 · Cada cola tiene su DLQ · 9%

> *¿Qué argumentos conectan la DLQ con su cola principal?*

Consola → **Queues** → `q.cmd.email` → *Features*. Verás dos argumentos:

```
x-dead-letter-exchange     = cmd.dead.dlx
x-dead-letter-routing-key  = email.send
```

**Explica la mecánica**: la cola principal no conoce a su DLQ. Conoce un
**exchange** (`cmd.dead.dlx`) y una routing key. Cuando un mensaje muere, el
broker lo publica ahí y el enrutamiento normal decide dónde cae. El acoplamiento
es contra el exchange, no contra la cola — por eso puedes cambiar la DLQ de sitio
sin tocar la cola principal.

**Muestra el binding**: Consola → **Exchanges** → `cmd.dead.dlx` → *Bindings*.
Tres: `email.send → q.cmd.email.dlq`, `kitchen.ticket → q.cmd.kitchen.dlq`,
`invoice.gen → q.cmd.invoice.dlq`.

**En el código**: `RabbitTopology.java`, método `mainQueue()`. Las tres colas
principales se construyen con el mismo helper, así que es imposible que una
quede sin DLQ por descuido.

> **Un mensaje llega a la DLQ por tres motivos**, no solo por NACK: rechazo con
> `requeue=false`, expiración del TTL, o desbordamiento de `max-length`. En
> nuestras colas los tres están activos (ver P11).

---

## P6 · Exchanges direct y topic · 6%

> *¿Cuál es la diferencia y cuándo conviene cada uno?*

| | `cmd.direct` | `cmd.topic` |
|---|---|---|
| Criterio | igualdad exacta de routing key | patrón: `*` una palabra, `#` cero o más |
| Destinos | normalmente uno | varios a la vez |
| Para qué | **comandos** con destinatario conocido | **eventos** con clasificación jerárquica |

Consola → **Exchanges**. Hay tres: `cmd.direct`, `cmd.topic` y `cmd.dead.dlx`.

Bindings del topic: `email.*` → email, `invoice.*` → invoice,
`kitchen.#` → kitchen.

**La diferencia entre `*` y `#` la van a preguntar.** `kitchen.#` captura
`kitchen.ticket` y también `kitchen.ticket.termica.urgente`. `email.*` captura
`email.send` pero **no** `email.send.masivo`: `*` es exactamente una palabra.

**Cuál usa la aplicación**: `DefaultDomainEventPublisher.publishCommand()`
publica en `cmd.direct` con routing key exacta. Son órdenes con un destinatario
conocido. El `cmd.topic` está declarado y bindeado para las variantes —
`email.send.masivo`, `kitchen.ticket.termica` — que entran sin tocar código.

Si te preguntan por qué no todo por topic: porque un comando tiene **un** dueño.
Si por un error de binding dos consumidores reciben el mismo `invoice.gen`,
emites la boleta dos veces. El direct hace ese error imposible.

---

## P7 · Integración de microservicios con las colas · 10%

> *Realice una acción desde Angular y muestre el recorrido del mensaje.*

**Prepara la pantalla antes**: en la terminal con SSH a `ec2-apps`, deja
corriendo

```bash
docker logs -f pedidos360-notify
```

y en el navegador la consola de RabbitMQ en **Queues**.

**La acción**: entra a http://localhost:4200, inicia sesión como Admin, ve a
*Pedidos*, abre uno y cámbialo a **ACEPTADO**.

**El recorrido**, dicho en voz alta mientras ocurre:

```
Angular  →  API Gateway  →  JWT Authorizer (Entra ID)  →  BFF  →  orders
                                                                    │
                        orders valida la transición, reserva stock  │
                        y persiste. Recién ahí publica.             │
                                                                    ▼
                                            cmd.direct  ──┬── email.send    → q.cmd.email
                                                          └── kitchen.ticket → q.cmd.kitchen
                                                                    │
                                                            notify consume,
                                                            procesa y hace ACK
```

En los logs aparece `[COCINA] ticket para el pedido ...`. En la consola, un pico
en la tasa de mensajes de `q.cmd.kitchen` y la cola **vuelve a 0**.

**Lo importante de ese 0**: no significa que no pasó nada. Significa que el
mensaje se procesó y el consumidor confirmó con ACK. Una cola que **crece** es
la que indica problema.

**La frase clave**: orders **no espera** a que se imprima la comanda. Publica y
responde al usuario. Si la cocina está caída, el pedido igual queda aceptado y
el mensaje espera en la cola. Eso es desacoplamiento temporal, y es la razón de
usar mensajería en vez de una llamada HTTP.

Cambia después a **ENTREGADO** y aparece `[BOLETA] generando documento...`:
distinta transición, distinta cola.

---

## P8 · Integración con las DLQ · 8%

> *Provoque un error no recuperable y muestre el log del listener de la DLQ.*

El fallo se provoca publicando un mensaje inválido a mano. Consola →
**Exchanges** → `cmd.direct` → *Publish message*:

- Routing key: `email.send`
- Payload:

```json
{"type":"email.send","eventId":"demo-dlq-001","occurredAt":"2026-10-24T12:00:00Z",
 "traceId":"tr-demo","correlationId":"co-demo","actor":"profesor@duoc.cl",
 "aggregateId":"PED-DEMO-001","payload":{"orderId":"PED-DEMO-001"}}
```

El `payload` **no trae `to`**. Sin destinatario, el correo es irreparable.

En los logs de `notify` salen dos líneas seguidas:

```
ERROR AckPolicy          : Mensaje no procesable en q.cmd.email: comando
                           email.send sin destinatario. Se envia a la DLQ.
ERROR DeadLetterListener : [DLQ q.cmd.email.dlq] mensaje no procesable ·
                           type=email.send eventId=demo-dlq-001 traceId=tr-demo
                           pedido=PED-DEMO-001 actor=profesor@duoc.cl
```

**Explica las tres decisiones de diseño**, que es lo que evalúan:

1. **NACK sin requeue** (`AckPolicy.deadLetter()` → `basicNack(tag, false, false)`).
   Con `requeue=true` el mensaje volvería a la cola, fallaría igual y entraría
   en un bucle infinito que satura al consumidor. El error no va a arreglarse
   solo: hay que sacarlo de la cola.

2. **La DLQ siempre hace ACK, nunca NACK.** Un mensaje que ya está en la DLQ no
   tiene a dónde seguir cayendo. NACK con `requeue=false` lo descartaría —
   perderías la evidencia — y con `requeue=true` crearía un bucle dentro de la
   propia DLQ.

3. **Se registran `eventId` y `traceId`.** El `eventId` identifica el mensaje
   original; el `traceId` permite cruzarlo con los logs de los otros
   microservicios y reconstruir qué pasó en toda la cadena.

**Diferencia entre fallo permanente y transitorio**: `AckPolicy` tiene dos
caminos. `PermanentFailureException` → DLQ. Cualquier otra excepción →
`retry()`, que hace NACK **con** requeue para volver a intentarlo. Un timeout
de red se reintenta; un correo sin destinatario no.

También puedes mostrar el rastro en la aplicación: `GET /api/notify/outbox`
registra lo que cayó en la DLQ.

---

## P9 · Microservicio administrador · 12%

> *Cree una cola, un exchange y un binding, y compruebe el resultado.*

Lo haces desde la **pantalla `/admin-colas`** del frontend, con sesión de Admin.
Es el segundo indicador que más pesa.

**Antes**: consola de RabbitMQ en *Queues* y *Exchanges*, para el "antes y
después".

| Operación | Endpoint | En la pantalla |
|---|---|---|
| Crear cola | `POST /api/rabbitadmin/queues` | formulario *Crear cola* |
| Crear exchange | `POST /api/rabbitadmin/exchanges` | formulario *Crear exchange* |
| Crear binding | `POST /api/rabbitadmin/bindings` | formulario *Crear binding* |
| Purgar | `POST /api/rabbitadmin/queues/{n}/purge` | botón *Purgar* |
| Eliminar | `DELETE /api/rabbitadmin/queues/{n}` | botón *Eliminar* |

Crea `tmp.demo-profesor`, un exchange `demo.direct` y bíndealos. Refresca la
consola: aparecen los tres.

**Tres cosas que preguntan aquí:**

**¿Cómo se protege?** `@PreAuthorize(Roles.HAS_ADMIN)` en el controlador, y
además el JWT Authorizer del gateway antes. Son dos capas: el gateway rechaza
tokens inválidos, el microservicio rechaza tokens válidos sin el rol. Pruébalo
cerrando sesión y entrando como Operador: la opción *Colas* ni aparece en el
menú, y si llamas al endpoint directo da 403.

**¿Por qué hay un registro en memoria?** AMQP **no permite enumerar colas** —
solo preguntar por una concreta. El servicio mantiene un `Set` con las que ha
declarado para poder listarlas. Si el profe pregunta por qué no aparecen las
colas que él creó desde la consola web, esa es la razón, y es una limitación del
protocolo, no un bug.

**¿Qué pasa si intentas borrar una cola del sistema?** Hay una lista de topología
protegida: las seis colas y los tres exchanges del Caso 0 no se pueden eliminar
por API. Intenta borrar `q.cmd.email` y responde **409**. Un microservicio
administrador que puede destruir la aplicación en producción no es una
herramienta, es un accidente esperando.

---

## P10 · Métricas de monitoreo · 8%

> *Explique qué significan Ready, Unacked y Total.*

Consola → **Queues**, columna *Messages*.

| Métrica | Qué es |
|---|---|
| **Ready** | listos para entregar, nadie los ha tomado todavía |
| **Unacked** | entregados a un consumidor que **aún no confirmó** con ACK |
| **Total** | `Ready + Unacked` |

**Qué significa cada patrón** — esto es lo que distingue una respuesta memorizada
de una entendida:

- **Ready alto y subiendo**: llegan más mensajes de los que se procesan. Faltan
  consumidores o están lentos.
- **Unacked alto y estancado**: un consumidor tomó mensajes y no confirma. Está
  colgado o procesando algo muy lento. Esos mensajes están **bloqueados**: si el
  consumidor muere sin hacer ACK, el broker los reentrega a otro.
- **Los dos en 0 con consumidores conectados**: lo normal. Es el estado de
  nuestras colas en reposo.

**Para generar actividad en vivo**: cambia el estado de un pedido desde Angular
y mira el pico en *Message rates*.

**Si pregunta por el prefetch**: `prefetch: 1` en `application.yml` de notify
significa que cada consumidor toma **un** mensaje y no recibe el siguiente hasta
confirmarlo. Por eso Unacked nunca pasa de 1 por consumidor. Con prefetch alto
un consumidor acapara mensajes que otro libre podría estar procesando.

**Dónde más mirar**: *Overview* → *Nodes* (memoria, descriptores, uptime de cada
nodo) y *Overview* → *Message rates* (publish vs deliver del clúster completo).

---

## P11 · Políticas de retención y limpieza · 10%

> *¿Qué límites y expiración aplicó y dónde los definió?*

Consola → **Admin** → **Policies**. Tres políticas:

| Política | Patrón | Definición |
|---|---|---|
| `pedidos360-colas-negocio` | `^q\.cmd\.(email\|kitchen\|invoice)$` | `max-length` 10000 · `message-ttl` 1 h · `overflow` reject-publish-dlx · `ha-mode` all |
| `pedidos360-colas-dlq` | `^q\.cmd\..*\.dlq$` | `max-length` 5000 · `message-ttl` 7 días · `ha-mode` all |
| `pedidos360-colas-temporales` | `^tmp\.` | `expires` 30 min · `ha-mode` all |

**Dónde se definen**: `infra/mq/definitions.json`, que `rabbitmq.conf` carga en
cada arranque con `load_definitions`. Versionado en el repositorio, no aplicado
a mano con `rabbitmqctl`.

**Por qué política y no argumento de cola** — la pregunta fuerte de este
indicador. Los argumentos (`x-max-length`) se fijan **al declarar** la cola y son
inmutables: para cambiar un TTL hay que **borrar y recrear** la cola, perdiendo
los mensajes. Las políticas se aplican por patrón, se cambian en caliente y
alcanzan a colas que todavía no existen. Por eso la cola `tmp.demo` que creas en
P9 ya nace con su política aplicada.

**Los tres valores, y por qué cada uno:**

- **`message-ttl` 1 hora** en las de negocio: una notificación de hace más de una
  hora ya no le sirve a nadie. Al expirar **no se pierde**: va a la DLQ, porque
  la cola tiene DLX configurado.
- **`max-length` 10 000** con **`overflow: reject-publish-dlx`**: si la cola se
  desborda, el mensaje nuevo se rechaza y va a la DLQ. El comportamiento por
  defecto es `drop-head`, que **descarta silenciosamente el más antiguo** — y un
  mensaje perdido sin rastro es lo peor que puede pasar en un sistema de
  mensajería.
- **`expires` 30 min** solo en `tmp.`: borra la cola entera si pasa media hora
  sin uso. Aplicado únicamente a las temporales del microservicio administrador,
  para que las pruebas no dejen basura. Nunca a las de negocio: una cola de
  producción sin tráfico un domingo no debe desaparecer.

**Las DLQ retienen 7 días**, mucho más que la hora de las principales. Son para
diagnosticar: si se vacían en una hora pierdes la evidencia justo cuando vas a
investigar.

---

## Preguntas que no están en la planilla pero caen

**¿Por qué RabbitMQ y Kafka a la vez?**
Rabbit reparte trabajo, Kafka guarda historia. Un **comando** (`email.send`)
tiene un destinatario, se procesa una vez y **se borra** al confirmar. Un
**evento** (`orders.events`) es un hecho consumado, lo lee quien quiera y **no se
borra**. Por eso `audit.timeline` usa `cleanup.policy=compact`: conserva el
último estado de cada pedido indefinidamente. En `OrderService` se ve en líneas
pegadas: `publishEvent()` a Kafka, `publishCommand()` a Rabbit.

**¿Qué pasa si un nodo se cae?**
Las colas están replicadas (`ha-mode: all`): cada una tiene master en un nodo y
espejo en el otro. Si cae el master, el espejo se promueve. Los microservicios
conocen los dos nodos vía `spring.rabbitmq.addresses` y reconectan solos.

**Demuéstralo en vivo** — está medido, tarda menos de un minuto entero:

```bash
# Terminal con SSH a ec2-mq
docker stop pedidos360-rabbit1
docker exec pedidos360-rabbit2 rabbitmqctl cluster_status   # Running: solo rabbit2
docker exec pedidos360-rabbit2 rabbitmqctl list_queues name consumers
```

A los **~16 s** los 6 consumidores aparecen reconectados sobre `rabbit2`. Con el
nodo todavía caído, publica un mensaje desde la consola de `rabbit2`
(http://34.229.10.208:15673) y en los logs de `notify` sale
`[COCINA] ticket para el pedido ...`: **la aplicación sigue procesando con la
mitad del clúster muerto**. Eso es lo que hay que hacer notar, no que el clúster
sobreviva en abstracto.

```bash
docker start pedidos360-rabbit1    # ~15 s en reincorporarse
```

Fíjate en que `rabbit1` **nunca dejó de aparecer en Disc Nodes**: por eso al
volver se reincorpora solo, sin ningún `join_cluster`. Y `Network Partitions`
sigue en `(none)`.

Si pregunta por qué tarda 16 s y no es instantáneo: el cliente tiene que
detectar la conexión caída, agotar el intento contra el nodo muerto y
reconectar. Por eso `connection-timeout: 5s` en el `application.yml` — con el
valor por defecto de 60 s el failover tardaba más de un minuto.

**¿Cómo evitas procesar dos veces el mismo mensaje?**
Idempotencia por `eventId`. Cada listener llama primero a
`outbox.markProcessed(envelope.eventId())`; si ya estaba, hace ACK y sale sin
reprocesar. Hace falta porque RabbitMQ garantiza **at-least-once**: si un
consumidor procesa y muere antes del ACK, el broker reentrega.

**¿Por qué ACK manual y no automático?**
Con `acknowledge-mode: auto` el mensaje se confirma **al entregarlo**, antes de
procesarlo. Si el consumidor falla, el mensaje ya se borró y se perdió. Con
manual el ACK lo controla el código, después de que el trabajo esté hecho.
Está en `application.yml` de notify y en los `ackMode = "MANUAL"` de los
`@RabbitListener`.
