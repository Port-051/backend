package com.port051.queuemate.matching.config;

import com.port051.queuemate.matching.sse.MatchingEventListener;
import com.port051.queuemate.matching.redis.Keys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scripting.support.ResourceScriptSource;

@Configuration
public class RedisConfig {

    // claim·release·confirm 스크립트는 비교 축이다. 각자 브랜치에서 정의한다.



    @Bean
    RedisScript<Long> cancelScript() {
        return script("lua/cancel.lua");
    }


    private RedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }

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
