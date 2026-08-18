#!/usr/bin/env python3
"""마크다운 문서의 상대 링크가 실제로 존재하는지 검사한다.

docs/ 문서들이 서로를 참조하고 README도 이들을 가리킨다. 파일명이 바뀌면
링크가 조용히 깨지고, 아무도 클릭해보기 전까지 모른다.

외부 URL(http/https/mailto)은 검사하지 않는다. 네트워크에 의존하면 CI가
남의 서버 사정으로 실패한다.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = {".git", "node_modules", ".github/scripts"}

# [텍스트](대상) — 이미지 ![...](...)도 같이 잡힌다
LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
EXTERNAL = re.compile(r"^(https?:|mailto:|tel:|//)")


def markdown_files():
    for path in sorted(ROOT.rglob("*.md")):
        rel = path.relative_to(ROOT)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        yield path


def check(path):
    """path 안의 깨진 링크를 (줄번호, 대상) 목록으로 돌려준다."""
    broken = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        for target in LINK.findall(line):
            if EXTERNAL.match(target) or target.startswith("#"):
                continue

            # 앵커(#섹션)는 떼고 파일 경로만 본다
            file_part = target.split("#", 1)[0]
            if not file_part:
                continue

            resolved = (path.parent / file_part).resolve()
            if not resolved.exists():
                broken.append((lineno, target))
    return broken


def main():
    total = 0
    failed = 0

    for path in markdown_files():
        rel = path.relative_to(ROOT)
        broken = check(path)
        total += 1

        for lineno, target in broken:
            failed += 1
            print(f"::error file={rel},line={lineno}::깨진 링크 — {target}")

    print(f"마크다운 {total}개 검사, 깨진 링크 {failed}개")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
