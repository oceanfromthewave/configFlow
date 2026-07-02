# 01. 요구사항 분석 (Requirements Analysis)

## 1. 프로젝트 정의

**ConfigFlow**는 Git과 SVN을 하나의 데스크톱 애플리케이션에서 관리하는 통합 형상관리 클라이언트다.
SourceTree(Git/Hg)와 TortoiseSVN(SVN)을 동시에 대체하는 것을 목표로 하며,
향후 Mercurial 등 다른 VCS를 플러그인 형태로 추가할 수 있는 확장 구조를 갖는다.

## 2. 핵심 문제 (Why)

| 문제 | 현재 상황 | ConfigFlow의 해결 |
|---|---|---|
| 도구 분리 | Git은 SourceTree, SVN은 TortoiseSVN — 서로 다른 UX, 별도 설치 | 하나의 앱, 하나의 UX로 통합 |
| VCS별 개념 차이 | Stage/Stash(Git) vs Lock/Cleanup(SVN)을 사용자가 도구마다 다시 학습 | 공통 개념은 통일된 UI, VCS 고유 개념은 Capability 기반으로 노출 |
| 확장 불가 | 기존 도구는 새 VCS 추가 불가 | VCS Provider 플러그인 아키텍처 |
| AI 부재 | 커밋 메시지, 충돌 해결에 AI 지원 없음 | AI Provider 추상화 계층 선탑재 |

## 3. 이해관계자 및 사용자

- **주 사용자**: Git과 SVN을 함께 사용하는 개발자 (예: 신규 프로젝트는 Git, 레거시는 SVN인 조직)
- **부 사용자**: 단일 VCS만 사용하지만 SourceTree 대체재를 찾는 개발자
- **운영 환경**: Windows 우선, macOS/Linux 지원 가능한 크로스 플랫폼 구조

## 4. 기능 요구사항 (요약)

상세 정의는 `02-feature-definition.md` 참조.

- **FR-1 Repository 관리**: 등록/Clone/Init/Remove/Open, 최근 목록, 즐겨찾기
- **FR-2 Git 작업**: Clone, Pull, Push, Fetch, Branch CRUD, Checkout, Merge, Rebase, Cherry-Pick, Stash, Tag, Reset, Revert
- **FR-3 SVN 작업**: Checkout, Update, Commit, Cleanup, Revert, Resolve, Lock/Unlock, Repository Browser
- **FR-4 Working Tree**: 변경 파일 조회, Stage/Unstage, Ignore, Diff, 파일 상태 표시
- **FR-5 Commit**: Commit, Amend, History, Author/Message 검색
- **FR-6 Branch 시각화**: Tree View, Graph View, Merge, Compare
- **FR-7 Diff Viewer**: Side-by-Side/Inline, Syntax Highlight, 라인 번호, 변경 유형 표시 (GitHub 수준)
- **FR-8 History**: Commit Graph(SourceTree 수준), Merge/Tag 표시, Filter/Search
- **FR-9 Conflict**: 충돌 파일 표시, 3-way 비교, Merge Editor, (추후) AI 해결 제안
- **FR-10 Settings**: Git/SVN Path, SSH Key, Theme, Language, Proxy
- **FR-11 AI 통합(인터페이스 우선)**: Commit Message 생성, 변경 요약, 충돌 해결, 코드 리뷰, PR 설명, 영향 분석

## 5. 비기능 요구사항 (NFR)

| ID | 항목 | 목표 |
|---|---|---|
| NFR-1 | 성능 | 커밋 10만 개 이상 Repository에서 그래프 로딩 3초 이내 (가상 스크롤 + 페이지네이션) |
| NFR-2 | 응답성 | 모든 VCS 작업은 비동기 실행, UI 블로킹 금지, 장기 작업은 진행률 스트리밍 |
| NFR-3 | 확장성 | 신규 VCS 추가 시 Core/UI 수정 없이 Provider 모듈 추가만으로 가능 |
| NFR-4 | 유지보수성 | Clean Architecture 계층 준수, 모듈 간 순환 의존 금지 |
| NFR-5 | 테스트 가능성 | Domain/Application 계층은 인프라 없이 단위 테스트 가능 |
| NFR-6 | 보안 | 자격 증명은 OS Credential Store 사용(평문 저장 금지), AI 전송 데이터는 사용자 동의 기반 |
| NFR-7 | 국제화 | i18n 구조 선반영 (한국어/영어) |
| NFR-8 | 접근성 | 키보드 내비게이션, 다크/라이트 테마 |

## 6. 제약 조건

- Backend: **Java 21 + Spring Boot** (JGit, SVNKit이 Java 생태계이므로 자연스러운 선택)
- Frontend: **React + TypeScript + Vite + Tailwind CSS**
- Desktop: **Electron** (ADR-001에서 Tauri와 비교 후 결정 — Spring Boot 사이드카 프로세스 관리와 성숙도 기준)
- Local Data: **SQLite** (Repository 메타데이터, 설정, 캐시)
- 대용량 Repository 지원을 처음부터 고려 (스트리밍, 페이징, 캐싱)

## 7. 범위 제외 (Out of Scope — v1)

- Git 서버 호스팅 기능 (GitHub/GitLab 연동은 추후)
- SVN 서버 관리 (svnadmin)
- 실시간 협업 기능
- 모바일 클라이언트
- Mercurial Provider (구조만 준비, 구현은 v2 이후)

## 8. 성공 기준

1. Git Repository와 SVN Working Copy를 같은 앱에서 열고, 각각의 전체 워크플로우(clone→변경→커밋→push/update)를 수행할 수 있다.
2. 신규 VCS Provider를 Core 코드 수정 없이 추가할 수 있음을 Mock Provider 테스트로 증명한다.
3. Domain/Application 계층 테스트 커버리지 80% 이상.
4. 10만 커밋 규모 Repository에서 그래프/히스토리 탐색이 실사용 가능한 수준으로 동작한다.

## 9. 주요 리스크와 대응

| 리스크 | 영향 | 대응 |
|---|---|---|
| Git과 SVN의 개념 불일치 (Stage 없음, 리비전 모델 차이) | 공통 추상화 실패 시 UI가 VCS별로 분기 폭발 | **Capability 모델**: Provider가 지원 기능을 선언하고 UI는 Capability 기반으로 렌더링 |
| JGit의 대용량 성능 한계 | 초대형 repo에서 느림 | 인터페이스 뒤에 Native Git CLI 구현체 교체 가능하도록 설계 |
| SVNKit 라이선스(TMate 오픈소스 라이선스) | 상용 배포 시 조건 확인 필요 | 라이선스 검토 + Native SVN CLI 대체 구현 경로 확보 |
| Electron + JVM 동시 구동 메모리 | 리소스 사용량 큼 | JVM 옵션 튜닝, 필요 시 GraalVM Native Image 검토 |
| 자격 증명 보안 | 유출 시 치명적 | OS Keychain(Windows Credential Manager 등) 위임 |
