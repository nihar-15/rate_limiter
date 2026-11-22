-- Token Bucket rate limiting, executed atomically inside Redis.
--
-- Redis-side mirror of TokenBucket#checkLimit: the bucket is lazily refilled
-- from the elapsed time, then a single token is consumed if one is available.
-- Refill + consume happen in one atomic Lua invocation, so concurrent
-- requests cannot both spend the same token.
--
-- Capacity is rule.limitCount and the bucket refills a full capacity over
-- rule.windowInSeconds, i.e. refillRate = capacity / windowMillis.
--
-- KEYS[1] : ratelimit:<userId>:<component>
-- ARGV[1] : limitCount (bucket capacity)
-- ARGV[2] : windowInSeconds (time to refill a full bucket)
-- ARGV[3] : userId
-- ARGV[4] : component
--
-- Returns  { allowed(0|1), remainingWholeTokens, retryAfterMillis }

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local windowSeconds = tonumber(ARGV[2])
local userId = ARGV[3]
local component = ARGV[4]

local windowMillis = windowSeconds * 1000

local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)

local raw = redis.call('GET', key)
local state = {}
if raw then
    state = cjson.decode(raw)
end

local timestamps = state.requestTimestamps
local tokens = tonumber(state.availableTokens) or 0
local lastRefill = tonumber(state.lastRefillTimestamp) or 0

-- A brand new bucket starts full.
if lastRefill == 0 then
    tokens = capacity
    lastRefill = now
end

local elapsed = now - lastRefill
if elapsed < 0 then
    elapsed = 0
end

local refillRate = capacity / windowMillis
tokens = math.min(capacity, tokens + (elapsed * refillRate))
lastRefill = now

local allowed = 0
if tokens >= 1.0 then
    allowed = 1
    tokens = tokens - 1
end

state.userId = userId
state.component = component
state.requestCount = tonumber(state.requestCount) or 0
state.windowStartTimestamp = tonumber(state.windowStartTimestamp) or 0
state.availableTokens = tokens
state.lastRefillTimestamp = lastRefill

if timestamps ~= nil and #timestamps > 0 then
    state.requestTimestamps = timestamps
else
    state.requestTimestamps = nil
end

-- An idle bucket is fully refilled after one window; two windows of TTL
-- means an expired key and a refilled bucket are indistinguishable.
local ttlMillis = windowMillis * 2 + 1000
redis.call('SET', key, cjson.encode(state), 'PX', ttlMillis)

local remaining = math.floor(tokens)

-- Time until the bucket accrues the fraction of a token still missing.
local retryAfterMillis = 0
if allowed == 0 then
    retryAfterMillis = math.ceil((1.0 - tokens) / refillRate)
    if retryAfterMillis < 0 then
        retryAfterMillis = 0
    end
end

return { allowed, remaining, retryAfterMillis }