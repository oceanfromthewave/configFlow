-- 실패 원인을 이름으로 기록한다.
--
-- error_message 는 JGit/SVNKit 이 만든 산문이라 클라이언트가 분기 근거로 쓸 수 없다.
-- (라이브러리 버전이 바뀌면 문구가 바뀐다.) 방금 실패한 작업은 SSE 로 code 를 받는데
-- 히스토리에서 읽어온 작업만 code 가 없으면 같은 인증 실패가 패널에서 다르게 보인다.
--
-- context(host/protocol)는 저장하지 않는다. 그 값이 답하는 질문은 "어떻게 재시도하느냐"
-- 인데, 이 테이블에는 remote 이름도 pull 전략도 clone URL 도 없어서 재시도에 필요한
-- 원래 요청 자체가 남아있지 않다. 재시도가 요청을 저장하게 되면 그때 함께 정한다.
ALTER TABLE operation_history ADD COLUMN error_code TEXT;
