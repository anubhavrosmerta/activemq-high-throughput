# activemq-high-throughput
activemq with high throughput for testing

![executionprocess](./activemq%20execution%20report%20in%20batches.png)

![performance](./activemq%20highthroughput%20.png)
here recheck pending 


*** it’s **high-throughput + no-loss + scalable**:

---

### ✅ **Producer side (GpsPublisher + JmsTemplate)**

* `jmsTemplate.setDeliveryPersistent(true)` → ensures **no data loss** (messages written to broker store).
* `connectionFactory.setUseAsyncSend(true)` + `setAlwaysSyncSend(false)` → allows **asynchronous sends**, much faster than sync.
* `setCopyMessageOnSend(false)` → reduces GC overhead.
* Bulk publishing loop is fine — you can even parallelize with `ExecutorService` if you need *10k+/sec* from one JVM.

✔️ Producer is optimized for speed + persistence.

---

### ✅ **Consumer side (GpsSubscriber + Listener factory)**

* `DefaultJmsListenerContainerFactory` with `concurrency="50-100"` → allows **50–100 parallel consumers per JVM instance**.
* Using **Queue** mode (`factory.setPubSubDomain(false)`) → enables **real load balancing** across consumers.
* Logging: replaced `System.out` with `Slf4j async logging` → avoids console bottlenecks.
* Rate calculation every 5s → smart, avoids per-message log spam.

✔️ Consumer is optimized for parallel consumption + logging efficiency.

---

### ✅ **Broker configuration**

* ActiveMQ prefetch set: `factory.getPrefetchPolicy().setQueuePrefetch(1000)` → helps throughput.
* `producerWindowSize=1024000` → batching for producers.
* Persistent delivery mode ensures **no data loss**, but bottleneck will be **disk I/O**.

👉 For very high throughput (millions/sec), consider:

* Put ActiveMQ on **SSD disks**.
* Tune `systemUsage` in `activemq.xml` (`memoryLimit`, `storeUsage`).
* If you go beyond \~50k/sec, consider **ActiveMQ Artemis** (faster engine) or **Kafka**.

---

### ✅ **Logging**

* `logback-spring.xml` with **async file appender** → ✅ correct.
* `queueSize=10000`, `discardingThreshold=0` → ensures **no log drops**.
* Rolling policy keeps 7 days → production-friendly.

✔️ Logging won’t block your app now.

---

### 🚀 Final verdict

* **Producer** → fast, async, persistent.
* **Consumer** → concurrent, load-balanced, async logging.
* **Broker** → properly tuned for high throughput.
* **Logging** → no bottleneck.

👉 Yes, this setup can handle **huge bulk per second** (10k+/sec depending on hardware & disk).
👉 **No data loss** (thanks to persistent delivery).
👉 **Fast throughput** (async send + batching + concurrent consumers).

---


---
---

Q) DefaultJmsListenerContainerFactory with concurrency="50-100" → allows 50–100 parallel consumers per JVM instance if i pass 500-1000 then also it perform well or not with no data loss?

---

### ⚡ `concurrency="500-1000"` — what happens?

When you configure:

```java
factory.setConcurrency("500-1000");
```

you are telling Spring JMS to spin up **up to 1000 consumer threads in *this JVM*** for that queue.

* ✅ **No data loss** → JMS guarantees delivery (messages will remain in broker store until acknowledged).
* ❌ **But performance may *degrade*** beyond a point:

  * **Thread contention** → 500–1000 threads in a single JVM = massive context switching, GC overhead.
  * **Broker load** → ActiveMQ must manage 1000 active consumers → increases network chatter (ACKs, prefetch distribution).
  * **Memory pressure** → each consumer prefetches messages (you set prefetch=1000).

    * With 1000 consumers × 1000 prefetch = **1M messages sitting in consumers’ memory**, possibly stuck.

---

### ⚠️ Why 50–100 is usually the sweet spot

* 50–100 consumers per JVM → enough parallelism to saturate CPU and network.
* Going to 500+ consumers in a single JVM **does not scale linearly** — instead it creates bottlenecks in threads, broker dispatch, and GC.

---

### 🚀 How to really scale beyond that

If you need **>1000 parallel consumers** safely:

1. **Multiple JVM instances (microservices)**:

   * Run 10 JVMs, each with `concurrency=100`.
   * ActiveMQ Queue load balances across them automatically.
   * Much cleaner than `concurrency=1000` in one JVM.

2. **Horizontal scale consumers** → deploy across multiple servers (e.g., Kubernetes, Docker).

3. **Broker-side tuning**:

   * Adjust `systemUsage` (memory/disk limits).
   * Increase `transportConnector` thread pools.
   * Tune `prefetchPolicy` (e.g., `50` instead of `1000`) to avoid one consumer hoarding messages.

---

### ✅ Answer

* If you set `500-1000`, **it will not lose data** (JMS persistence guarantees).
* But **performance will probably get worse**, not better.
* Best practice:

  * Keep `50–100` concurrency per JVM.
  * Scale horizontally (more instances) instead of stuffing thousands of threads into one process.

---

Q) if jvm run 10 then how much it can handle?

Good one 👍 — let’s calculate realistically how much **10 JVMs** can handle.

---

### 🔹 Current setup recap

* You configured:

  * `concurrency="50-100"` → \~100 consumers per JVM.
  * `prefetch=1000` → each consumer can hold up to 1000 messages in memory.
  * Optimizations (async send, optimize acknowledge, batching, async logging) → reduce overhead.

---

### 🔹 Throughput math

#### 1. One consumer speed

* A **single consumer** can typically process **2k–10k msgs/sec** depending on:

  * message size (\~1KB is easy, \~256KB will be slower).
  * processing logic (just counting/logging = fast, DB writes = slower).
  * hardware (CPU/memory/network).

Let’s assume **5k msgs/sec per consumer** as a balanced estimate.

#### 2. One JVM with \~100 consumers

```
100 consumers × 5k msgs/sec = ~500k msgs/sec (theoretical max)
```

⚠️ In practice, due to thread contention, context switches, and broker round-robin limits, you’ll get more like **100k–200k msgs/sec per JVM** stable.

#### 3. 10 JVMs (horizontally scaled)

```
10 JVMs × 100k–200k msgs/sec = ~1M–2M msgs/sec
```

So with **10 JVMs, each with \~100 consumers**, your system could stably consume **over 1 million messages per second** (assuming message size is small, broker is tuned, and network isn’t the bottleneck).

---

### 🔹 Bottlenecks to watch

1. **Broker (ActiveMQ Classic)**

   * Default broker isn’t designed for sustained 1M/sec.
   * You must increase `systemUsage` limits, persistence adapter (KahaDB) tuning, and possibly cluster brokers.

2. **Message size**

   * 1KB message → fine at millions/sec.
   * 100KB message → throughput drops drastically (network & disk I/O bottleneck).

3. **Consumer processing**

   * If consumers write to DB → DB becomes the bottleneck.
   * For raw in-memory consumption → you can push millions/sec.

---

### ✅ Answer

If you run **10 JVMs with \~100 consumers each**, you can realistically consume **\~1M messages/sec** (with small messages, lightweight processing, and a tuned broker).

* Data will not be lost (persistent queue + auto-ack).
* Scale further by adding **more JVMs or brokers**.

---
