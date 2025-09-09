package in.anubhav.service;

import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GpsPublisher {

    private final JmsTemplate jmsTemplate;
    private final ExecutorService executor = Executors.newFixedThreadPool(5); // 5 parallel threads

    public GpsPublisher(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
        this.jmsTemplate.setDeliveryPersistent(true); // messages are persisted
    }

    // Publish a single GPS message
    public void publishGps(String gpsPayload) {
        jmsTemplate.convertAndSend("gps.telemetry.queue", gpsPayload);
    }

    // Bulk publish messages safely using batching and async threads
    public void sendBulkMessages(String destination, int count) {
        int batchSize = 5000; // 5k messages per batch
        int totalBatches = (int) Math.ceil(count / (double) batchSize);

        for (int b = 0; b < totalBatches; b++) {
            int start = b * batchSize;
            int end = Math.min(start + batchSize, count);

            final int s = start;
            final int e = end;

            executor.submit(() -> {
                for (int i = s; i < e; i++) {
                    jmsTemplate.convertAndSend(destination, "Message-" + i);
                }
                System.out.println("✅ Batch " + (s / batchSize + 1) + " sent: " + (e - s) + " messages");
            });
        }
    }
}
