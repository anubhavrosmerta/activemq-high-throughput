package in.anubhav.config;

/*
import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;

@Configuration
public class JmsConfig {
	
    // 1️⃣ ConnectionFactory bean
    @Bean
    public ActiveMQConnectionFactory connectionFactory() {
        ActiveMQConnectionFactory factory =
                new ActiveMQConnectionFactory("tcp://localhost:61616");
        factory.setUserName("admin");
        factory.setPassword("admin");
        factory.setUseAsyncSend(true);
        factory.setAlwaysSyncSend(false);       // avoid forced sync
        factory.setCopyMessageOnSend(false);    // less GC overhead
        factory.setOptimizeAcknowledge(true);   // batch acks
        factory.setWatchTopicAdvisories(false); // reduces overhead
        factory.getPrefetchPolicy().setQueuePrefetch(1000);
        factory.setProducerWindowSize(1024000); //  1 MB buffer  // batching
        return factory;
    }

    // 2️⃣ JmsTemplate bean for publishing
    @Bean
    public JmsTemplate jmsTemplate(ActiveMQConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(true); // use TOPIC
      //  template.setPubSubDomain(false); // ✅ Use QUEUE instead of TOPIC

        return template;
    }
    
    
	@Bean
	public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(ConnectionFactory connectionFactory) {
		DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		//factory.setPubSubDomain(true); // Enable TOPIC mode
	    factory.setPubSubDomain(false); // ✅ Queue mode for load balancing
		factory.setConcurrency("50-100"); // Parallel consumers
		factory.setSessionTransacted(false);
	    factory.setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE);
	       
		return factory;
	}
}
*/

import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.Session;

@Configuration
public class JmsConfig {
	
    // 1️⃣ ActiveMQConnectionFactory bean
    @Bean
    public ActiveMQConnectionFactory connectionFactory() {
        ActiveMQConnectionFactory factory =
                new ActiveMQConnectionFactory("tcp://localhost:61616");
        factory.setUserName("admin");
        factory.setPassword("admin");
        factory.setUseAsyncSend(true);
        factory.setAlwaysSyncSend(false);       // avoid forced sync
        factory.setCopyMessageOnSend(false);    // less GC overhead
        factory.setOptimizeAcknowledge(true);   // batch acks
        factory.setWatchTopicAdvisories(false); // reduces overhead
        factory.getPrefetchPolicy().setQueuePrefetch(1000);
        factory.setProducerWindowSize(1024000); //  1 MB buffer  // batching
        return factory;
    }

    // 2️⃣ CachingConnectionFactory bean
    @Bean
    public CachingConnectionFactory cachingConnectionFactory(ActiveMQConnectionFactory connectionFactory) {
        CachingConnectionFactory cachingFactory = new CachingConnectionFactory(connectionFactory);
        cachingFactory.setSessionCacheSize(100); // reuse sessions
        return cachingFactory;
    }

    // 3️⃣ JmsTemplate bean for publishing
    @Bean
    public JmsTemplate jmsTemplate(CachingConnectionFactory cachingConnectionFactory) {
        JmsTemplate template = new JmsTemplate(cachingConnectionFactory);
        template.setPubSubDomain(true); // use TOPIC
      //  template.setPubSubDomain(false); // ✅ Use QUEUE instead of TOPIC
       // template.setDeliveryPersistent(true); // ✅ ensure messages are stored until delivered
        
        return template;
    }

    // 4️⃣ Listener container factory for subscribing
	@Bean
	public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(CachingConnectionFactory cachingConnectionFactory) {
		DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
		factory.setConnectionFactory(cachingConnectionFactory);
		factory.setPubSubDomain(true); // Enable TOPIC mode
	   // factory.setPubSubDomain(false); // ✅ Queue mode for load balancing
		factory.setConcurrency("50-100"); // Parallel consumers
		factory.setSessionTransacted(false);
	    factory.setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE);
		return factory;
	}
}