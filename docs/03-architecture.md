# 03. 아키텍처 설계 (Architecture)

## 1. 전체 구성 (System Overview)

```
┌────────────────────────────────────────────────────────────────┐
│ Electron Shell (desktop/)                                      │
│  ├─ Main Process: 창 관리, 백엔드 프로세스 생명주기, 네이티브 다이얼로그 │
│  ├─ Preload: 안전한 IPC 브리지 (contextIsolation)                │
│  └─ Renderer: React SPA (frontend/)                            │
│       │  HTTP (REST) + SSE (진행률/이벤트 스트림)                 │
│       ▼                                                        │
│ Spring Boot Backend (backend/) — localhost 전용, 토큰 인증        │
│  ├─ api          : REST Controller, SSE, DTO                   │
│  ├─ application  : UseCase, 작업 큐, 트랜잭션 경계                │
│  ├─ domain       : VCS 추상화 모델, 포트(인터페이스), 도메인 서비스   │
│  └─ infrastructure                                             │
│       ├─ vcs-git  : JGit 기반 GitProvider                      │
│       ├─ vcs-svn  : SVNKit 기반 SvnProvider                    │
│       ├─ persistence : SQLite (Repository 메타, 설정, 캐시)      │
│       ├─ credential  : OS Credential Store 어댑터               │
│       └─ ai          : AI Provider 어댑터 (Claude/OpenAI, 추후)  │
└────────────────────────────────────────────────────────────────┘
```

- Frontend ↔ Backend 통신은 **HTTP REST + SSE**. Electron IPC는 네이티브 기능(파일 다이얼로그, 창 제어, 셸 열기)에만 사용한다.
  - 이유: 통신 계층을 웹 표준으로 유지하면 추후 브라우저 모드/원격 모드 확장이 가능하고, Backend를 독립적으로 테스트할 수 있다.
- Backend는 **임의 포트 + 세션 토큰**으로 기동하고 Electron Main이 토큰을 Renderer에 주입한다 (로컬 권한 상승 공격 방지).

## 2. 계층 구조 (Clean Architecture)

의존 방향은 항상 안쪽(Domain)으로만 향한다.

```
api ──▶ application ──▶ domain ◀── infrastructure
```

| 계층 | 책임 | 의존 | 금지 사항 |
|---|---|---|---|
| **domain** | VCS 도메인 모델, 포트 인터페이스, 도메인 규칙 | 없음 (순수 Java) | Spring/JGit/SVNKit import 금지 |
| **application** | UseCase 조합, 작업 큐, 이벤트 발행 | domain | HTTP/DB 세부사항 금지 |
| **api** | REST/SSE 엔드포인트, DTO 매핑, 예외 → HTTP 변환 | application | 비즈니스 로직 금지 |
| **infrastructure** | 포트 구현체 (JGit, SVNKit, SQLite, Keychain, AI) | domain (포트 구현) | 다른 infra 모듈 직접 참조 금지 |

Gradle 멀티모듈로 계층을 물리적으로 분리하여 컴파일 타임에 의존 규칙을 강제한다.

## 3. 핵심 추상화: VCS Provider (플러그인 아키텍처)

### 3.1 설계 원칙

- Domain은 **`VcsProvider` 포트(인터페이스 집합)**만 알고, Git/SVN은 그 구현체다.
- Provider는 **Capability를 선언**하고, Application/UI는 Capability를 조회해 기능을 노출한다.
  - "SVN에는 Stash가 없다"를 if(svn) 분기가 아니라 `provider.capabilities().contains(STASH)`로 처리.
- 신규 VCS(예: Mercurial)는 `VcsProvider` 구현 모듈을 추가하고 `VcsProviderRegistry`에 등록하면 끝.

### 3.2 포트 인터페이스 (Interface Segregation)

하나의 거대 인터페이스가 아니라 역할별로 분리한다. Provider는 지원하는 인터페이스만 구현한다.

