#!/usr/bin/env python3
"""
Generate traffic against the rate limiter and report what it did.

Standard library only - no pip install required.

Examples
--------
# 20 sequential FREE requests against code-run, 100ms apart
python scripts/load_test.py \
    --url http://localhost:8080/api/demo/code-run \
    --user user-101 --plan FREE --requests 20 --delay 0.1

# 50 requests fired by 25 concurrent workers
python scripts/load_test.py \
    --url http://localhost:8080/api/demo/code-run \
    --user user-101 --plan FREE --requests 50 --workers 25

# Everything from a config file instead of flags
python scripts/load_test.py --config scripts/examples/free-burst.json

Exit code is 0 when the run completed, 1 when requests failed with something
other than a rate limit rejection (a rate limit rejection is a successful
observation, not an error).
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from statistics import mean

DEFAULTS = {
    "url": "http://localhost:8080/api/demo/code-run",
    "method": None,          # inferred from the URL when not given
    "user": "user-101",
    "plan": "FREE",
    "requests": 10,
    "delay": 0.0,
    "workers": 1,
    "timeout": 10.0,
    "body": None,
}

# Environment overrides, so a run can be configured without touching flags.
ENV_PREFIX = "RATELIMIT_"


class Result:
    """One request's outcome."""

    __slots__ = ("index", "status", "elapsed", "allowed", "remaining",
                 "retry_after", "component", "algorithm", "error")

    def __init__(self, index, status, elapsed, payload=None, headers=None, error=None):
        self.index = index
        self.status = status
        self.elapsed = elapsed
        self.error = error

        payload = payload or {}
        headers = headers or {}

        self.allowed = payload.get("allowed")
        self.remaining = payload.get("remaining")
        self.retry_after = payload.get("retryAfterSeconds")
        self.component = payload.get("component")
        self.algorithm = payload.get("algorithm")

        if self.remaining is None and "X-RateLimit-Remaining" in headers:
            self.remaining = headers["X-RateLimit-Remaining"]


def infer_method(url, explicit):
    if explicit:
        return explicit.upper()
    # The demo status endpoint is the only GET in the playground.
    return "GET" if url.rstrip("/").endswith("/status") else "POST"


def send(config, index):
    """Perform one request and time it."""

    data = None
    if config["body"]:
        data = json.dumps(config["body"]).encode()

    request = urllib.request.Request(
        config["url"],
        data=data,
        method=config["method"],
    )
    request.add_header("X-User-Id", config["user"])
    request.add_header("X-Subscription-Plan", config["plan"])
    request.add_header("Accept", "application/json")
    if data:
        request.add_header("Content-Type", "application/json")

    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=config["timeout"]) as response:
            raw = response.read()
            elapsed = time.perf_counter() - started
            return Result(index, response.status, elapsed,
                          parse(raw), dict(response.headers))

    except urllib.error.HTTPError as err:
        # 429 arrives here; it is an expected outcome, not a failure.
        raw = err.read()
        elapsed = time.perf_counter() - started
        return Result(index, err.code, elapsed, parse(raw), dict(err.headers))

    except Exception as err:                                  # noqa: BLE001
        elapsed = time.perf_counter() - started
        return Result(index, None, elapsed, error=f"{type(err).__name__}: {err}")


def parse(raw):
    if not raw:
        return {}
    try:
        payload = json.loads(raw)
    except (ValueError, TypeError):
        return {}
    # /api/demo/status nests the verdict under "rateLimit".
    if isinstance(payload, dict) and "rateLimit" in payload:
        return payload["rateLimit"]
    return payload if isinstance(payload, dict) else {}


def run(config, quiet=False):
    """Run the configured scenario and return the list of results."""

    total = config["requests"]
    workers = max(1, config["workers"])
    results = []

    started = time.perf_counter()

    if workers == 1:
        for index in range(total):
            result = send(config, index)
            results.append(result)
            if not quiet:
                print(line(result))
            if config["delay"] and index < total - 1:
                time.sleep(config["delay"])
    else:
        # Concurrent mode: the delay staggers submission, not completion.
        def task(index):
            if config["delay"]:
                time.sleep(config["delay"] * index / workers)
            return send(config, index)

        with ThreadPoolExecutor(max_workers=workers) as pool:
            results = list(pool.map(task, range(total)))

        if not quiet:
            for result in sorted(results, key=lambda r: r.index):
                print(line(result))

    wall = time.perf_counter() - started
    return results, wall


