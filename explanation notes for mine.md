Great—this is all wired up and running. Here’s what you’re seeing, why you used each piece, and a couple of fixes/tweaks so queue vs topic behavior is crystal-clear.

# What your code does (component by component)

## REST layer

```java
@PostMapping("/publish")
public String publishGps(@RequestBody String gpsPayload) {
    gpsPublisher.publishGps(gpsPayload);
    return "GPS data published: " + gpsPayload;
}
```

* Accepts the HTTP body **as a raw String** and hands it to the JMS publisher.
* Because it’s a raw String, if you POST JSON like `{"gpsPayload":"hi"}`, the **entire JSON** is sent to JMS (not just `"hi"`). That’s fine if intentional; otherwise define a DTO.

## Producer (publisher)

```java
@Service
public class GpsPublisher {
    private final JmsTemplate jmsTemplate;

    public void publishGps(String gpsPayload) {
        jmsTemplate.convertAndSend("gps.telemetry.queue", gpsPayload);
    }
}
```

* Uses **JmsTemplate** to send messages.
* You also called `this.jmsTemplate.setDeliveryPersistent(true)` (good): messages are stored on disk and survive broker restarts.

## Connection & performance tuning

```java
@Bean
public ActiveMQConnectionFactory connectionFactory() {
    ActiveMQConnectionFactory f = new ActiveMQConnectionFactory("tcp://localhost:61616");
    f.setUserName("admin"); f.setPassword("admin");
    f.setUseAsyncSend(true);       // producer doesn’t block each send
    f.setAlwaysSyncSend(false);    // allow batching
    f.setCopyMessageOnSend(false); // less GC
    f.setOptimizeAcknowledge(true);// consumer acks in batches
    f.getPrefetchPolicy().setQueuePrefetch(1000); // consumer prefetch
    f.setProducerWindowSize(1024_000);            // async send buffer
    return f;
}

@Bean
public CachingConnectionFactory cachingConnectionFactory(...) {
    var c = new CachingConnectionFactory(connectionFactory);
    c.setSessionCacheSize(100); // re-use sessions/producers
    return c;
}
```

* **ActiveMQConnectionFactory** connects to the broker and includes sensible throughput optimizations.
* **CachingConnectionFactory** keeps sessions/producers alive to avoid re-creating them on every send/receive.

## Listener (consumer)

```java
@JmsListener(destination = "gps.telemetry.queue", containerFactory = "jmsListenerContainerFactory")
public void receiveGps(String message) { ... }

@JmsListener(
  destination = "gps.telemetry.topic",
  containerFactory = "jmsListenerContainerFactory",
  subscription = "GpsDurableSub")
public void receiveGpsFromTopic(String message) { ... }
```

* You’ve defined **one listener method for a queue** and another **for a topic**.
* Your container factory:

```java
@Bean
public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(...) {
    var f = new DefaultJmsListenerContainerFactory();
    f.setConnectionFactory(cachingConnectionFactory);
    f.setPubSubDomain(true);    // <— THIS MEANS “TOPIC MODE”
    f.setConcurrency("1-3");    // up to 3 concurrent consumers per @JmsListener
    f.setSessionTransacted(false);
    f.setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE);
    return f;
}
```

* **Important:** `setPubSubDomain(true)` puts the **factory in Topic mode**. Any listener using this factory will treat its destination as a **Topic**, even if the name says “\*.queue”.
  If you want true queue semantics, you need a **separate factory with `false`** (see fixes below).

# Interpreting your screenshot (ActiveMQ 5.x console)

Row highlights from your image:

* **gps.telemetry.queue — Number Of Consumers: 3, Enqueued: 2, Dequeued: 3**

  * **3 consumers**: your `@JmsListener` with `concurrency="1-3"` has scaled up to 3 concurrent consumers. That’s why it shows **3**.
  * **Enqueued** is how many messages have been sent to that destination (cumulative since broker start or last reset).
  * **Dequeued** increments when a consumer successfully receives & acknowledges a message (also cumulative). It can be **greater than Enqueued** if there were older messages from before, or because these counters are not strictly “matched pairs” per restart; they’re rolling totals.

