package com.port051.queuemate.matching.config;

import com.port051.queuemate.matching.domain.GameQueue;
import com.port051.queuemate.matching.domain.TierPolicy;
import org.springframework.stereotype.Component;

/** 01 3.5의 허용 범위를 설정 데이터에서 읽는 구현. */
@Component
public class ConfiguredTierPolicy implements TierPolicy {

    private final MatchingProperties.TierRule rule;

    public ConfiguredTierPolicy(MatchingProperties properties) {
        this.rule = properties.tierRule();
    }

    @Override
    public boolean playableTogether(GameQueue queue, Integer aTierOrder, Integer bTierOrder) {
        if (!queue.gameTierRuleApplies()) {
            return true;   // 자유 랭크와 일반은 이 제약을 적용하지 않는다
        }
        if (aTierOrder == null || bTierOrder == null) {
            return false;  // 01 3.7 — 티어가 없으면 허용 범위를 계산할 기준이 없다
        }
        int spread = Math.min(allowedSpread(queue, aTierOrder), allowedSpread(queue, bTierOrder));
        return Math.abs(aTierOrder - bTierOrder) <= spread;
    }

    @Override
    public int allowedSpread(GameQueue queue, Integer tierOrder) {
        if (!queue.gameTierRuleApplies()) {
            return Integer.MAX_VALUE;
        }
        if (tierOrder == null) {
            return 0;
        }
        for (MatchingProperties.TierRule.Band band : rule.soloDuoBands()) {
            if (tierOrder <= band.upToOrder()) {
                return band.spread();
            }
        }
        return 0;
    }

    @Override
    public boolean eligibleForSoloDuo(Integer tierOrder) {
        return tierOrder != null && tierOrder < rule.soloDuoBlockedFromOrder();
    }
}
