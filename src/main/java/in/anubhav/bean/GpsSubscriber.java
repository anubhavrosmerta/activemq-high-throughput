package in.anubhav.bean;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Component
public class GpsSubscriber {

	private static final Logger log = LoggerFactory.getLogger(GpsSubscriber.class);

	private final AtomicLong counter = new AtomicLong();
	private volatile long lastReportTime = System.currentTimeMillis();

	// for queue when from publisher uses
	@JmsListener(destination = "gps.telemetry.queue", containerFactory = "jmsListenerContainerFactory")
	public void receiveGps(String message) {
		long current = counter.incrementAndGet();
		long now = System.currentTimeMillis();

		if (now - lastReportTime >= 5000) { // every 5 sec
			double rate = (current * 1000.0) / (now - lastReportTime);
			log.info("📥 Consumed {} messages ({} msgs/sec)", current, String.format("%.2f", rate));

			counter.set(0);
			lastReportTime = now;
		}
	}

	@JmsListener(destination = "gps.telemetry.topic", containerFactory = "jmsListenerContainerFactory", subscription = "GpsDurableSub")
	public void receiveGpsFromTopic(String message) {

		long current = counter.incrementAndGet();
		long now = System.currentTimeMillis();

		if (now - lastReportTime >= 5000) { // every 5 sec
			double rate = (current * 1000.0) / (now - lastReportTime);
			log.info("📥 Consumed {} messages ({} msgs/sec)", current, String.format("%.2f", rate));

			counter.set(0);
			lastReportTime = now;
		}
	}

}
