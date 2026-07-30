# 07. API 설계 (REST + SSE)

## 1. 공통 규약

- Base URL: `http://127.0.0.1:{동적포트}/api/v1`
- 인증: 모든 요청에 `X-ConfigFlow-Token: {세션토큰}` (Electron Main이 기동 시 발급받아 Renderer에 주입)
- 직렬화: JSON, 날짜는 ISO-8601 UTC
- ID: Repository는 UUID, Revision은 VCS 네이티브 ID 문자열 (SHA / "r1234")

### 에러 응답 (RFC 9457 Problem Details)

```json
{
  "type": "urn:configflow:error:merge-conflict",
  "title": "Merge resulted in conflicts",
  "status": 409,
  "detail": "3 files are conflicted",
  "code": "MERGE_CONFLICT",              // 프론트 분기용 안정 코드
  "context": { "conflictedFiles": ["src/a.ts", "..."] }
}
```

주요 에러 코드 (정본: `domain/operation/OperationFailures.java`): `VALIDATION_ERROR`(400),
`NOT_FOUND`(404), `VCS_AUTH_REQUIRED`(자격증명 재요청 플로우), `MERGE_CONFLICT`,
`CONFLICT`(전제조건 위반 — 비-FF 거부 등), `VCS_NETWORK_ERROR`, `CANCELLED`,
`CAPABILITY_NOT_SUPPORTED`(SVN에 stash 요청 등 — 400), `INTERNAL_ERROR`.
토큰 인증 실패는 `AUTH_TOKEN_INVALID`(401).

### 실행 모델

- **조회성 API**: 동기 응답.
- **변경성/장기 API**: `202 Accepted` + `Operation` 반환 → 진행률은 SSE로 구독. 짧은 작업(브랜치 생성 등)도 동일 모델로 통일해 프론트 처리 단순화.

```json
// 202 응답 본문
{ "operationId": "…", "type": "CLONE", "state": "QUEUED" }
```

## 2. 엔드포인트

### Repositories

| Method | Path | 설명 |
|---|---|---|
| GET | `/repositories` | 등록 목록 (`?favorite=true`, `?sort=recent`) |
| POST | `/repositories` | 로컬 경로 등록 (VCS 자동 감지) `{ localPath }` |
| POST | `/repositories/clone` | Git clone / SVN checkout → 202 `{ url, localPath, vcsType, credentialId? }` |
| POST | `/repositories/init` | Git init `{ localPath, name }` |
| GET | `/repositories/{id}` | 상세 (+ `capabilities: string[]`) |
| PATCH | `/repositories/{id}` | 이름/즐겨찾기/그룹 수정 |
| DELETE | `/repositories/{id}` | 등록 해제 (`?deleteFromDisk=true`는 명시적) |
| POST | `/repositories/{id}/open` | 열기 기록 갱신 + 상태 사전 로드 |

### Working Tree

| Method | Path | 설명 |
|---|---|---|
| GET | `/repositories/{id}/status` | WorkingTreeStatus |
| POST | `/repositories/{id}/stage` | `{ paths: [] }` (STAGING 필요) |
| POST | `/repositories/{id}/unstage` | 〃 |
| POST | `/repositories/{id}/discard` | 〃 (2단계 확인은 프론트 책임) |
| POST | `/repositories/{id}/ignore` | `{ pattern }` |
| GET | `/repositories/{id}/diff?path=&staged=` | 워킹트리 파일 diff (구조화된 hunk JSON) |

### Commits / History

| Method | Path | 설명 |
|---|---|---|
| POST | `/repositories/{id}/commit` | 커밋 `{ message, amend?, paths?(SVN), keepLock?(SVN) }` → 200 `{ revisionId }` (Git 로컬 커밋은 동기) |
| GET | `/repositories/{id}/history` | 커서 페이징 `?cursor=&limit=50&branch=&author=&message=&path=&from=&to=` |
| GET | `/repositories/{id}/commits/{revision}` | 커밋 상세 (메타) |
| GET | `/repositories/{id}/commits/{revision}/changes` | 커밋이 바꾼 파일 목록 (FileChange[], 첫 부모 대비) |
| GET | `/repositories/{id}/commits/{revision}/diff?path=` | 특정 커밋의 파일 diff (첫 부모 대비) |
| GET | `/repositories/{id}/graph` | GraphRow[] 커서 페이징 (레인 배치 포함) — **미구현**: 현재 M2 그래프는 프론트가 history의 `parents`로 레인 계산(SVG) |

### Branches / Tags / Refs

