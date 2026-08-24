-- 02 2장 참조 스키마 — 불변식 검출에 필요한 최소 형태.
-- 스파이크 판정이 INV-1~5 검출 질의로 이뤄지므로(02 게이트 요약) 이 스키마는 골격이다.
-- 매칭 실행기가 이 테이블을 읽느냐 마느냐는 비교 축이다. 여기서 정하지 않는다.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE match_request (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    queue             TEXT        NOT NULL,
    target_size       SMALLINT    NOT NULL,
    status            TEXT        NOT NULL,
    requested_at      TIMESTAMPTZ NOT NULL,
    play_minutes      INT         NOT NULL,
    -- 02 2장 — 매칭에 쓰는 티어는 캐시가 아니라 요청에 고정된 값이다.
    -- 캐시를 읽으면 배치 재실행 시 결과가 달라져 결정적 FCFS와 INV-3 검증이 함께 무너진다.
    tier_snapshot     INT,
    tier_snapshot_at  TIMESTAMPTZ,
    purpose           TEXT        NOT NULL,
    voice_mode        TEXT        NOT NULL
);

CREATE INDEX idx_request_waiting ON match_request (queue, target_size, requested_at, id)
    WHERE status = 'WAITING';

CREATE TABLE party (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue        TEXT        NOT NULL,
    target_size  SMALLINT    NOT NULL,
    start_at     TIMESTAMPTZ NOT NULL,
    end_at       TIMESTAMPTZ NOT NULL,
    status       TEXT        NOT NULL,
    purpose      TEXT        NOT NULL,
    voice_party  BOOLEAN     NOT NULL
);

CREATE TABLE party_member (
    party_id     BIGINT      NOT NULL REFERENCES party (id),
    request_id   BIGINT      NOT NULL REFERENCES match_request (id),
    user_id      BIGINT      NOT NULL,
    "position"   TEXT        NOT NULL,
    joined_at    TIMESTAMPTZ NOT NULL,
    left_at      TIMESTAMPTZ,
    leave_reason TEXT,
    PRIMARY KEY (party_id, request_id),
    -- INV-3 — 하나의 요청은 최대 하나의 파티에만 속한다.
    -- 제약이 있으면 배치는 구조적으로 멱등해진다. 락이 아니라 쓰기가 멱등성을 보장한다.
    CONSTRAINT uq_member_request UNIQUE (request_id)
);

-- INV-4 — 한 파티 안에서 포지션은 겹치지 않는다.
-- 02 2장이 요구한 대로 이탈자(left_at IS NOT NULL)는 계수에서 뺀다.
CREATE UNIQUE INDEX uq_member_position
    ON party_member (party_id, "position")
    WHERE left_at IS NULL;

-- INV-2 — 배제 제약은 한 테이블 안에서만 성립하므로 점유 구간을 한 행으로 모은다.
CREATE TABLE user_busy_interval (
    user_id  BIGINT    NOT NULL,
    during   TSTZRANGE NOT NULL,
    party_id BIGINT    NOT NULL REFERENCES party (id),
    EXCLUDE USING gist (user_id WITH =, during WITH &&)
);

CREATE TABLE party_rating (
    party_id BIGINT      NOT NULL REFERENCES party (id),
    rater_id BIGINT      NOT NULL,
    ratee_id BIGINT      NOT NULL,
    verdict  TEXT        NOT NULL,
    rated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_rating UNIQUE (rater_id, ratee_id, party_id)
);

-- 01 7.7 — kind: REUNION | BLOCK. 매칭 실행 중에는 읽기만 하므로 결정성을 깨지 않는다.
CREATE TABLE user_relation (
    user_id    BIGINT      NOT NULL,
    other_id   BIGINT      NOT NULL,
    kind       TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, other_id, kind)
);

-- INV-5 — 알림 중복 발송. dedup_key가 같은 작업은 한 번만 남는다.
CREATE TABLE notification_job (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    party_id  BIGINT      NOT NULL REFERENCES party (id),
    user_id   BIGINT      NOT NULL,
    kind      TEXT        NOT NULL,
    fire_at   TIMESTAMPTZ NOT NULL,
    status    TEXT        NOT NULL,
    dedup_key TEXT        NOT NULL,
    CONSTRAINT uq_notification_dedup UNIQUE (dedup_key)
);
