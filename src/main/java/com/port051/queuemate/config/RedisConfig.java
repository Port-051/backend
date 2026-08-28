package com.port051.queuemate.config;

import com.port051.queuemate.contract.RedisKeys;
import com.port051.queuemate.sse.EventFanout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 배선. 스크립트는 기동 시 한 번 로드하고 이후 EVALSHA로 나간다.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /** 후보 전원 선점. 1 = 전원 성공, 0 = 하나라도 잡혀 있어 아무것도 잡지 않음. */
    @Bean
    public RedisScript<Long> claimAllScript() {
        return script("lua/claim_all.lua", Long.class);
    }

    /** 내가 건 선점만 뗀다. 반환값은 실제로 뗀 개수. */
    @Bean
    public RedisScript<Long> releaseClaimsScript() {
        return script("lua/release_claims.lua", Long.class);
    }

    /** 파티 확정. INV-3 · INV-4를 이 안에서 막는다. */
    @Bean
    public RedisScript<Long> confirmPartyScript() {
        return script("lua/confirm_party.lua", Long.class);
    }

    /** 수락·거절 반영. 멱등성과 전원 수락 판정이 이 안에서 한 번에 일어난다. */
    @Bean
    public RedisScript<String> respondOfferScript() {
        return script("lua/respond_offer.lua", String.class);
    }

    /** 제한시간 초과로 제안을 닫는다. 1 = 이 호출이 닫았다. */
    @Bean
    public RedisScript<Long> expireOfferScript() {
        return script("lua/expire_offer.lua", Long.class);
    }

    /** 요청 취소. 제안이 선점했으면 {@code OFFERED:{offerId}} 문자열을 돌려준다. */
    @Bean
    public RedisScript<String> cancelRequestScript() {
        return script("lua/cancel_request.lua", String.class);
    }

    private <T> RedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource(path));
        redisScript.setResultType(resultType);
        return redisScript;
    }

    /**
     * 인스턴스 간 제안·확정 전파. 02 3.2 —
     * SSE 연결은 한 인스턴스에 고정되는데 참가자는 두 인스턴스에 나뉘어 붙어 있으므로,
     * 발행은 Pub/Sub으로 하고 각 인스턴스가 자기에게 붙은 연결에만 내보낸다.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory, EventFanout fanout) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(fanout, new ChannelTopic(RedisKeys.EVENT_CHANNEL));
        return container;
    }
}
