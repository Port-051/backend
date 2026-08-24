rootProject.name = "queuemate-backend"

// 04-tech-stack.md 7장 — 실행기는 다섯이지만 처음부터 다섯으로 쪼개지 않는다.
// 스파이크(#35) 범위는 실시간 매칭 서비스 하나다.
include("realtime-matching")
