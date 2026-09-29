-- Fills a session's leaderboard from Postgres's totals, when update_leaderboard.lua reported it MISSING (or a read
-- found it missing).
--
-- KEYS[1]  leaderboard:{S}
-- ARGV[1]  time-to-live of the key, in seconds
-- ARGV[2], ARGV[3]  playerId, total   (repeated for every player with a total)
--
-- ZADD GT again: two rebuilds racing each other, or a rebuild racing an update, can only ever end at the highest
-- total each player had. One script so the key never exists without its expiry.

for i = 2, #ARGV, 2 do
    redis.call('ZADD', KEYS[1], 'GT', ARGV[i + 1], ARGV[i])
end
redis.call('EXPIRE', KEYS[1], ARGV[1])
return (#ARGV - 1) / 2 -- players written
