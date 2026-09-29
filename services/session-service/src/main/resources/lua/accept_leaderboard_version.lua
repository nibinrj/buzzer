-- Decides whether a leaderboard may be pushed: only if it isn't older than the last one pushed for its session.
-- GET, compare and SET as one step, so two instances can't both pass the check with versions in the wrong order.
--
-- KEYS[1]  session:{S}:leaderboard-version    string: the version of the last leaderboard pushed
-- ARGV[1]  the new version (ScoreUpdated.version, a counter that only grows per session)
-- ARGV[2]  time-to-live of the key, in seconds (the live state's)
--
-- Returns 1 (push it, now stored as the last pushed) or 0 (older: skip it, nothing changed).
-- An EQUAL version is accepted: the same snapshot pushed twice is harmless, and refusing it could drop a
-- republication whose first attempt never reached anyone.

local last = redis.call('GET', KEYS[1])
if last and tonumber(ARGV[1]) < tonumber(last) then
    return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return 1
