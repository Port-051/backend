package com.port051.queuemate.matching.domain;

import java.util.Map;

/**
 * 01 4.4 — 요청별 배정 포지션. {@code primaryCount}는 주 포지션을 받은 인원 수이며
 * 클수록 좋은 배정으로 보되, 통과·탈락 기준은 배정 가능 여부 자체다.
 */
public record PositionAssignment(Map<Long, Position> byRequestId, int primaryCount) {
}