```java
// domain/vcs/port/
public interface VcsProvider {
    VcsType type();                          // GIT, SVN, ...
    Set<VcsCapability> capabilities();
    boolean detect(Path localPath);          // .git / .svn 감지
    RepositoryHandle open(Path localPath);
}

public interface RepositoryOperations {      // clone/checkout/init
    OperationHandle cloneRepository(CloneRequest req);   // 진행률 스트리밍
    RepositoryHandle init(Path path);
}

public interface WorkingTreeOperations {
    WorkingTreeStatus status(RepositoryHandle repo);
    void stage(RepositoryHandle repo, List<Path> paths);      // STAGING capability
    void unstage(RepositoryHandle repo, List<Path> paths);
    void discard(RepositoryHandle repo, List<Path> paths);
    void ignore(RepositoryHandle repo, IgnorePattern pattern);
}

public interface CommitOperations {
    CommitId commit(RepositoryHandle repo, CommitRequest req);
    Page<Revision> history(RepositoryHandle repo, HistoryQuery query);  // 페이징 필수
    Revision show(RepositoryHandle repo, RevisionId id);
}

public interface BranchOperations { /* list/create/delete/checkout/merge/compare */ }
public interface RemoteSyncOperations { /* fetch/pull/push (git), update (svn) */ }
public interface DiffOperations { /* diff(working), diff(revision..revision), file content at revision */ }
public interface ConflictOperations { /* list, threeWayContent, resolve */ }
public interface HistoryGraphOperations { /* graph rows with lane assignment */ }

// Git 전용 확장 포트 (선택 구현)
public interface StashOperations { /* ... */ }
public interface RebaseOperations { /* start/continue/abort/skip */ }
public interface TagOperations { /* ... */ }

// SVN 전용 확장 포트 (선택 구현)
public interface LockOperations { /* lock/unlock/breakLock */ }
public interface RemoteBrowseOperations { /* repository browser */ }
```

### 3.3 공통 도메인 모델 (Ubiquitous Language)

Git 커밋과 SVN 리비전을 **`Revision`**으로 통합한다.

```java
record RevisionId(String value)            // Git: SHA, SVN: 리비전 번호 문자열
record Revision(RevisionId id, List<RevisionId> parents, Author author,
                Instant timestamp, String message, List<RefLabel> labels)
record FileChange(Path path, ChangeType type, Path oldPath /*rename*/)
enum ChangeType { ADDED, MODIFIED, DELETED, RENAMED, COPIED, CONFLICTED, UNTRACKED, IGNORED, LOCKED_BY_OTHER }
record WorkingTreeStatus(List<FileChange> staged, List<FileChange> unstaged, List<FileChange> conflicted)
   // SVN: staged는 항상 빈 목록 (STAGING capability 없음)
```

## 4. 장기 실행 작업 모델 (Operation Queue)

Clone, Fetch, 대규모 Diff 등은 수 분이 걸릴 수 있다.

- 모든 변경성 작업은 `OperationQueue`에 제출되고 **Repository 단위로 순차 실행** (동일 working copy 동시 변경 방지). 서로 다른 Repository는 병렬 실행.
- 작업은 `Operation { id, type, state(QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED), progress, log }`로 모델링한다.
- `Operation`에는 `CONFLICTED` 상태를 두지 않는다.
- Merge/Rebase/Cherry-pick 등에서 충돌이 발생하면 Operation은 `FAILED`로 종료하고, `error.code`에 `MERGE_CONFLICT` 등의 안정적인 오류 코드를 전달한다.
- 충돌 파일 및 3-way 내용은 Conflict API를 통해 조회한다.
- 진행률과 상태 변화는 **도메인 이벤트 → SSE**로 Frontend에 스트리밍.
- 조회성 작업(status, history 페이지)은 큐를 거치지 않고 즉시 실행하되 읽기 락으로 보호.

## 5. 이벤트 아키텍처

```
Provider(진행률 콜백) → Application(OperationEvents) → SSE Endpoint → Frontend(EventSource)
                                    └→ Console Log 패널 기록 (실행 명령/결과)
```

