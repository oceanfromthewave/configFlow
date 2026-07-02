# 05. 데이터 모델 설계 (Data Model)

두 층위로 나뉜다:
1. **Domain 모델** — 메모리 상의 VCS 추상화 (영속화하지 않음, Provider가 매번 조회)
2. **SQLite 스키마** — 앱 메타데이터 (Repository 목록, 설정, 캐시)

원칙: **VCS 데이터 자체(커밋, 브랜치)는 DB에 저장하지 않는다.** 원본은 항상 Git/SVN이며, DB는 앱 상태와 성능용 캐시만 담당한다. 캐시는 언제든 삭제 가능해야 한다.

---

## 1. Domain 모델

```
Repository (애그리거트 루트)
├── id: RepositoryId (UUID)
├── name, localPath, remoteUrl
├── vcsType: GIT | SVN
├── favorite: boolean, groupName
└── lastOpenedAt

Revision  ← Git 커밋과 SVN 리비전의 통합 모델
├── id: RevisionId          (Git: SHA-1/256 hex, SVN: "r1234")
├── parents: RevisionId[]   (SVN은 항상 0~1개)
├── author: { name, email }
├── timestamp, message
└── labels: RefLabel[]      (BRANCH | REMOTE_BRANCH | TAG | HEAD)

WorkingTreeStatus
├── staged: FileChange[]        (STAGING capability 없으면 빈 배열)
├── unstaged: FileChange[]
└── conflicted: ConflictedFile[]

FileChange { path, changeType, oldPath?, locked?, lockOwner? }

GraphRow  ← Commit Graph 렌더링 단위
├── revision: Revision
├── lane: int                   (이 커밋이 그려질 세로 레인)
└── edges: { fromLane, toLane, type: NORMAL|MERGE|BRANCH_OUT }[]

Operation
├── id: OperationId (UUID)
├── repositoryId, type (CLONE|FETCH|PULL|PUSH|MERGE|...)
├── state: QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED
├── progress: { percent?, phase, detail }    (percent 불명 작업은 indeterminate)
├── startedAt, finishedAt, errorMessage?
└── logLines: string[]          (Console 패널용 — 실행 명령·출력)

ConflictedFile
├── path
├── mineContent / theirsContent / baseContent (지연 로드)
└── resolution: UNRESOLVED | MINE | THEIRS | MANUAL
```

---

## 2. SQLite 스키마

파일 위치: `%USERPROFILE%\.configflow\configflow.db` · 마이그레이션: Flyway (`V1__init.sql`, ...)

```sql
-- 등록된 Repository
CREATE TABLE repository (
    id            TEXT PRIMARY KEY,              -- UUID
    name          TEXT NOT NULL,
    local_path    TEXT NOT NULL UNIQUE,
    remote_url    TEXT,
    vcs_type      TEXT NOT NULL CHECK (vcs_type IN ('GIT','SVN')),
    group_name    TEXT,                          -- 즐겨찾기 그룹
    is_favorite   INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL,                 -- ISO-8601
    last_opened_at TEXT
);
CREATE INDEX idx_repository_last_opened ON repository(last_opened_at DESC);

-- 앱 설정 (key-value, 타입은 애플리케이션에서 보장)
CREATE TABLE app_setting (
    key        TEXT PRIMARY KEY,                 -- 'theme', 'language', 'git.executablePath',
    value      TEXT NOT NULL,                    -- 'proxy.url', 'ai.provider', ...
    updated_at TEXT NOT NULL
);

-- 자격 증명 참조 (실제 비밀은 OS Credential Store)
CREATE TABLE credential_ref (
    id          TEXT PRIMARY KEY,
    host        TEXT NOT NULL,                   -- 'github.com', 'svn.company.com'
    protocol    TEXT NOT NULL,                   -- 'https', 'ssh', 'svn'
    username    TEXT,
    store_key   TEXT NOT NULL,                   -- OS store 조회 키
    created_at  TEXT NOT NULL,
    UNIQUE(host, protocol, username)
);

-- 작업 이력 (Console/Log 패널, 감사 용도)
CREATE TABLE operation_history (
    id            TEXT PRIMARY KEY,
    repository_id TEXT REFERENCES repository(id) ON DELETE CASCADE,
    type          TEXT NOT NULL,
    state         TEXT NOT NULL,
    started_at    TEXT NOT NULL,
    finished_at   TEXT,
    error_message TEXT,
    log           TEXT                            -- 압축된 명령 로그 (상한 적용)
);
CREATE INDEX idx_operation_repo_time ON operation_history(repository_id, started_at DESC);

-- SVN 원격 히스토리 캐시 (오프라인 조회·성능)
CREATE TABLE cache_svn_log (
    repository_id TEXT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
    revision      INTEGER NOT NULL,
    author        TEXT, timestamp TEXT, message TEXT,
    changed_paths TEXT,                           -- JSON
    PRIMARY KEY (repository_id, revision)
);

-- UI 상태 (탭, 패널 크기 등 — 삭제해도 무방)
CREATE TABLE ui_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL                           -- JSON
);
```

### 스키마 설계 노트

- `cache_*` / `ui_state`는 삭제해도 기능 손실이 없는 데이터 — "Settings > 캐시 비우기"가 이 테이블만 truncate.
- Git 히스토리는 캐시하지 않는다 (JGit이 로컬 `.git`에서 충분히 빠름). SVN만 네트워크 왕복이 필요하므로 캐시.
- `operation_history.log`는 행당 상한(예: 64KB)을 두고 오래된 이력은 보존 정책(예: 90일)으로 정리.
- 날짜는 TEXT(ISO-8601, UTC)로 통일 — SQLite의 date 함수와 호환되며 타임존 모호성 제거.

---

## 3. 설정 키 목록 (app_setting)

| key | 예시 값 | 비고 |
|---|---|---|
| `theme` | `dark` (기본) / `light` | |
| `language` | `ko` / `en` | |
| `git.engine` | `jgit` / `cli` | ADR-004 |
| `git.executablePath` | `C:\Program Files\Git\bin\git.exe` | engine=cli일 때 |
| `svn.engine` | `svnkit` / `cli` | |
| `ssh.keyPath` | `~/.ssh/id_ed25519` | |
| `proxy.url`, `proxy.bypass` | | |
| `ai.provider` | `none` (기본) / `claude` / `openai` | |
| `ai.consent.sendDiff` | `false` (기본) | 프라이버시 게이트 |
