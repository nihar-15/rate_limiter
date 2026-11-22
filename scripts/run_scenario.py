#!/usr/bin/env python3
"""
Run a named rate limiting experiment end to end.

A scenario configures the rules it needs through the admin API, resets the
relevant Redis state, generates the traffic and prints what to expect. Scenarios
live in scripts/scenarios.json - add your own there, no code change needed.

Examples
--------
python scripts/run_scenario.py --list
python scripts/run_scenario.py sequential-limit
python scripts/run_scenario.py concurrent-same-user --base-url http://localhost:8080
python scripts/run_scenario.py --all

Standard library only.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from load_test import report, run  # noqa: E402

DEFAULT_BASE_URL = os.environ.get("RATELIMIT_BASE_URL", "http://localhost:8080")
SCENARIOS_FILE = Path(__file__).resolve().parent / "scenarios.json"


def call(base_url, method, path, body=None, timeout=10.0):
    """Minimal admin API client. Returns (status, parsed_body)."""

    data = json.dumps(body).encode() if body is not None else None

    request = urllib.request.Request(base_url + path, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data:
        request.add_header("Content-Type", "application/json")

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as err:
        raw = err.read()
        try:
            return err.code, json.loads(raw) if raw else None
        except ValueError:
            return err.code, None


def ensure_rule(base_url, rule):
    """Create the rule, or replace it if it already exists."""

    status, _ = call(base_url, "PUT", "/api/admin/rules?resetState=true", rule)

    if status == 404:
        status, body = call(base_url, "POST", "/api/admin/rules?resetState=true", rule)
        if status != 201:
            raise SystemExit(f"Could not create rule {rule}: HTTP {status} {body}")
        action = "created"
    elif status == 200:
        action = "updated"
    else:
        raise SystemExit(f"Could not configure rule {rule}: HTTP {status}")

    print(f"  {action}: {rule['plan']:<8} {rule['component']:<13}"
          f" {rule['limit']:>4} / {rule['windowInSeconds']}s  {rule['algorithm']}")


def reset_state(base_url, user_id, component):
    status, body = call(base_url, "DELETE", f"/api/admin/state/{user_id}/{component}")
    if status == 403:
        print(f"  ! state reset refused: {body.get('message') if body else ''}")
    return status


def check_app(base_url):
    try:
        status, _ = call(base_url, "GET", "/api/admin/rules", timeout=5.0)
    except Exception as err:                                   # noqa: BLE001
        raise SystemExit(
            f"Cannot reach the application at {base_url} ({err}).\n"
            f"Start it with:  cd nihar && ./gradlew bootRun")

    if status == 401:
        raise SystemExit(
            f"{base_url} requires authentication. These scripts target the local "
            f"playground profile; start the app without an explicit profile.")


def run_scenario(name, scenario, base_url, quiet):
    print()
    print("#" * 70)
    print(f"# SCENARIO: {name}")
    print(f"# {scenario['description']}")
    print("#" * 70)

    rules = scenario.get("rules", [])
    if rules:
        print("\nConfiguring rules:")
        for rule in rules:
            ensure_rule(base_url, rule)

    resets = scenario.get("reset", [])
    if resets:
        print("\nResetting state:")
        for user_id, component in resets:
            reset_state(base_url, user_id, component)
            print(f"  cleared {user_id} / {component}")

    totals = {"allowed": 0, "limited": 0, "failed": 0}

    for index, phase in enumerate(scenario["phases"], start=1):

        if phase.get("sleep"):
            print(f"\n  ... waiting {phase['sleep']}s ...")
            time.sleep(phase["sleep"])

        print(f"\n--- Phase {index}: {phase['name']} ---")

        users = phase.get("users")

        if users:
            if phase.get("resetUsers"):
                component = rules[0]["component"] if rules else None
                if component:
                    for user in users:
                        reset_state(base_url, user, component)
            results = run_spread(base_url, phase, users, quiet)
        else:
            config = phase_config(base_url, phase)
            results, wall = run(config, quiet=quiet)
            totals["failed"] += report(config, results, wall)
            tally(results, totals)
            continue

        tally(results, totals)

    print(f"\nExpected: {scenario['expect']}")
    print(f"Observed overall: allowed={totals['allowed']} "
          f"limited={totals['limited']} failed={totals['failed']}")

    return totals


def phase_config(base_url, phase, user=None):
    return {
        "url": base_url + phase["url"],
        "method": "GET" if phase["url"].rstrip("/").endswith("/status") else "POST",
        "user": user or phase.get("user", "user-101"),
        "plan": phase.get("plan", "FREE"),
        "requests": int(phase.get("requests", 1)),
        "delay": float(phase.get("delay", 0.0)),
        "workers": int(phase.get("workers", 1)),
        "timeout": float(phase.get("timeout", 10.0)),
        "body": phase.get("body"),
    }


def run_spread(base_url, phase, users, quiet):
    """Spread one phase's requests across several user ids, concurrently."""

    from concurrent.futures import ThreadPoolExecutor
    from load_test import send

    total = int(phase.get("requests", len(users)))
    workers = int(phase.get("workers", 1))

    configs = [phase_config(base_url, phase, users[i % len(users)])
               for i in range(total)]

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        results = list(pool.map(lambda pair: send(pair[1], pair[0]),
                                list(enumerate(configs))))
    wall = time.perf_counter() - started

    allowed = sum(1 for r in results if r.status == 200)
    limited = sum(1 for r in results if r.status == 429)

    per_user = {}
    for config, result in zip(configs, results):
        if result.status == 200:
            per_user[config["user"]] = per_user.get(config["user"], 0) + 1

    print(f"  users={len(users)} requests={total} workers={workers}")
    print(f"  allowed={allowed} limited={limited} wall={wall:.3f}s")
    print(f"  allowed per user: "
          + ", ".join(f"{u}={per_user.get(u, 0)}" for u in users))

    return results


def tally(results, totals):
    totals["allowed"] += sum(1 for r in results if r.status == 200)
    totals["limited"] += sum(1 for r in results if r.status == 429)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Run a named rate limiting experiment.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)

    parser.add_argument("scenario", nargs="?", help="scenario name")
    parser.add_argument("--list", action="store_true", help="list available scenarios")
    parser.add_argument("--all", action="store_true", help="run every scenario in order")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help=f"application base URL (default {DEFAULT_BASE_URL})")
    parser.add_argument("--file", default=str(SCENARIOS_FILE),
                        help="scenario definition file")
    parser.add_argument("--quiet", action="store_true",
                        help="only print phase summaries")

    args = parser.parse_args(argv)

    with open(args.file, encoding="utf-8") as handle:
        scenarios = {k: v for k, v in json.load(handle).items()
                     if not k.startswith("_")}

    if args.list or (not args.scenario and not args.all):
        print("Available scenarios:\n")
        for name, scenario in scenarios.items():
            print(f"  {name:<28} {scenario['description']}")
        print(f"\nRun one with:  python {Path(__file__).name} <name>")
        return 0

    base_url = args.base_url.rstrip("/")
    check_app(base_url)

    names = list(scenarios) if args.all else [args.scenario]

    for name in names:
        if name not in scenarios:
            raise SystemExit(
                f"Unknown scenario '{name}'. Use --list to see the available ones.")
        run_scenario(name, scenarios[name], base_url, args.quiet)

    return 0


if __name__ == "__main__":
    sys.exit(main())