* **gps.telemetry.topic — Number Of Consumers: 1, Enqueued: 0, Dequeued: 0**

  * You have **1 consumer** (your topic listener). No messages have been published to this topic yet (your producer is currently sending to the queue name).

* **ActiveMQ.Advisory.* rows*\*

  * Internal advisory topics/queues ActiveMQ uses for events (connections, producers, consumers). You can usually ignore them.

* **Operations links**

  * **Send To Active Subscribers**: quick UI to send a test message.
  * **Active Producers**: see who is currently producing to that destination.
  * **Delete**: remove an empty destination.

### How many producers do you have?

* In your app, there’s **one producer**: the `JmsTemplate` inside `GpsPublisher`.
* The console shows producers only when they’re actively connected/sending (use the **Active Producers** link on that row).

# Queue vs Topic (and what you’re doing)

* **Queue** (`gps.telemetry.queue`): **competing consumers**. Each message goes to **one** of the consumers. With concurrency `1-3`, Spring can create up to 3 consumer threads (hence “3” in the console).
* **Topic** (`gps.telemetry.topic`): **broadcast**. Every subscriber gets a copy. If you want a subscriber to receive messages even while it’s offline, you need a **durable subscription**.

Right now:

* Your **producer** is sending to `"gps.telemetry.queue"` (by name).
* Your **JmsTemplate** is configured with `setPubSubDomain(true)` which makes it send to a **Topic** named `gps.telemetry.queue` (not a Queue). That’s potentially confusing.
* Your **listener factory** is also in **Topic mode**, so your “queue” listener may actually be attached to a Topic of the same name.

That your console shows a **Queue** called `gps.telemetry.queue` with 3 consumers suggests you previously ran the queue-mode config, or you have another factory in play. To avoid ambiguity, split queue vs topic config.

# Recommended clean setup (no ambiguity)

## 1) Two factories: one for queues, one for topics

```java
@Bean
public DefaultJmsListenerContainerFactory queueListenerFactory(CachingConnectionFactory ccf) {
    var f = new DefaultJmsListenerContainerFactory();
    f.setConnectionFactory(ccf);
    f.setPubSubDomain(false);     // QUEUE mode
    f.setConcurrency("1-3");
    f.setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE);
    return f;
}

@Bean
public DefaultJmsListenerContainerFactory topicListenerFactory(CachingConnectionFactory ccf) {
    var f = new DefaultJmsListenerContainerFactory();
    f.setConnectionFactory(ccf);
    f.setPubSubDomain(true);      // TOPIC mode
    f.setSubscriptionDurable(true);
    f.setClientId("gps-app-client"); // required for durable subscriptions
    f.setConcurrency("1-2");
    return f;
}
```

## 2) Two JmsTemplates: one for queues, one for topics

```java
@Bean
public JmsTemplate queueTemplate(CachingConnectionFactory ccf) {
    var t = new JmsTemplate(ccf);
    t.setPubSubDomain(false);          // QUEUE
    t.setDeliveryPersistent(true);
    return t;
}

@Bean
public JmsTemplate topicTemplate(CachingConnectionFactory ccf) {
    var t = new JmsTemplate(ccf);
    t.setPubSubDomain(true);           // TOPIC
    t.setDeliveryPersistent(true);
    return t;
}
```

## 3) Producer with explicit destination types

```java
@Service
public class GpsPublisher {
    private final JmsTemplate queueTemplate;
    private final JmsTemplate topicTemplate;

    public GpsPublisher(JmsTemplate queueTemplate, @Qualifier("topicTemplate") JmsTemplate topicTemplate) {
        this.queueTemplate = queueTemplate;
        this.topicTemplate = topicTemplate;
    }

    public void publishToQueue(String payload) {
        queueTemplate.convertAndSend("gps.telemetry.queue", payload);
    }

    public void publishToTopic(String payload) {
        topicTemplate.convertAndSend("gps.telemetry.topic", payload);
    }
}
```

## 4) Listeners wired to the right factories

