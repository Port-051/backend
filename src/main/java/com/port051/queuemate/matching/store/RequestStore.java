package com.port051.queuemate.matching.store;

import com.port051.queuemate.matching.domain.MatchRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * 요청 메모 {@code req:{requestId}}. 05-realtime-matching-contract 1장.
 *
 * <p>값은 계약 2장이 고정한 JSON 문자열이다. 부하 스크립트가 이 모양을 직접 읽고 쓰므로
 * 자바 전용 표현으로 저장할 수 없다.
 *
 * <p><b>{@code RedisTemplate}을 쓰지 않는 이유가 있다.</b> 자동 설정된 {@code RedisTemplate}의
 * 기본 변환기는 JDK 직렬화라 {@code Serializable}이 아닌 record를 아예 저장하지 못하고,
 * {@code Serializable}을 붙여도 나오는 것은 JSON이 아니라 자바 클래스 이름이 박힌 바이너리다.
 * Jackson 변환기로 갈아끼우는 방법도 있지만 그쪽은 타입 정보를 JSON 안에 심는 경우가 있어
 * 계약이 정한 필드 열셋만 나온다는 보장이 없다. 그래서 문자열로 직접 다룬다.
 *
 * <p>{@link JsonMapper}를 주입받지 않고 직접 만드는 것도 같은 이유다. 이 JSON의 모양은
 * 계약이지 애플리케이션 설정이 아니므로, 앱 전역 Jackson 설정이 바뀌어도 흔들리면 안 된다.
 */
@Component
public class RequestStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;

    public RequestStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 요청을 저장한다. 같은 요청 ID가 이미 있으면 덮어쓴다. */
    public void save(MatchRequest request) {
        redis.opsForValue().set(key(request.requestId()), JSON.writeValueAsString(request));
    }

    /** 요청을 읽는다. 취소·만료·확정으로 이미 지워졌으면 빈 값이다. */
    public Optional<MatchRequest> find(long requestId) {
        String json = redis.opsForValue().get(key(requestId));
        return json == null ? Optional.empty() : Optional.of(JSON.readValue(json, MatchRequest.class));
    }

    /** 요청을 지운다. 없던 것을 지워도 문제되지 않는다. */
    public void delete(long requestId) {
        redis.delete(key(requestId));
    }

    private static String key(long requestId) {
        return "req:" + requestId;
    }
}
