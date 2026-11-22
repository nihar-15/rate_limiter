-- Fixed Window rate limiting, executed atomically inside Redis.
--
-- This is the Redis-side mirror of FixedWindow#checkLimit. The whole
-- read -> decide -> write cycle happens in a single Lua invocation, so two
-- concurrent requests for the same user can never both observe the same
-- pre-increment counter.
--
-- The state document stored under KEYS[1] is exactly the JSON produced by
-- RateLimitStateManager (a serialized UserStatus), so the existing Java
-- readers and the admin inspection APIs keep working unchanged.
--
-- KEYS[1] : ratelimit:<userId>:<component>
-- ARGV[1] : limitCount
-- ARGV[2] : windowInSeconds
-- ARGV[3] : userId
-- ARGV[4] : component
--
-- Returns  { allowed(0|1), remaining, retryAfterMillis }

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local windowSeconds = tonumber(ARGV[2])
local userId = ARGV[3]
local component = ARGV[4]

local windowMillis = windowSeconds * 1000

-- Redis server clock: a single clock source for every application instance.
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)

local raw = redis.call('GET', key)
local state = {}
if raw then
    state = cjson.decode(raw)
end

local timestamps = state.requestTimestamps
local requestCount = tonumber(state.requestCount) or 0
local windowStart = tonumber(state.windowStartTimestamp) or 0

-- First request, or the previous window has elapsed.
if windowStart == 0 or (now - windowStart) >= windowMillis then
    windowStart = now
    requestCount = 0
end

local allowed = 0
if requestCount < limit then
    allowed = 1
    requestCount = requestCount + 1
end

state.userId = userId
state.component = component
state.requestCount = requestCount
state.windowStartTimestamp = windowStart
state.availableTokens = tonumber(state.availableTokens) or 0
state.lastRefillTimestamp = tonumber(state.lastRefillTimestamp) or 0

-- cjson encodes an empty table as {} which would not deserialize into a
-- List<Long>; omitting the field lets UserStatus keep its empty default.
if timestamps ~= nil and #timestamps > 0 then
    state.requestTimestamps = timestamps
else
    state.requestTimestamps = nil
end

-- Keep the key alive for two windows so abandoned users do not leak forever.
local ttlMillis = windowMillis * 2 + 1000
redis.call('SET', key, cjson.encode(state), 'PX', ttlMillis)

local remaining = limit - requestCount
if remaining < 0 then
    remaining = 0
end

local retryAfterMillis = 0
if allowed == 0 then
    retryAfterMillis = (windowStart + windowMillis) - now
    if retryAfterMillis < 0 then
        retryAfterMillis = 0
    end
end

return { allowed, remaining, retryAfterMillis }