```java
@Component
public class GpsSubscriber {

    @JmsListener(destination = "gps.telemetry.queue", containerFactory = "queueListenerFactory")
    public void receiveFromQueue(String msg) { /* rate logging as you have */ }

    @JmsListener(
      destination = "gps.telemetry.topic",
      containerFactory = "topicListenerFactory",
      subscription = "GpsDurableSub")
    public void receiveFromTopic(String msg) { /* rate logging as you have */ }
}
```

## 5) Optional: request model so you don’t send the full JSON

```java
@Data
class GpsRequest { private String gpsPayload; }

@PostMapping("/publish")
public String publish(@RequestBody GpsRequest req,
                      @RequestParam(defaultValue="queue") String dest) {
    if ("topic".equalsIgnoreCase(dest)) {
        gpsPublisher.publishToTopic(req.getGpsPayload());
        return "Published to topic";
    } else {
        gpsPublisher.publishToQueue(req.getGpsPayload());
        return "Published to queue";
    }
}
```

# About “sync to multiple places / subscribers”

* **Queue** = **load-balanced**: many consumers can read from the same queue, but **each message is processed once** (by exactly one consumer). Great for scaling processing.
* **Topic** = **fan-out/broadcast**: **every active subscriber** gets a copy.

  * **Durable subscription** lets a subscriber receive messages sent while it was offline (requires `clientId` + `subscription` name + durable enabled).
  * **Non-durable** subscribers only receive while connected.

# A note on connection pooling

You set:

```
spring.activemq.pool.enabled=true
spring.activemq.pool.max-connections=100
```

For these to take effect you must have the **pooled JMS** library on the classpath (e.g., `org.messaginghub:pooled-jms`). If it’s not present, Boot will quietly fall back to the non-pooled factory (you still have `CachingConnectionFactory`, which helps). If you want true broker connection pooling, add the pooled-jms dependency.

# Quick answers to your questions

* **“How many consumers?”**
  In your screenshot, **gps.telemetry.queue** shows **3 consumers** (because `concurrency="1-3"`). **gps.telemetry.topic** shows **1 consumer**.

* **“How many producers?”**
  Your app has **one** (the `JmsTemplate`). The console’s **Active Producers** link shows any currently connected producers.

* **“What’s being synced where?”**

  * To the **queue**: one copy per message; any one of the up-to-3 consumers will process it.
  * To the **topic**: one copy per **subscriber**; with durable subs, offline subscribers catch up later.

* **“Why use each setting?”**

  * `useAsyncSend + producerWindowSize`: higher throughput, less blocking on send.
  * `optimizeAcknowledge + prefetch=1000`: consumers acknowledge in batches and read ahead—higher throughput.
  * `CachingConnectionFactory`: reuses JMS sessions/producers—less overhead.
  * Separate **queue/topic factories**: removes ambiguity; ensures you really get queue semantics for queues and topic semantics for topics.
  * **Durable subscription** for topics: guarantees delivery even if your app is temporarily down.

If you want, I can tailor the config 100% to **only queues** or **only topics** (durable) and give you a minimal, copy-paste setup.


Perfect 👍 let’s lock this down so you have **two clean setups** you can pick from, depending on whether you want **only Queue** semantics (load-balancing workers) or **only Topic** semantics (broadcast to subscribers).

---

# ✅ Option 1: Queue-only setup (load balancing)

Use this if you want to send GPS data to a **queue** and let multiple consumers share the load.

### Config

```java
@Configuration
public class JmsQueueConfig {

    @Bean
    public ActiveMQConnectionFactory connectionFactory() {
        ActiveMQConnectionFactory f = new ActiveMQConnectionFactory("tcp://localhost:61616");
        f.setUserName("admin");
        f.setPassword("admin");
        return f;
    }

    @Bean
    public CachingConnectionFactory cachingConnectionFactory(ActiveMQConnectionFactory connectionFactory) {
        return new CachingConnectionFactory(connectionFactory);
    }

    // Queue mode
    @Bean
    public JmsTemplate queueTemplate(CachingConnectionFactory ccf) {
        JmsTemplate t = new JmsTemplate(ccf);
        t.setPubSubDomain(false); // QUEUE
        return t;
    }

    @Bean
    public DefaultJmsListenerContainerFactory queueListenerFactory(CachingConnectionFactory ccf) {
        DefaultJmsListenerContainerFactory f = new DefaultJmsListenerContainerFactory();
        f.setConnectionFactory(ccf);
        f.setPubSubDomain(false); // QUEUE
        f.setConcurrency("1-3");  // up to 3 threads
        return f;
    }
}
```

