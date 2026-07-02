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
