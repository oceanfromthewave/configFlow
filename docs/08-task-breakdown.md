# 08. 작업 분리 및 우선순위 (Task Breakdown)

## 마일스톤 로드맵

| 마일스톤 | 목표 | 완료 기준 |
|---|---|---|
| **M0 스캐폴딩** | 빌드 가능한 뼈대 | backend/frontend/desktop이 각각 빌드·기동, Electron에서 React 화면 + backend 헬스체크 표시 |
| **M1 Git 코어** | Git 단독으로 실사용 가능 | 등록→상태→stage→commit→history→push/pull 전체 플로우 |
| **M2 시각화** | SourceTree급 뷰 | Commit Graph, Diff Viewer(Side-by-Side/Inline), 브랜치 트리 |
| **M3 SVN** | SVN 워크플로우 | checkout→update→commit→lock→browser |
| **M4 고급 Git + 충돌** | Merge Editor 포함 | merge/rebase/cherry-pick/stash/conflict 해결 |
| **M5 완성도** | 배포 가능 | Settings 전체, 자격증명, i18n, 인스톨러, E2E |
| **M6 AI (v2)** | AI 기능 활성화 | Claude/OpenAI Provider, commit message 생성 등 |

## Task 목록 (M0–M2 상세, M3+ 요약)

### M0. 스캐폴딩 — 의존성 없음, 3트랙 병렬 가능

| Task | 내용 | 담당 트랙 |
|---|---|---|
| M0-T1 | Gradle 멀티모듈 셋업 (domain/application/infra 4종/bootstrap), ArchUnit 규칙 | Backend |
| M0-T2 | domain 핵심 모델 + 포트 인터페이스 전체 정의 (컴파일만 되는 수준) | Backend |
| M0-T3 | SQLite + Flyway V1 마이그레이션, persistence 어댑터 | Backend |
| M0-T4 | 동적 포트 + 토큰 인증 필터, `/health`, SSE 엔드포인트 뼈대 | Backend |
| M0-T5 | Vite + React + TS + Tailwind 셋업, 디자인 토큰, 기본 레이아웃 셸 | Frontend |
| M0-T6 | API 클라이언트 + SSE 구독 유틸 + TanStack Query 셋업 | Frontend |
| M0-T7 | Electron main/preload, backend-launcher (JAR 기동·핸드셰이크·종료) | Desktop |
| M0-T8 | `scripts/dev.ps1` 통합 개발 기동, CI 파이프라인 초안 | 공통 |

### M1. Git 코어 — Backend(Engine)와 Frontend 병렬

| Task | 내용 | 의존 |
|---|---|---|
| M1-T1 | `vcs-git`: GitProvider 골격 + detect/open + Capability 선언 | M0-T2 |
| M1-T2 | Repository UseCase + API (등록/목록/즐겨찾기/최근/열기) | M0-T3 |
| M1-T3 | OperationQueue 구현 (repo별 순차, 취소, SSE 이벤트 발행) | M0-T4 |
| M1-T4 | `vcs-git`: status/stage/unstage/discard/ignore | M1-T1 |
| M1-T5 | `vcs-git`: commit/amend/history(커서 페이징)/show | M1-T1 |
| M1-T6 | `vcs-git`: clone/fetch/pull/push + 진행률 콜백 + 인증(HTTPS/SSH) | M1-T1, M1-T3 |
| M1-T7 | 자격 증명: OS Credential Store 어댑터 + 401 재시도 플로우 | M0-T3 |
| M1-T8 | FE: Welcome 화면 (목록/즐겨찾기/Clone/Add/Init) | M0-T5,6 |
| M1-T9 | FE: Working Tree 패널 (파일 목록/stage/커밋 박스) | M0-T5,6 |
| M1-T10 | FE: 히스토리 리스트(그래프 없이) + 커밋 상세 | M0-T5,6 |
| M1-T11 | FE: Operations/Console 하단 패널 + 진행률 토스트 | M0-T6 |
| M1-T12 | 통합 테스트: 임시 git repo 기반 E2E 커밋 플로우 | M1-T4,5 |

### M2. 시각화

| Task | 내용 | 의존 |
|---|---|---|
| M2-T1 | 그래프 레인 배치 알고리즘 (application/graph) + 단위 테스트 | M1-T5 |
| M2-T2 | FE: Canvas Commit Graph + 가상 스크롤 + refs 칩 | M2-T1 |
| M2-T3 | BE: 구조화 diff API (hunk JSON, rename 감지, 바이너리 판별) | M1-T5 |
| M2-T4 | FE: Diff Viewer (Side-by-Side/Inline, syntax highlight, 가상 스크롤) | M2-T3 |
| M2-T5 | FE: 브랜치 사이드바 트리 + 컨텍스트 메뉴 | M1-T10 |
| M2-T6 | BE+FE: 히스토리 필터/검색 (author/message/date/path) | M1-T5 |
| M2-T7 | BE+FE: Compare (두 ref 비교) | M2-T3 |

### M3. SVN (요약)
SvnProvider(checkout/update/commit/revert/cleanup) → lock/unlock → Repository Browser → 히스토리 캐시 → Capability 기반 UI 검증 (Stage 숨김 등)

### M4. 고급 Git + 충돌 (요약)
branch CRUD·checkout·merge → rebase(continue/abort/skip)·cherry-pick → stash → tag·reset·revert → Conflict API(3-way) → Merge Editor UI → resolve 플로우 (Git `add`/SVN `resolve` 공용)

### M5. 완성도 (요약)
Settings 전체 화면 → SSH key 관리 → Proxy → i18n(ko/en) → 파일 watcher 자동 갱신 → 파괴적 작업 확인 UX → electron-builder 인스톨러 + jlink JRE → Playwright E2E → 성능 검증(10만 커밋 repo)

### M6. AI (요약)
AiProvider 계약 테스트 → Claude Provider → OpenAI Provider → 시크릿 마스킹 필터 → Commit Message 생성 UI → 변경 요약 → 충돌 해결 제안

## Sub-Agent 병렬 실행 계획

CLAUDE.md의 서브에이전트 정책에 따른 매핑:

| 시점 | 병렬 실행 Agent | 작업 |
|---|---|---|
| M0 | Backend + Frontend + Desktop | M0-T1~4 ∥ M0-T5~6 ∥ M0-T7 |
| M1 | Git Engine + Frontend + Backend | M1-T4~6 ∥ M1-T8~11 ∥ M1-T2,3,7 |
| M2 | Git Engine + Frontend + UI/UX | M2-T1,3 ∥ M2-T2,4,5 |
| M3 | SVN Engine + Testing | M3 전체 ∥ M1/M2 회귀 테스트 보강 |
| M4 | Git Engine + Frontend | 고급 명령 ∥ Merge Editor |
| M5 | Desktop + Testing + Documentation | 인스톨러 ∥ E2E ∥ 문서 |

통합 규칙: 각 Agent 결과는 메인 에이전트가 아키텍처 일관성(계층 규칙, 네이밍, Capability 준수) 검토 후 병합.

## 우선순위 원칙

1. **세로 슬라이스 우선**: 얇더라도 등록→커밋→푸시가 끝까지 동작하는 것이 개별 기능 완성도보다 먼저다 (M1).
2. **추상화 검증 조기화**: SVN Provider(M3)를 너무 늦추지 않는다 — 두 번째 구현체가 추상화의 시험대.
3. **성능 리스크 조기 검증**: M2에서 대형 repo(예: linux kernel clone)로 그래프/히스토리 성능을 즉시 측정.
4. AI는 인터페이스만 M0에 포함하고 구현은 마지막 (외부 의존·비용 리스크 격리).
