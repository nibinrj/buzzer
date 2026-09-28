-- Decides one answer in the buzz race: accepted (with its place in line) or why not.
-- Redis runs a script start to finish with nothing in between, so the checks and the writes below are one step,
-- whichever session-service instance called it. Every check comes before the first write: Redis has no rollback,
-- and a rejected answer must leave no trace (no seq taken, so accepted seqs stay 1, 2, 3... without gaps).
--
-- KEYS[1]  session:{S}:state        hash: status, questionIndex, questionDeadline (epoch ms), questionOpen
-- KEYS[2]  buzz:{S}:Q               sorted set: member = playerId, score = seq
-- KEYS[3]  buzz:{S}:Q:seq           counter: arrival order among accepted answers
-- KEYS[4]  buzz:{S}:Q:correct       counter: order among accepted CORRECT answers
-- ({S} is the session id in literal braces: a Redis Cluster hash tag, so all four keys live on one node.)
--
-- ARGV[1]  playerId
-- ARGV[2]  questionIndex the answer is for
-- ARGV[3]  "1" if the chosen option is correct, else "0" (the app decides, from the session's stored questions)
-- ARGV[4]  time-to-live of the answer keys, in seconds
--
-- Returns {outcome, seq, correctRank, answeredAtMs}. seq is 0 unless ACCEPTED or DUPLICATE (then the player's
-- original seq); correctRank is 0 unless ACCEPTED and correct; answeredAtMs is Redis's TIME in epoch ms when
-- ACCEPTED (the same clock as the deadline), else 0. Never nil: a nil would cut the returned array short.

local state = redis.call('HMGET', KEYS[1], 'status', 'questionIndex', 'questionDeadline', 'questionOpen')
local status, index, deadline, open = state[1], state[2], state[3], state[4]
-- A missing field comes back as false, never nil.

-- 1. Is this question taking answers at all?
if status ~= 'IN_PROGRESS' then
    return { 'NOT_RUNNING', 0, 0, 0 }
end
if index ~= ARGV[2] then
    return { 'WRONG_QUESTION', 0, 0, 0 } -- an earlier question, or none running
end
if open ~= '1' or not deadline then
    return { 'CLOSED', 0, 0, 0 } -- revealed, or never opened: missing means closed
end

-- 2. One answer per player per question. Before the deadline check on purpose: a retry that arrives late
--    still learns that its first attempt counted, and with which seq.
local previous = redis.call('ZSCORE', KEYS[2], ARGV[1])
if previous then
    return { 'DUPLICATE', tonumber(previous), 0, 0 }
end

-- 3. The deadline, by Redis's own clock: the same clock that set it (RunSession via LiveStateRepository.serverTime).
--    TIME returns {seconds, microseconds}. An answer in the deadline's own millisecond still counts.
local time = redis.call('TIME')
local nowMs = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if nowMs > tonumber(deadline) then
    return { 'LATE', 0, 0, 0 }
end

-- 4. Accepted. Every write happens here, after every check.
local seq = redis.call('INCR', KEYS[3])
redis.call('ZADD', KEYS[2], seq, ARGV[1])
local correctRank = 0
if ARGV[3] == '1' then
    correctRank = redis.call('INCR', KEYS[4])
end
-- No key may outlive the game: refresh all three on every accepted answer.
redis.call('EXPIRE', KEYS[2], ARGV[4])
redis.call('EXPIRE', KEYS[3], ARGV[4])
redis.call('EXPIRE', KEYS[4], ARGV[4])
return { 'ACCEPTED', seq, correctRank, nowMs }
