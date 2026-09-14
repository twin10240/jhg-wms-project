# JHG-WMS 에이전트 가이드

Claude Code·Codex 공용(`CLAUDE.md`는 이 파일을 불러온다). 사람용 설명은 `README.md`.
여기엔 **에이전트가 매번 다시 알아내거나, 실제로 틀렸던 것**만 적는다.
에이전트가 틀리면 [`docs/agent-failure-log.md`](docs/agent-failure-log.md)에 한 줄 적고 막을 곳을 정한다. 같은 실수가 또 나오면 그 줄에 재발을 표시하고 한 단계 강한 장치로 올린다.

## 이 저장소

- 재고 정본을 가진 WMS. 주문(OMS)은 `../jhg-commerce-project`, 시스템 테스트는 `../jhg-system-tests` — 별도 저장소·별도 DB.
- WMS는 Claude Code, OMS·realtime·system-tests는 Codex 담당이다. 옆 저장소는 커밋하지 않고 README "통신 채널"의 계약으로만 맞춘다.
  필요한 변경은 요청 문서로 넘긴다 — 공개하면 안 되는 요청은 git 밖 `~/study/docs/codex-requests/`.
- **이 저장소를 바꾸는 작업은 이 디렉터리에서 연다.** 상위 폴더(`~/study`, git 아님)에서 연 세션은 이 파일을 WMS 파일을 읽을 때에야 불러오고,
  워크트리 격리가 안 되며, 메모리·세션 기록도 따로 쌓여 다음 세션이 앞 작업을 못 찾는다(2026-09-13 실제로 겪음).
  옆 저장소 참조가 필요하면 `--add-dir ../jhg-commerce-project`로 붙인다.

## 어느 스택인가 — 응답이 왔다고 의도한 스택이 답한 게 아니다

| 주소 | 무엇 | DB | AI 키 |
|---|---|---|---|
| `:8080` | OMS (다른 저장소) | OMS DB | — |
| `:8081` | WMS 단독 기동(IntelliJ·bootRun), 개발용 | brew Postgres `wms` — 시연·잔재 데이터 섞임 | 있음 |
| `:8090` | 공개 스택(compose): nginx → wms1~3 | compose Postgres 컨테이너 | **없음** |
| `https://wms.jhgsoft.com` | Cloudflare Tunnel → `:8090` | 〃 | 〃 |

- 구별: 공개 스택 응답엔 `X-Served-By` 헤더가 있고(요청마다 바뀜) `:8081`엔 없다.
- **공개·터널 대상은 반드시 `:8090`.** `:8081`을 공개하면 개발 DB와 AI 키가 함께 나간다(실제로 그럴 뻔했다).
- MCP 서버(`.mcp.json`)는 `:8081`을 본다 — 보고서 숫자는 개발 DB 기준이다.
- 테스트는 `wms_test` DB(create-drop)를 쓴다. 개발 DB `wms`는 건드리지 않는다.

## 검증

- `./gradlew test` — 전체 테스트. brew `postgresql@17` 필요. `eval` 태그는 제외된다.
- `./gradlew evalTest` — 반품 분류·발주 메모 분류·브리핑 평가. **실제 API를 호출해 과금된다 — 요청 없이 돌리지 않는다.**
- `cd mcp-server && uv run --locked pytest -q`
- PR 체크 5종(Build & Test · Docker image builds · MCP server · integration · system-integration/wms)이 전부 pass여야 병합한다.

## 규약

- 커밋 제목: `type(wms): 한국어 현재형 서술` — feat / fix / docs / test / ci. 왜 바꿨는지는 본문에.
- 병합은 머지 커밋(`gh pr merge --merge`). master에 직접 push하지 않는다.
- 데이터 시드는 SQL이 아니라 앱 경로(API·폼)를 탄다 — `docs/seed-*.sh`. 멱등이 아니다.
- 평가셋 라벨의 최종 권위는 사람이다. 에이전트가 지어낸 문구로 만든 데이터를 평가셋으로 쓰지 않는다.
- "X가 로그에 안 남는다"를 로그 캡처로 단언하는 테스트는 만들지 않는다 — 로깅 구현에 묶여 깨지기 쉽다. 코드 리뷰로 지킨다.

## 비밀과 공개 스택

- 공개 스택 자격증명은 루트 `.env`(gitignore). 값을 출력·커밋하지 않는다. 공개해도 되는 건 `demo` / `demo1234`(OPERATOR)뿐.
- 공개 스택에 `ANTHROPIC_API_KEY`를 넣지 않는다. 예외(시드)는 README "공개 스택" 절 절차대로 한다.
- **공개 스택을 바꾼 뒤(재빌드·재생성·시드·터널 수정)엔 `docs/check-public-stack.sh`가 통과해야 끝이다.** AI 키, 이미지 최신성, 터널 대상, nginx 3대 순환, demo 로그인을 본다. 손 확인으로 대신하지 않는다.
- `:8090` 직결로는 관리자 폼 POST 뒤 리다이렉트가 `:80`으로 간다(nginx가 `Host`에서 포트를 뗀다). 폼을 타는 스크립트는 공개 주소로 돌린다.

## 상태를 기록할 때

- 켜짐/꺼짐/해소됨 같은 상태를 문서·메모리에 적을 땐 **확인한 명령과 날짜를 같이** 적는다.
  병렬 세션이 서로의 최신 상태를 모른 채 틀린 사실을 남긴 적이 있다(이미 해소된 자동 기동 결손, 이미 끈 Funnel).
- 사용자 보고·PR 본문에 쓰는 수치(줄 수·건수·집계)는 **원본에서 다시 센다.** 요약·이전 메시지에서 옮기지 않는다(같은 날 두 번 틀렸다).
- 옆 저장소 상태는 `git fetch` 후 원격 기본 브랜치로 판단한다. 로컬 체크아웃은 뒤처져 있다
  (2026-09-14 로컬 `service-refs.json`만 보고 "기준이 옛 OMS"라 틀리게 판단했다).
- **계약이 걸린 PR은 병합으로 끝나지 않는다.** 구현 병합 · system-tests 기준 갱신(Codex) · 기준 조합 통합 검증 · 공개 스택 배포를
  [`../jhg-system-tests/docs/cross-repository-work.md`](../jhg-system-tests/docs/cross-repository-work.md) 양식으로 요청 문서에 따로 적는다.
  PR의 `system-integration/wms` 성공은 그 SHA만 일시 교체한 검증이지 기준 갱신이 아니다. 문서만 바뀌면 기준 갱신이 불필요한 이유를 PR에 적는다.

## 어디를 읽나

- 설계·API·운영: `README.md` — 길다. 필요한 절만 찾아 읽는다.
- 평가 기록: `docs/wms-classification-eval.md`, `docs/wms-purchase-order-memo-eval.md`, `docs/wms-briefing-eval.md`
- 보고서: `.claude/skills/` — MCP 숫자를 인용하는 보고서는 해당 스킬 규칙을 따른다.
- 로드맵: `docs/wms-business-roadmap.md`
