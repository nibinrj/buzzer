-- Raises one player's total on a session's leaderboard and reports the top of it, in one atomic step.
-- Redis is a copy here, rebuilt from Postgres whenever it's missing; Postgres (player_scores) holds the truth.
--
-- KEYS[1]  leaderboard:{S}    sorted set: member = playerId, score = the player's total points
-- ARGV[1]  playerId
-- ARGV[2]  the player's total, as just read from Postgres (an absolute value, not an increment)
-- ARGV[3]  time-to-live of the key, in seconds
-- ARGV[4]  how many to return from the top (10)
--
-- Returns {'MISSING'} if the key doesn't exist: expired, or lost with Redis. The caller rebuilds it from Postgres
-- and calls again. Adding just this player to a missing key would leave a leaderboard of one, which would look
-- complete. Otherwise {'OK', position, player1, points1, player2, points2, ...}: position is the player's 0-based
-- place (0 = first), followed by the top, best first. Points come back as strings (WITHSCORES).

if redis.call('EXISTS', KEYS[1]) == 0 then
    return { 'MISSING' }
end

-- GT: only ever raise an existing member's score (new members are still added). Totals only grow, so a copy that
-- arrives late, or twice, can never lower a player's score; the order of these writes doesn't matter.
redis.call('ZADD', KEYS[1], 'GT', ARGV[2], ARGV[1])
redis.call('EXPIRE', KEYS[1], ARGV[3])

local position = redis.call('ZREVRANK', KEYS[1], ARGV[1])
-- Best first. Equal scores come in reverse lexicographic order of the member (the playerId).
local top = redis.call('ZREVRANGE', KEYS[1], 0, tonumber(ARGV[4]) - 1, 'WITHSCORES')

local reply = { 'OK', position }
for i = 1, #top do
    reply[#reply + 1] = top[i]
end
return reply
