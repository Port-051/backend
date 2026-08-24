package com.port051.queuemate.matching.config;

import com.port051.queuemate.matching.redis.Keys;
import com.port051.queuemate.matching.sse.MatchingEventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    // Lua 스크립트는 전부 비교 축이다 — 선점(claim·release), 확정(confirm), 취소(cancel).
    // 넷이 같은 claim: 키를 놓고 경쟁하므로 하나만 골격에 두면 나머지 셋의 설계가 제약된다.
    // 각자 브랜치에서 필요한 스크립트를 정의하고 빈으로 올린다.

    /**
     * 02 3.2 — 각 인스턴스는 채널을 구독하고 <b>자신에게 붙은 연결에만</b> 전달한다.
     * SSE 연결은 한 인스턴스에 고정되지만 제안은 전원에게 같은 시점에 닿아야 하므로
     * 전파는 Pub/Sub을 지난다.
     */
    @Bean
    RedisMessageListenerContainer listenerContainer(RedisConnectionFactory connectionFactory,
                                                    MatchingEventListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(Keys.EVENT_CHANNEL));
        return container;
    }
}
