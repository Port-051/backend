package com.port051.queuemate.matching.sse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Pub/Sub 구독을 띄운다.
 *
 * <p>스프링이 자동으로 만들어 주지 않는다. 구독은 연결 하나를 계속 붙잡고 있는 일이라
 * 쓰지 않는 애플리케이션에까지 만들어 줄 수 없기 때문이다.
 */
@Configuration
public class EventChannelConfig {

    @Bean
    public RedisMessageListenerContainer matchingEventContainer(RedisConnectionFactory connectionFactory,
                                                                EventSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(EventPublisher.CHANNEL));
        return container;
    }
}