- 이벤트 종류 (실제 와이어 이름): `operation.progress`, `operation.completed`, `workingtree.changed`(watcher), `repository.refs-changed`(fetch/브랜치 후), `repository.registered`, `console.line`(콘솔 로그). 상세 페이로드는 07 §3.
- Frontend는 이벤트 수신 시 해당 화면의 쿼리 캐시(TanStack Query)를 무효화하여 자동 갱신.

## 6. AI Provider 추상화

```java
public interface AiProvider {
    String id();                                  // "claude", "openai", ...
    Set<AiFeature> supportedFeatures();
    AiResult<String> generateCommitMessage(DiffContext ctx);
    AiResult<String> summarizeChanges(DiffContext ctx);
    AiResult<MergeProposal> resolveConflict(ConflictContext ctx);
    AiResult<ReviewReport> reviewCode(DiffContext ctx);
    // 스트리밍 응답: AiResult는 동기값 또는 토큰 스트림 구독을 모두 표현
}
```

- `AiProviderRegistry`에서 설정 기반으로 활성 Provider 선택.
- **프라이버시 게이트**: AI로 전송되는 diff는 전송 전 사용자 동의 + 마스킹 규칙(시크릿 패턴 필터) 통과 필수.
- v1에서는 인터페이스 + `NoopAiProvider`만 탑재하고 UI 진입점을 disabled 상태로 준비.

## 7. 데이터 저장

- **SQLite** (backend 관리, 단일 파일 `~/.configflow/configflow.db`)
  - repositories, favorites, recent_open, settings, credentials_ref(실제 비밀은 OS store), operation_history
- **캐시**: SVN 원격 히스토리, 그래프 레인 계산 결과 등은 테이블 분리 (`cache_*`), 삭제해도 무방한 데이터임을 구조로 표현.
- 상세 스키마는 `05-data-model.md`.

## 8. 보안 설계

1. Backend는 `127.0.0.1` 바인딩 + 기동 시 생성되는 랜덤 토큰 검증 (Electron Main만 토큰 보유).
2. 자격 증명: Windows Credential Manager / macOS Keychain / libsecret 위임. SQLite에는 참조 키만 저장.
3. Electron: `contextIsolation: true`, `nodeIntegration: false`, preload에서 최소 API만 노출.
4. AI 전송 데이터: opt-in, 시크릿 마스킹, 전송 로그 기록.

## 9. 테스트 전략

| 레벨 | 대상 | 도구 | 방식 |
|---|---|---|---|
| Unit | domain, application | JUnit 5, Mockito | 포트를 Mock/Fake로 대체. **MockVcsProvider**로 Capability 분기 검증 |
| Integration | vcs-git, vcs-svn | JUnit 5 + 임시 실제 저장소 | 테스트마다 임시 디렉터리에 실제 git repo/svn repo(file://) 생성 후 실작업 검증 |
| Integration | persistence | JUnit 5 + 임시 SQLite | 마이그레이션 + CRUD |
| API | api | Spring MockMvc / WebTestClient | 계약 검증, 에러 매핑 |
| Frontend Unit | 컴포넌트, 상태 | Vitest + Testing Library | MSW로 API 모킹 |
| E2E | 전체 플로우 | Playwright (Electron) | clone→commit→push 핵심 시나리오 |

**아키텍처 테스트**: ArchUnit으로 계층 의존 규칙(domain의 무의존성 등)을 CI에서 강제.

## 10. 주요 설계 결정 요약 (ADR 목록)

| ADR | 결정 | 상태 |
|---|---|---|
| ADR-001 | Desktop: Electron 채택 (vs Tauri) | 승인 — `adr/ADR-001-desktop-framework.md` |
| ADR-002 | Frontend↔Backend: REST+SSE (vs WebSocket, IPC 직결) | 승인 — 본 문서 §1 |
| ADR-003 | VCS 추상화: 역할별 포트 + Capability 모델 | 승인 — 본 문서 §3 |
| ADR-004 | Git 엔진: JGit 기본, Native CLI 교체 가능 구조 | 승인 |
| ADR-005 | 작업 모델: Repository 단위 순차 큐 + SSE 진행률 | 승인 |