| Method | Path | 설명 |
|---|---|---|
| GET | `/repositories/{id}/refs` | 브랜치(local/remote)/태그 트리 |
| POST | `/repositories/{id}/branches` | `{ name, startPoint?, checkout? }` → 202 |
| DELETE | `/repositories/{id}/branches/{name}` | `?remote=true&force=` → 202 |
| POST | `/repositories/{id}/checkout` | `{ ref }` → 202 |
| POST | `/repositories/{id}/merge` | `{ source, ffOnly?, squash? }` → 202 (충돌 시 Operation `FAILED` + code `MERGE_CONFLICT`; 03 §4) |
| POST | `/repositories/{id}/rebase` | `{ upstream, onto? }` / `/rebase/continue|abort|skip` → 202 |
| POST | `/repositories/{id}/cherry-pick` | `{ revisions: [] }` → 202 |
| POST | `/repositories/{id}/reset` | `{ target, mode: soft|mixed|hard }` → 202 |
| POST | `/repositories/{id}/revert` | `{ revisions: [], mainlineParent? }` → 202 |
| GET | `/repositories/{id}/compare?base=&target=` | 두 ref 간 커밋·파일 차이 |
| POST | `/repositories/{id}/tags` / DELETE `…/tags/{name}` | 태그 |

### Sync (원격)

| Method | Path | 설명 |
|---|---|---|
| POST | `/repositories/{id}/fetch` | `{ remote?, prune? }` → 202 |
| POST | `/repositories/{id}/pull` | `{ strategy: merge|rebase }` → 202 |
| POST | `/repositories/{id}/push` | `{ remote?, forceWithLease?, tags? }` → 202 |
| POST | `/repositories/{id}/svn/update` | `{ revision? }` → 202 |
| POST | `/repositories/{id}/svn/cleanup` | → 202 |

### Stash (Git) / Lock (SVN)

| Method | Path | 설명 |
|---|---|---|
| GET/POST | `/repositories/{id}/stashes` | 목록 / 생성 `{ message, includeUntracked }` |
| POST | `/repositories/{id}/stashes/{n}/apply\|pop` · DELETE `…/{n}` | |
| POST | `/repositories/{id}/locks` | `{ paths, comment }` → 202 |
| DELETE | `/repositories/{id}/locks` | `{ paths, breakLock? }` → 202 |
| GET | `/repositories/{id}/svn/browse?url=&revision=` | Repository Browser 트리 |

### Conflicts

| Method | Path | 설명 |
|---|---|---|
| GET | `/repositories/{id}/conflicts` | 충돌 파일 목록 |
| GET | `/repositories/{id}/conflicts/content?path=` | `{ base, mine, theirs }` 3-way 내용 |
| POST | `/repositories/{id}/conflicts/resolve` | `{ path, resolution: MINE\|THEIRS\|MANUAL, content? }` |

### Operations / Settings / AI

| Method | Path | 설명 |
|---|---|---|
| GET | `/operations?repositoryId=&state=` | 작업 목록 |
| GET | `/operations/{id}` | 상세 (+ log) |
| POST | `/operations/{id}/cancel` | 취소 |
| POST | `/operations/{id}/retry` | 실패 작업 재시도 (§4 인증 플로우) |
| GET/PUT | `/settings` · `/settings/{key}` | 설정 |
| POST | `/credentials` / GET `/credentials` / DELETE `/credentials/{key}` | 자격 증명 (비밀은 OS store로) |
| GET | `/ai/features` | 활성 Provider의 지원 기능 (v1: 빈 배열) |
| POST | `/ai/commit-message` 등 | v1: 501 `CAPABILITY_NOT_SUPPORTED` |

## 3. SSE 이벤트 스트림

`GET /api/v1/events` (토큰 인증, 자동 재연결은 프론트 책임)

```
event: operation.progress
data: { "operationId": "…", "percent": 42, "phase": "Receiving objects", "detail": "…" }

event: operation.completed
data: { "operationId": "…", "state": "SUCCEEDED|FAILED|CANCELLED", "error": null,
        "result": { "conflicted": false } }

event: workingtree.changed          // 파일 watcher
data: { "repositoryId": "…" }

event: repository.refs-changed      // fetch/branch 작업 후
data: { "repositoryId": "…" }

event: console.line                 // Console 패널 실시간 로그
data: { "repositoryId": "…", "operationId": "…", "line": "git fetch origin", "level": "cmd|out|err" }
```

프론트 규칙: `operation.completed` 수신 → 관련 TanStack Query 키 무효화 (`status`, `history`, `refs`, `graph`).

## 4. 인증 필요(401) 플로우

1. 작업이 `VCS_AUTH_REQUIRED`로 실패 → `operation.completed`의 error에 `host`, `protocol` 포함
2. 프론트가 자격 증명 모달 표시 → `POST /credentials` 저장
3. 원래 작업 재시도 (`POST /operations/{id}/retry`)
