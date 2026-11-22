-- Sliding Window rate limiting, executed atomically inside Redis.
--
-- Redis-side mirror of SlidingWindow#checkLimit: expired timestamps are
-- pruned, the surviving count is compared against the limit, and the new
-- timestamp is appended - all in one atomic Lua invocation.
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

local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)

local raw = redis.call('GET', key)
local state = {}
if raw then
    state = cjson.decode(raw)
end

local existing = state.requestTimestamps or {}
local windowStart = now - windowMillis

-- Drop every request that fell out of the sliding window.
local kept = {}
for i = 1, #existing do
    local ts = tonumber(existing[i])
    if ts ~= nil and ts > windowStart then
        kept[#kept + 1] = ts
    end
end

local allowed = 0
if #kept < limit then
    allowed = 1
    kept[#kept + 1] = now
end

local oldest = kept[1]

state.userId = userId
state.component = component
state.requestCount = #kept
state.windowStartTimestamp = tonumber(state.windowStartTimestamp) or 0
state.availableTokens = tonumber(state.availableTokens) or 0
state.lastRefillTimestamp = tonumber(state.lastRefillTimestamp) or 0

if #kept > 0 then
    state.requestTimestamps = kept
else
    state.requestTimestamps = nil
end

local ttlMillis = windowMillis * 2 + 1000
redis.call('SET', key, cjson.encode(state), 'PX', ttlMillis)

local remaining = limit - #kept
if remaining < 0 then
    remaining = 0
end

-- The next slot frees up when the oldest in-window request expires.
local retryAfterMillis = 0
if allowed == 0 and oldest ~= nil then
    retryAfterMillis = (oldest + windowMillis) - now
    if retryAfterMillis < 0 then
        retryAfterMillis = 0
    end
end

return { allowed, remaining, retryAfterMillis }