def line(result):
    if result.error:
        return f"  #{result.index + 1:<4} ERROR    {result.error}"

    verdict = "ALLOWED " if result.status == 200 else (
        "LIMITED " if result.status == 429 else "OTHER   ")

    extra = ""
    if result.remaining is not None:
        extra += f" remaining={result.remaining}"
    if result.retry_after:
        extra += f" retryAfter={result.retry_after}s"

    return (f"  #{result.index + 1:<4} {verdict} HTTP {result.status}"
            f" {result.elapsed * 1000:7.1f}ms{extra}")


def report(config, results, wall):
    allowed = [r for r in results if r.status == 200]
    limited = [r for r in results if r.status == 429]
    failed = [r for r in results if r.status not in (200, 429)]
    times = [r.elapsed for r in results if r.status is not None]

    algorithm = next((r.algorithm for r in results if r.algorithm), "n/a")
    component = next((r.component for r in results if r.component), "n/a")

    print()
    print("=" * 62)
    print(f" {config['method']} {config['url']}")
    print(f" user={config['user']}  plan={config['plan']}  "
          f"component={component}  algorithm={algorithm}")
    print(f" workers={config['workers']}  delay={config['delay']}s")
    print("-" * 62)
    print(f" Total requests sent      : {len(results)}")
    print(f" Allowed (HTTP 200)       : {len(allowed)}")
    print(f" Rate limited (HTTP 429)  : {len(limited)}")
    print(f" Other failures           : {len(failed)}")

    if failed:
        reasons = {}
        for r in failed:
            key = r.error or f"HTTP {r.status}"
            reasons[key] = reasons.get(key, 0) + 1
        for reason, count in sorted(reasons.items()):
            print(f"     - {reason}: {count}")

    print(f" Total execution time     : {wall:.3f}s")
    if times:
        print(f" Average response time    : {mean(times) * 1000:.1f}ms")
        print(f" Minimum response time    : {min(times) * 1000:.1f}ms")
        print(f" Maximum response time    : {max(times) * 1000:.1f}ms")
        if wall > 0:
            print(f" Throughput               : {len(results) / wall:.1f} req/s")
    print("=" * 62)

    return len(failed)


def load_config(args):
    """Merge, in increasing priority: defaults, config file, env, CLI flags."""

    config = dict(DEFAULTS)

    if args.config:
        with open(args.config, encoding="utf-8") as handle:
            config.update(json.load(handle))

    for key in DEFAULTS:
        env_key = ENV_PREFIX + key.upper()
        if env_key in os.environ:
            config[key] = os.environ[env_key]

    for key in DEFAULTS:
        value = getattr(args, key, None)
        if value is not None:
            config[key] = value

    config["requests"] = int(config["requests"])
    config["workers"] = int(config["workers"])
    config["delay"] = float(config["delay"])
    config["timeout"] = float(config["timeout"])
    config["method"] = infer_method(config["url"], config["method"])

    return config


def build_parser():
    parser = argparse.ArgumentParser(
        description="Drive traffic at the rate limiter and summarise the outcome.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)

    parser.add_argument("--config",
                        help="JSON file with any of the options below")
    parser.add_argument("--url",
                        help=f"target endpoint (default {DEFAULTS['url']})")
    parser.add_argument("--method",
                        help="HTTP method; inferred from the URL when omitted")
    parser.add_argument("--user",
                        help="value of the X-User-Id header")
    parser.add_argument("--plan",
                        help="value of the X-Subscription-Plan header")
    parser.add_argument("--requests", type=int,
                        help="number of requests to send")
    parser.add_argument("--delay", type=float,
                        help="seconds to wait between requests (sequential mode)")
    parser.add_argument("--workers", type=int,
                        help="number of concurrent workers; 1 means sequential")
    parser.add_argument("--timeout", type=float,
                        help="per-request timeout in seconds")
    parser.add_argument("--quiet", action="store_true",
                        help="only print the summary")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    config = load_config(args)

    if not args.quiet:
        print(f"Sending {config['requests']} request(s) to "
              f"{config['method']} {config['url']} as {config['user']}/{config['plan']}")

    results, wall = run(config, quiet=args.quiet)
    failures = report(config, results, wall)

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())