### Producer

```java
@Service
public class GpsPublisher {
    private final JmsTemplate queueTemplate;

    public GpsPublisher(@Qualifier("queueTemplate") JmsTemplate queueTemplate) {
        this.queueTemplate = queueTemplate;
    }

    public void publishToQueue(String payload) {
        queueTemplate.convertAndSend("gps.telemetry.queue", payload);
    }
}
```

### Consumer

```java
@Component
public class GpsSubscriber {
    @JmsListener(destination = "gps.telemetry.queue", containerFactory = "queueListenerFactory")
    public void receiveFromQueue(String msg) {
        System.out.println("📥 Received from Queue: " + msg);
    }
}
```

👉 Behavior: one message is processed by exactly **one** consumer (good for scaling workers).

---

# ✅ Option 2: Topic-only setup (broadcast)

Use this if you want every subscriber to get a copy of each GPS message.

### Config

```java
@Configuration
public class JmsTopicConfig {

    @Bean
    public ActiveMQConnectionFactory connectionFactory() {
        ActiveMQConnectionFactory f = new ActiveMQConnectionFactory("tcp://localhost:61616");
        f.setUserName("admin");
        f.setPassword("admin");
        return f;
    }

    @Bean
    public CachingConnectionFactory cachingConnectionFactory(ActiveMQConnectionFactory connectionFactory) {
        return new CachingConnectionFactory(connectionFactory);
    }

    // Topic mode
    @Bean
    public JmsTemplate topicTemplate(CachingConnectionFactory ccf) {
        JmsTemplate t = new JmsTemplate(ccf);
        t.setPubSubDomain(true); // TOPIC
        return t;
    }

    @Bean
    public DefaultJmsListenerContainerFactory topicListenerFactory(CachingConnectionFactory ccf) {
        DefaultJmsListenerContainerFactory f = new DefaultJmsListenerContainerFactory();
        f.setConnectionFactory(ccf);
        f.setPubSubDomain(true); // TOPIC
        f.setSubscriptionDurable(true);
        f.setClientId("gps-app-client"); // required for durable subs
        return f;
    }
}
```

### Producer

```java
@Service
public class GpsPublisher {
    private final JmsTemplate topicTemplate;

    public GpsPublisher(@Qualifier("topicTemplate") JmsTemplate topicTemplate) {
        this.topicTemplate = topicTemplate;
    }

    public void publishToTopic(String payload) {
        topicTemplate.convertAndSend("gps.telemetry.topic", payload);
    }
}
```

### Consumer

```java
@Component
public class GpsSubscriber {
    @JmsListener(
      destination = "gps.telemetry.topic",
      containerFactory = "topicListenerFactory",
      subscription = "GpsDurableSub")
    public void receiveFromTopic(String msg) {
        System.out.println("📥 Received from Topic: " + msg);
    }
}
```

👉 Behavior: every active subscriber gets a copy. With `subscription = "GpsDurableSub"` + `clientId`, even if the subscriber is down, it receives missed messages when it reconnects.

---

# 🔍 Quick difference in console

* **Queue (`gps.telemetry.queue`)**

  * Shows `Number Of Consumers = N` (your concurrency threads).
  * Each message delivered once.

* **Topic (`gps.telemetry.topic`)**

  * Shows `Number Of Consumers = N` (active subscribers).
  * Each message delivered to **every subscriber**.

---

Do you want me to merge both setups into **one config**, where you can **choose queue vs topic at runtime** (via REST parameter)? That way you don’t need to maintain two separate projects.
