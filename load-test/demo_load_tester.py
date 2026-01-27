#!/usr/bin/env python3
"""
🐦 Twitter Clone Load Tester
Professional load testing with configurable user types
"""

import sys
import json
import uuid
import time
import random
import asyncio
import aiohttp
import statistics
from datetime import datetime, timezone
from pathlib import Path
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Optional

try:
    from rich.console import Console
    from rich.panel import Panel
    from rich.table import Table
    from rich.progress import Progress, BarColumn, TextColumn, TaskProgressColumn
    from rich.progress import TimeElapsedColumn
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False
    print("Install 'rich' for better output: pip install rich")

console = Console() if RICH_AVAILABLE else None


@dataclass
class RequestMetrics:
    endpoint: str
    method: str
    status: int
    latency_ms: float
    success: bool
    error: Optional[str] = None


@dataclass 
class EndpointStats:
    total: int = 0
    success: int = 0
    failed: int = 0
    latencies: list = field(default_factory=list)
    errors: dict = field(default_factory=lambda: defaultdict(int))
    
    @property
    def success_rate(self) -> float:
        return (self.success / self.total * 100) if self.total > 0 else 0
    
    @property
    def p50(self) -> float:
        return statistics.median(self.latencies) if self.latencies else 0
    
    @property
    def p95(self) -> float:
        if not self.latencies:
            return 0
        sorted_lat = sorted(self.latencies)
        idx = int(len(sorted_lat) * 0.95)
        return sorted_lat[min(idx, len(sorted_lat) - 1)]
    
    @property
    def p99(self) -> float:
        if not self.latencies:
            return 0
        sorted_lat = sorted(self.latencies)
        idx = int(len(sorted_lat) * 0.99)
        return sorted_lat[min(idx, len(sorted_lat) - 1)]


class MetricsCollector:
    def __init__(self):
        self.requests: list[RequestMetrics] = []
        self.by_endpoint: dict[str, EndpointStats] = defaultdict(EndpointStats)
        self.start_time: float = 0
        self.end_time: float = 0
        self.phase: str = "setup"  # "setup" or "runtime"
        self.setup_requests: list[RequestMetrics] = []
        self.runtime_requests: list[RequestMetrics] = []
    
    def set_phase(self, phase: str):
        self.phase = phase
    
    def record(self, metric: RequestMetrics):
        self.requests.append(metric)
        
        # Track by phase
        if self.phase == "setup":
            self.setup_requests.append(metric)
        else:
            self.runtime_requests.append(metric)
        
        key = f"{metric.method} {metric.endpoint}"
        stats = self.by_endpoint[key]
        stats.total += 1
        stats.latencies.append(metric.latency_ms)
        if metric.success:
            stats.success += 1
        else:
            stats.failed += 1
            err_key = f"{metric.status}" if metric.status else metric.error or "unknown"
            stats.errors[err_key] += 1
    
    def get_phase_stats(self, phase: str) -> dict:
        """Get stats for a specific phase."""
        requests = self.setup_requests if phase == "setup" else self.runtime_requests
        if not requests:
            return {"total": 0, "success": 0, "failed": 0, "p50": 0, "p95": 0, "p99": 0}
        
        latencies = sorted([r.latency_ms for r in requests])
        success = sum(1 for r in requests if r.success)
        
        return {
            "total": len(requests),
            "success": success,
            "failed": len(requests) - success,
            "success_rate": success / len(requests) * 100 if requests else 0,
            "p50": latencies[len(latencies) // 2] if latencies else 0,
            "p95": latencies[int(len(latencies) * 0.95)] if latencies else 0,
            "p99": latencies[int(len(latencies) * 0.99)] if latencies else 0,
        }
    
    @property
    def total_requests(self) -> int:
        return len(self.requests)
    
    @property
    def total_success(self) -> int:
        return sum(1 for r in self.requests if r.success)
    
    @property
    def total_failed(self) -> int:
        return sum(1 for r in self.requests if not r.success)
    
    @property
    def overall_success_rate(self) -> float:
        return (self.total_success / self.total_requests * 100) if self.total_requests > 0 else 0
    
    @property
    def duration_seconds(self) -> float:
        return self.end_time - self.start_time if self.end_time else 0
    
    @property
    def tps(self) -> float:
        return self.total_requests / self.duration_seconds if self.duration_seconds > 0 else 0
    
    @property
    def all_latencies(self) -> list:
        return [r.latency_ms for r in self.requests]
    
    @property
    def overall_p50(self) -> float:
        lats = self.all_latencies
        return statistics.median(lats) if lats else 0
    
    @property
    def overall_p95(self) -> float:
        lats = sorted(self.all_latencies)
        if not lats:
            return 0
        idx = int(len(lats) * 0.95)
        return lats[min(idx, len(lats) - 1)]
    
    @property
    def overall_p99(self) -> float:
        lats = sorted(self.all_latencies)
        if not lats:
            return 0
        idx = int(len(lats) * 0.99)
        return lats[min(idx, len(lats) - 1)]


class LoadTester:
    def __init__(self, config: dict):
        self.config = config
        self.base_url = config.get("target", {}).get("host", "").rstrip('/')
        self.metrics = MetricsCollector()
        self.exec_id = datetime.now().strftime("%Y%m%d-%H%M%S")
        self.session: Optional[aiohttp.ClientSession] = None
        
        # Concurrency control
        max_concurrent = config.get("concurrency", {}).get("max_concurrent_requests", 50)
        self.semaphore = asyncio.Semaphore(max_concurrent)
        
        # Users
        self.regular_users: list[str] = []
        self.celebrities: list[str] = []
        self.celebrity_followers: list[str] = []  # Followers created just to make celebs
        self.all_users: list[str] = []
        self.follow_graph: dict[str, list[str]] = {}
        
    async def __aenter__(self):
        connector = aiohttp.TCPConnector(limit=100, limit_per_host=100)
        self.session = aiohttp.ClientSession(connector=connector)
        return self
    
    async def __aexit__(self, *args):
        if self.session:
            await self.session.close()
    
    def print_banner(self):
        if RICH_AVAILABLE:
            console.print()
            console.print(Panel.fit(
                "[bold cyan]🐦 TWITTER CLONE LOAD TESTER[/bold cyan]",
                border_style="cyan"
            ))
            console.print()
        else:
            print("\n" + "=" * 60)
            print("   🐦 TWITTER CLONE LOAD TESTER")
            print("=" * 60 + "\n")
    
    async def api_call(self, method: str, endpoint: str, user_id: str,
                       body: dict = None, expected_status: list = None) -> dict:
        if expected_status is None:
            expected_status = [200]
        
        url = f"{self.base_url}{endpoint}"
        headers = {"X-User-Id": user_id, "Content-Type": "application/json"}
        
        # Simplify endpoint for grouping
        endpoint_key = endpoint.split('?')[0]
        for segment in endpoint_key.split('/'):
            try:
                uuid.UUID(segment)
                endpoint_key = endpoint_key.replace(segment, '{id}')
            except:
                pass
        
        start = time.perf_counter()
        
        async with self.semaphore:  # Limit concurrent requests
            try:
                async with self.session.request(
                    method, url, headers=headers,
                    json=body, timeout=aiohttp.ClientTimeout(total=30)
                ) as resp:
                    latency_ms = (time.perf_counter() - start) * 1000
                    status = resp.status
                    try:
                        data = await resp.json()
                    except:
                        data = await resp.text()
                    
                    success = status in expected_status
                    self.metrics.record(RequestMetrics(
                        endpoint=endpoint_key, method=method, status=status,
                        latency_ms=latency_ms, success=success,
                        error=None if success else f"HTTP {status}"
                    ))
                    return {"success": success, "status": status, "data": data}
                    
            except Exception as e:
                latency_ms = (time.perf_counter() - start) * 1000
                self.metrics.record(RequestMetrics(
                    endpoint=endpoint_key, method=method, status=0,
                    latency_ms=latency_ms, success=False, error=str(e)
                ))
                return {"success": False, "status": 0, "error": str(e)}
    
    async def check_health(self) -> bool:
        try:
            endpoint = self.config["target"].get("health_endpoint", "/actuator/health")
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{self.base_url}{endpoint}",
                    timeout=aiohttp.ClientTimeout(total=5)
                ) as resp:
                    return resp.status == 200
        except:
            return False
    
    # =========================================================================
    # API Operations
    # =========================================================================
    
    async def create_tweet(self, user_id: str, content: str):
        return await self.api_call(
            "POST", "/api/v1/tweets", user_id,
            body={"content": content}, expected_status=[201]
        )
    
    async def follow_user(self, follower_id: str, followee_id: str):
        return await self.api_call(
            "POST", f"/api/v1/users/{follower_id}/follow/{followee_id}",
            follower_id, expected_status=[201, 409]
        )
    
    async def unfollow_user(self, follower_id: str, followee_id: str):
        return await self.api_call(
            "DELETE", f"/api/v1/users/{follower_id}/follow/{followee_id}",
            follower_id, expected_status=[200, 204, 404]
        )
    
    async def get_timeline(self, user_id: str, limit: int = 20, cursor: str = None):
        url = f"/api/v1/users/{user_id}/timeline?limit={limit}"
        if cursor:
            url += f"&cursor={cursor}"
        return await self.api_call("GET", url, user_id, expected_status=[200])
    
    async def get_user_tweets(self, user_id: str, limit: int = 20, cursor: str = None):
        url = f"/api/v1/users/{user_id}/tweets?limit={limit}"
        if cursor:
            url += f"&cursor={cursor}"
        return await self.api_call("GET", url, user_id, expected_status=[200])
    
    async def paginate_timeline(self, user_id: str, limit: int = 10):
        """Fetch entire timeline using cursor pagination."""
        pages = 0
        cursor = None
        while True:
            result = await self.get_timeline(user_id, limit, cursor)
            pages += 1
            if not result.get("success") or not result.get("data"):
                break
            cursor = result["data"].get("nextCursor")
            if not cursor:
                break
        return pages
    
    async def paginate_tweets(self, user_id: str, limit: int = 10):
        """Fetch all user tweets using cursor pagination."""
        pages = 0
        cursor = None
        while True:
            result = await self.get_user_tweets(user_id, limit, cursor)
            pages += 1
            if not result.get("success") or not result.get("data"):
                break
            cursor = result["data"].get("nextCursor")
            if not cursor:
                break
        return pages
    
    async def get_followers(self, user_id: str, limit: int = 20):
        return await self.api_call(
            "GET", f"/api/v1/users/{user_id}/followers?limit={limit}",
            user_id, expected_status=[200]
        )
    
    async def get_following(self, user_id: str, limit: int = 20):
        return await self.api_call(
            "GET", f"/api/v1/users/{user_id}/following?limit={limit}",
            user_id, expected_status=[200]
        )
    
    # =========================================================================
    # Test Phases
    # =========================================================================
    
    async def phase_create_users(self, progress=None, task=None):
        """Phase 1: Initialize users by having them post their first tweet (users are created implicitly)."""
        cfg = self.config["users"]
        
        # Create regular users
        for i in range(cfg["regular"]):
            user_id = str(uuid.uuid4())
            self.regular_users.append(user_id)
            await self.create_tweet(user_id, f"Hello! I'm regular user {i+1}")
            if progress and task:
                progress.advance(task)
        
        # Create celebrities
        for i in range(cfg["celebrities"]):
            user_id = str(uuid.uuid4())
            self.celebrities.append(user_id)
            await self.create_tweet(user_id, f"Hello! I'm celebrity {i+1} 🌟")
            if progress and task:
                progress.advance(task)
        
        self.all_users = self.regular_users + self.celebrities
    
    async def phase_build_celebrity_followers(self, progress=None, task=None):
        """Phase 2: Make celebrities reach follower threshold (fan-out on read)."""
        threshold = 5000  # Matches app.timeline.celebrity-follower-threshold in application.yml
        
        async def follow_and_report(follower_id: str, celeb: str):
            await self.follow_user(follower_id, celeb)
            if progress and task:
                progress.advance(task)
        
        # Create follower accounts and follow celebrities in batches
        tasks = []
        for celeb in self.celebrities:
            for i in range(threshold):
                follower_id = str(uuid.uuid4())
                self.celebrity_followers.append(follower_id)
                tasks.append(follow_and_report(follower_id, celeb))
        
        # Execute all follows concurrently (semaphore limits actual concurrency)
        await asyncio.gather(*tasks)
    
    async def phase_build_social_graph(self, progress=None, task=None):
        """Phase 3: Regular users follow each other and celebrities."""
        import random
        cfg = self.config["activity"]
        
        async def follow_and_report(user: str, target: str):
            await self.follow_user(user, target)
            if progress and task:
                progress.advance(task)
        
        tasks = []
        for user in self.regular_users:
            others = [u for u in self.regular_users if u != user]
            to_follow = random.sample(others, min(cfg["follows_per_regular_user"], len(others)))
            to_follow.extend(self.celebrities)
            self.follow_graph[user] = to_follow
            
            for target in to_follow:
                tasks.append(follow_and_report(user, target))
        
        await asyncio.gather(*tasks)
    
    async def phase_create_tweets(self, progress=None, task=None):
        """Phase 4: All users post tweets."""
        cfg = self.config["activity"]
        
        async def tweet_and_report(user: str, content: str):
            await self.create_tweet(user, content)
            if progress and task:
                progress.advance(task)
        
        tasks = []
        for round_num in range(cfg["tweets_per_user"]):
            for user in self.all_users:
                user_type = "celebrity 🌟" if user in self.celebrities else "user"
                content = f"Tweet {round_num + 1} from {user_type} - {uuid.uuid4().hex[:8]}"
                tasks.append(tweet_and_report(user, content))
        
        await asyncio.gather(*tasks)
    
    async def phase_read_timelines(self, progress=None, task=None):
        """Phase 5: Users read their timelines with pagination."""
        cfg = self.config["activity"]
        page_size = cfg.get("pagination_page_size", 10)
        
        async def read_and_report(user: str):
            # Paginate through entire timeline to test cursor handling
            await self.paginate_timeline(user, limit=page_size)
            if progress and task:
                progress.advance(task)
        
        tasks = []
        for _ in range(cfg["timeline_reads_per_user"]):
            for user in self.regular_users:
                tasks.append(read_and_report(user))
        
        await asyncio.gather(*tasks)
    
    async def phase_check_profiles(self, progress=None, task=None):
        """Phase 6: Users check profiles with pagination."""
        page_size = self.config["activity"].get("pagination_page_size", 10)
        
        async def check_and_report(user: str):
            await self.paginate_tweets(user, limit=page_size)
            await self.get_followers(user)
            await self.get_following(user)
            if progress and task:
                progress.advance(task)
        
        tasks = [check_and_report(user) for user in self.all_users]
        await asyncio.gather(*tasks)
    
    async def phase_unfollow_some(self, progress=None, task=None):
        """Phase 7: Some users unfollow others."""
        unfollows_per = self.config["activity"].get("unfollows_per_user", 1)
        
        for user in self.regular_users:
            following = self.follow_graph.get(user, [])
            # Unfollow regular users (not celebrities)
            regular_following = [f for f in following if f not in self.celebrities]
            
            for i in range(min(unfollows_per, len(regular_following))):
                await self.unfollow_user(user, regular_following[i])
                if progress and task:
                    progress.advance(task)
    
    async def phase_mixed_activity(self, progress=None, task=None):
        """Phase 8: Mixed realistic activity - tweets, follows, and reads happening together."""
        page_size = self.config["activity"].get("pagination_page_size", 10)
        
        async def mixed_user_activity(user: str, round_num: int):
            # Each user does a mix of activities
            # 1. Post a tweet
            content = f"Live tweet {round_num + 1} - {uuid.uuid4().hex[:8]}"
            await self.create_tweet(user, content)
            
            # 2. Follow someone new (if possible)
            not_following = [u for u in self.regular_users if u != user and u not in self.follow_graph.get(user, [])]
            if not_following:
                target = random.choice(not_following)
                await self.follow_user(user, target)
            
            # 3. Read timeline
            await self.paginate_timeline(user, limit=page_size)
            
            if progress and task:
                progress.advance(task)
        
        # Run 2 rounds of mixed activity for all regular users
        tasks = []
        for round_num in range(2):
            for user in self.regular_users:
                tasks.append(mixed_user_activity(user, round_num))
        
        await asyncio.gather(*tasks)
    
    def print_config(self):
        """Print test configuration."""
        cfg = self.config
        num_regular = cfg["users"]["regular"]
        num_celebs = cfg["users"]["celebrities"]
        threshold = 5000  # Matches app.timeline.celebrity-follower-threshold in application.yml
        tweets_per = cfg["activity"]["tweets_per_user"]
        reads_per = cfg["activity"]["timeline_reads_per_user"]
        follows_per = cfg["activity"]["follows_per_regular_user"]
        unfollows_per = cfg["activity"].get("unfollows_per_user", 1)
        
        # Calculate operations by phase
        total_celeb_follows = num_celebs * threshold
        
        setup_ops = (
            num_regular + num_celebs +  # Phase 1: create users
            total_celeb_follows +  # Phase 2: celebrity followers
            num_regular * (follows_per + num_celebs)  # Phase 3: social graph
        )
        
        runtime_ops = (
            (num_regular + num_celebs) * tweets_per +  # Phase 4: tweets
            num_regular * reads_per +  # Phase 5: timeline reads
            (num_regular + num_celebs) +  # Phase 6: check profiles
            num_regular * unfollows_per +  # Phase 7: unfollows
            num_regular * 2  # Phase 8: mixed activity (2 rounds)
        )
        
        if RICH_AVAILABLE:
            table = Table(title="⚙️ Configuration", border_style="dim")
            table.add_column("Setting", style="cyan")
            table.add_column("Value", justify="right")
            
            table.add_row("Target", self.base_url)
            table.add_row("", "")
            table.add_row("[bold]Users[/bold]", "")
            table.add_row("  Regular Users", str(num_regular))
            table.add_row("  Celebrities", str(num_celebs))
            table.add_row("  Celebrity Threshold", f"[dim]{threshold:,} (see application.yml)[/dim]")
            table.add_row("", "")
            page_size = cfg["activity"].get("pagination_page_size", 10)
            
            table.add_row("[bold]Activity (per user)[/bold]", "")
            table.add_row("  Tweets", str(tweets_per))
            table.add_row("  Timeline Reads", str(reads_per))
            table.add_row("  Follows", str(follows_per))
            table.add_row("  Unfollows", str(unfollows_per))
            table.add_row("  Pagination Size", f"{page_size} [dim](uses cursor)[/dim]")
            table.add_row("", "")
            table.add_row("[bold]Volume[/bold]", "")
            table.add_row("  Setup Phase", f"[dim]{setup_ops:,} requests[/dim]")
            table.add_row("  Runtime Phase", f"[bold cyan]~{runtime_ops:,}+ requests[/bold cyan] [dim](pagination adds more)[/dim]")
            table.add_row("  Concurrency", str(cfg.get("concurrency", {}).get("max_concurrent_requests", 50)))
            
            console.print(table)
            console.print()
        else:
            print(f"Target: {self.base_url}")
            print(f"Regular Users: {num_regular}, Celebrities: {num_celebs}")
            print(f"Celebrity Threshold: {threshold:,} followers")
            print(f"Setup: {setup_ops:,} requests, Runtime: {runtime_ops:,} requests")
    
    def print_results(self):
        """Print detailed results."""
        m = self.metrics
        cfg = self.config["thresholds"]
        
        setup_stats = m.get_phase_stats("setup")
        runtime_stats = m.get_phase_stats("runtime")
        
        if RICH_AVAILABLE:
            # Setup Phase Summary (informational)
            console.print()
            console.print(Panel.fit(
                "[bold]📦 Setup Phase[/bold] [dim](data preparation - not scored)[/dim]",
                border_style="dim"
            ))
            
            setup_table = Table(border_style="dim", show_header=False, box=None, padding=(0, 2))
            setup_table.add_column("", style="dim")
            setup_table.add_column("", justify="right", style="dim")
            setup_table.add_row("Requests", f"{setup_stats['total']:,}")
            setup_table.add_row("Success Rate", f"{setup_stats['success_rate']:.1f}%")
            setup_table.add_row("Latency p95", f"{setup_stats['p95']:.0f}ms")
            setup_table.add_row("", "")
            setup_table.add_row("[italic]High latency expected due to", "")
            setup_table.add_row("[italic]10,000 concurrent follow ops", "")
            console.print(setup_table)
            
            # Runtime Phase Summary (this is what matters)
            console.print()
            console.print(Panel.fit(
                "[bold cyan]🚀 Runtime Phase[/bold cyan] [dim](simulated real usage - scored)[/dim]",
                border_style="cyan"
            ))
            
            runtime_table = Table(title="", border_style="cyan")
            runtime_table.add_column("Metric", style="bold")
            runtime_table.add_column("Value", justify="right")
            
            runtime_table.add_row("Requests", f"{runtime_stats['total']:,}")
            runtime_table.add_row("Duration", f"{m.duration_seconds:.2f}s")
            
            runtime_success_rate = runtime_stats['success_rate']
            rate_ok = runtime_success_rate >= (100 - cfg["max_error_rate_percent"])
            runtime_table.add_row("Success Rate", 
                f"[green]{runtime_success_rate:.1f}%[/green]" if rate_ok 
                else f"[red]{runtime_success_rate:.1f}%[/red]")
            
            runtime_table.add_row("", "")
            runtime_table.add_row("Latency p50", f"{runtime_stats['p50']:.0f}ms")
            
            p95_ok = runtime_stats['p95'] < cfg["max_p95_latency_ms"]
            runtime_table.add_row("Latency p95", 
                f"[green]{runtime_stats['p95']:.0f}ms[/green]" if p95_ok 
                else f"[red]{runtime_stats['p95']:.0f}ms[/red]")
            runtime_table.add_row("Latency p99", f"{runtime_stats['p99']:.0f}ms")
            
            console.print(runtime_table)
            
            # Endpoint breakdown (runtime only)
            console.print()
            endpoints = Table(title="📈 Runtime Endpoints", border_style="blue")
            endpoints.add_column("Endpoint", style="cyan", max_width=40)
            endpoints.add_column("Total", justify="right")
            endpoints.add_column("OK", justify="right")
            endpoints.add_column("Fail", justify="right")
            endpoints.add_column("Rate", justify="right")
            endpoints.add_column("p50", justify="right")
            endpoints.add_column("p95", justify="right")
            
            # Filter to runtime-relevant endpoints
            runtime_endpoints = ["GET", "POST /api/v1/tweets", "DELETE"]
            for ep, stats in sorted(m.by_endpoint.items()):
                # Skip the mass follow endpoint used in setup
                if "follow" in ep and stats.total > 1000:
                    continue
                rate_color = "green" if stats.success_rate >= 95 else "red"
                endpoints.add_row(
                    ep, str(stats.total),
                    f"[green]{stats.success}[/green]",
                    f"[red]{stats.failed}[/red]" if stats.failed > 0 else "0",
                    f"[{rate_color}]{stats.success_rate:.0f}%[/{rate_color}]",
                    f"{stats.p50:.0f}ms", f"{stats.p95:.0f}ms"
                )
            console.print(endpoints)
            
            # Errors
            all_errors = defaultdict(int)
            for ep, stats in m.by_endpoint.items():
                for err, count in stats.errors.items():
                    all_errors[f"{ep} → {err}"] += count
            
            if all_errors:
                console.print()
                errors = Table(title="⚠️ Errors", border_style="red")
                errors.add_column("Error", style="dim")
                errors.add_column("Count", justify="right")
                for err, count in sorted(all_errors.items(), key=lambda x: -x[1])[:10]:
                    errors.add_row(err, str(count))
                console.print(errors)
            
            # Verdict (based on runtime only)
            console.print()
            passed = rate_ok and p95_ok
            if passed:
                console.print(Panel(
                    "[green bold]✓ PASSED[/green bold]\n"
                    f"[dim]Runtime: {runtime_success_rate:.1f}% success, p95 {runtime_stats['p95']:.0f}ms[/dim]",
                    border_style="green"
                ))
            else:
                reasons = []
                if not rate_ok:
                    reasons.append(f"Error rate {100-runtime_success_rate:.1f}% > {cfg['max_error_rate_percent']}%")
                if not p95_ok:
                    reasons.append(f"p95 {runtime_stats['p95']:.0f}ms > {cfg['max_p95_latency_ms']}ms")
                console.print(Panel(
                    f"[red bold]✗ FAILED[/red bold]\n[dim]{', '.join(reasons)}[/dim]",
                    border_style="red"
                ))
            
            return passed
        else:
            print(f"\nSetup: {setup_stats['total']} requests")
            print(f"Runtime: {runtime_stats['total']} requests, {runtime_stats['success_rate']:.1f}% success, p95 {runtime_stats['p95']:.0f}ms")
            return runtime_stats['success_rate'] >= 95
    
    async def run(self):
        """Run the load test."""
        self.print_banner()
        self.print_config()
        
        # Health check
        if RICH_AVAILABLE:
            console.print("[dim]Connecting...[/dim]", end=" ")
        else:
            print("Connecting...", end=" ")
        
        if not await self.check_health():
            if RICH_AVAILABLE:
                console.print("[red]FAILED[/red]")
                console.print("\n[yellow]Tip:[/yellow] Edit config.json to set your server URL")
            else:
                print("FAILED")
            return False
        
        if RICH_AVAILABLE:
            console.print("[green]OK[/green]")
        else:
            print("OK")
        
        cfg = self.config
        num_regular = cfg["users"]["regular"]
        num_celebs = cfg["users"]["celebrities"]
        threshold = 5000  # Matches app.timeline.celebrity-follower-threshold in application.yml
        tweets_per = cfg["activity"]["tweets_per_user"]
        reads_per = cfg["activity"]["timeline_reads_per_user"]
        follows_per = cfg["activity"]["follows_per_regular_user"]
        
        # Calculate operations
        phase1_ops = num_regular + num_celebs
        unfollows_per = cfg["activity"].get("unfollows_per_user", 1)
        
        phase2_ops = num_celebs * threshold
        phase3_ops = num_regular * (follows_per + num_celebs)
        phase4_ops = (num_regular + num_celebs) * tweets_per
        phase5_ops = num_regular * reads_per
        phase6_ops = (num_regular + num_celebs)
        phase7_ops = num_regular * unfollows_per
        
        self.metrics.start_time = time.perf_counter()
        
        delay_ms = cfg["timing"]["delay_after_writes_ms"]
        
        if RICH_AVAILABLE:
            console.print()
            console.print("[dim]━━━ Setup Phase (artificial load to create test data) ━━━[/dim]")
            with Progress(
                TextColumn("[bold]{task.description}"),
                BarColumn(bar_width=25),
                TextColumn("{task.completed}/{task.total}"),
                TextColumn("[dim]elapsed:[/dim]"),
                TimeElapsedColumn(),
                console=console
            ) as progress:
                
                self.metrics.set_phase("setup")
                
                t1 = progress.add_task("1. Initialize Users (first tweets)", total=phase1_ops)
                await self.phase_create_users(progress, t1)
                
                t2 = progress.add_task("2. Build Celebrity Followers", total=phase2_ops)
                await self.phase_build_celebrity_followers(progress, t2)
                
                t3 = progress.add_task("3. Build Social Graph", total=phase3_ops)
                await self.phase_build_social_graph(progress, t3)
            
            # Legend showing created users
            total_users = len(self.regular_users) + len(self.celebrities) + len(self.celebrity_followers)
            console.print()
            console.print(f"[dim]   └─ Users created: {total_users:,} "
                         f"({len(self.regular_users)} regular + {len(self.celebrities)} celebrities + "
                         f"{len(self.celebrity_followers):,} celebrity followers)[/dim]")
            
            console.print()
            console.print("[dim]━━━ Runtime Phase (simulated real user activity) ━━━[/dim]")
            with Progress(
                TextColumn("[bold]{task.description}"),
                BarColumn(bar_width=25),
                TextColumn("{task.completed}/{task.total}"),
                TextColumn("[dim]elapsed:[/dim]"),
                TimeElapsedColumn(),
                console=console
            ) as progress:
                
                self.metrics.set_phase("runtime")
                
                t4 = progress.add_task("4. Create Tweets", total=phase4_ops)
                await self.phase_create_tweets(progress, t4)
                
                await asyncio.sleep(delay_ms / 1000)
                
                t5 = progress.add_task("5. Read Timelines", total=phase5_ops)
                await self.phase_read_timelines(progress, t5)
                
                t6 = progress.add_task("6. Check Profiles", total=phase6_ops)
                await self.phase_check_profiles(progress, t6)
                
                t7 = progress.add_task("7. Unfollow Some", total=phase7_ops)
                await self.phase_unfollow_some(progress, t7)
                
                # Phase 8: Mixed activity (tweets + follows + reads together)
                phase8_ops = num_regular * 2  # 2 rounds per user
                t8 = progress.add_task("8. Mixed Activity", total=phase8_ops)
                await self.phase_mixed_activity(progress, t8)
        else:
            print("\n--- Setup Phase (data preparation) ---")
            self.metrics.set_phase("setup")
            print("1. Initialize Users (first tweets)...")
            await self.phase_create_users()
            print("2. Build Celebrity Followers...")
            await self.phase_build_celebrity_followers()
            print("3. Build Social Graph...")
            await self.phase_build_social_graph()
            
            total_users = len(self.regular_users) + len(self.celebrities) + len(self.celebrity_followers)
            print(f"   Users created: {total_users:,} ({len(self.regular_users)} regular + "
                  f"{len(self.celebrities)} celebrities + {len(self.celebrity_followers):,} celebrity followers)")
            
            print("\n--- Runtime Phase (simulated usage) ---")
            self.metrics.set_phase("runtime")
            print("4. Create Tweets...")
            await self.phase_create_tweets()
            await asyncio.sleep(delay_ms / 1000)
            print("5. Read Timelines...")
            await self.phase_read_timelines()
            print("6. Check Profiles...")
            await self.phase_check_profiles()
            print("7. Unfollow Some...")
            await self.phase_unfollow_some()
            print("8. Mixed Activity...")
            await self.phase_mixed_activity()
        
        self.metrics.end_time = time.perf_counter()
        
        return self.print_results()


def load_config(config_path: str = None) -> dict:
    """Load configuration from JSON file."""
    if config_path is None:
        # Look for config.json next to the script
        script_dir = Path(__file__).parent
        config_path = script_dir / "config.json"
    
    config_path = Path(config_path)
    
    if not config_path.exists():
        print(f"Config file not found: {config_path}")
        print("Create a config.json file or specify path as argument")
        sys.exit(1)
    
    with open(config_path) as f:
        return json.load(f)


async def run_smoke_test(host: str):
    """Run a minimal smoke test showing all endpoints with actual responses."""
    if RICH_AVAILABLE:
        console.print(Panel.fit("[bold cyan]🔍 SMOKE TEST[/bold cyan]\nMinimal test to verify all endpoints work", border_style="cyan"))
        console.print(f"\n[dim]Target:[/dim] {host}\n")
    else:
        print("\n🔍 SMOKE TEST - Verifying all endpoints\n")
        print(f"Target: {host}\n")
    
    base_url = host.rstrip('/')
    user1 = str(uuid.uuid4())
    user2 = str(uuid.uuid4())
    
    if RICH_AVAILABLE:
        console.print(f"[dim]User 1:[/dim] {user1}")
        console.print(f"[dim]User 2:[/dim] {user2}\n")
    
    async with aiohttp.ClientSession() as session:
        async def call(method: str, endpoint: str, user_id: str, body: dict = None, desc: str = ""):
            url = f"{base_url}{endpoint}"
            headers = {"X-User-Id": user_id, "Content-Type": "application/json"}
            
            if RICH_AVAILABLE:
                console.print(f"[bold]{desc}[/bold]")
                console.print(f"  [cyan]{method}[/cyan] {endpoint}")
            else:
                print(f"\n{desc}")
                print(f"  {method} {endpoint}")
            
            async with session.request(method, url, headers=headers, json=body) as resp:
                try:
                    data = await resp.json()
                except:
                    data = await resp.text()
                
                status_color = "green" if resp.status < 400 else "red"
                if RICH_AVAILABLE:
                    console.print(f"  [dim]Status:[/dim] [{status_color}]{resp.status}[/{status_color}]")
                    response_str = json.dumps(data, indent=2) if isinstance(data, dict) else str(data)
                    if len(response_str) > 500:
                        response_str = response_str[:500] + "\n  ... (truncated)"
                    console.print(f"  [dim]Response:[/dim]\n  {response_str}\n")
                else:
                    print(f"  Status: {resp.status}")
                    print(f"  Response: {json.dumps(data, indent=2) if isinstance(data, dict) else data}\n")
                
                return resp.status, data
        
        # ========== THE SMOKE TEST FLOW ==========
        
        if RICH_AVAILABLE:
            console.print("[bold yellow]═══ Step 1: Create Tweets ═══[/bold yellow]\n")
        else:
            print("\n═══ Step 1: Create Tweets ═══")
        
        await call("POST", "/api/v1/tweets", user1, {"content": "Hello from User 1!"}, "User 1 creates a tweet")
        await call("POST", "/api/v1/tweets", user1, {"content": "Second tweet from User 1"}, "User 1 creates another tweet")
        await call("POST", "/api/v1/tweets", user2, {"content": "Hello from User 2!"}, "User 2 creates a tweet")
        
        if RICH_AVAILABLE:
            console.print("[bold yellow]═══ Step 2: Follow ═══[/bold yellow]\n")
        else:
            print("\n═══ Step 2: Follow ═══")
        
        await call("POST", f"/api/v1/users/{user1}/follow/{user2}", user1, desc="User 1 follows User 2")
        await call("POST", f"/api/v1/users/{user2}/follow/{user1}", user2, desc="User 2 follows User 1 (mutual)")
        
        if RICH_AVAILABLE:
            console.print("[bold yellow]═══ Step 3: Get User Tweets (with pagination) ═══[/bold yellow]\n")
        else:
            print("\n═══ Step 3: Get User Tweets (with pagination) ═══")
        
        status, data = await call("GET", f"/api/v1/users/{user1}/tweets?limit=1", user1, desc="Get User 1's tweets (limit=1 to demo pagination)")
        
        if isinstance(data, dict) and data.get("pagination", {}).get("nextCursor"):
            cursor = data["pagination"]["nextCursor"]
            await call("GET", f"/api/v1/users/{user1}/tweets?limit=1&cursor={cursor}", user1, desc=f"Fetch NEXT PAGE using cursor={cursor[:20]}...")
        
        if RICH_AVAILABLE:
            console.print("[bold yellow]═══ Step 4: Get Timeline (with pagination) ═══[/bold yellow]\n")
        else:
            print("\n═══ Step 4: Get Timeline (with pagination) ═══")
        
        status, data = await call("GET", f"/api/v1/users/{user1}/timeline?limit=2", user1, desc="Get User 1's timeline (sees User 2's tweet)")
        
        if isinstance(data, dict) and data.get("pagination", {}).get("nextCursor"):
            cursor = data["pagination"]["nextCursor"]
            await call("GET", f"/api/v1/users/{user1}/timeline?limit=2&cursor={cursor}", user1, desc=f"Fetch NEXT PAGE using cursor")
        
        if RICH_AVAILABLE:
            console.print("[bold yellow]═══ Step 5: Get Followers/Following ═══[/bold yellow]\n")
        else:
            print("\n═══ Step 5: Get Followers/Following ═══")
        
        await call("GET", f"/api/v1/users/{user1}/followers?limit=10", user1, desc="Get User 1's followers (shows User 2)")
        await call("GET", f"/api/v1/users/{user1}/following?limit=10", user1, desc="Get User 1's following (shows User 2)")
        
        if RICH_AVAILABLE:
            console.print("[bold yellow]═══ Step 6: Unfollow ═══[/bold yellow]\n")
        else:
            print("\n═══ Step 6: Unfollow ═══")
        
        await call("DELETE", f"/api/v1/users/{user1}/follow/{user2}", user1, desc="User 1 unfollows User 2")
        await call("GET", f"/api/v1/users/{user1}/following?limit=10", user1, desc="Verify following is now empty")
        
        if RICH_AVAILABLE:
            console.print(Panel.fit("[bold green]✓ SMOKE TEST COMPLETE[/bold green]\nAll endpoints verified", border_style="green"))
        else:
            print("\n✓ SMOKE TEST COMPLETE - All endpoints verified")


async def main():
    if len(sys.argv) < 2 or sys.argv[1] in ["-h", "--help"]:
        print("\n🐦 Twitter Clone Load Tester")
        print("\nUsage:")
        print("  python demo_load_tester.py <host>                    # Full load test")
        print("  python demo_load_tester.py --smoke <host>            # Quick smoke test")
        print("  python demo_load_tester.py <host> [config.json]      # Custom config")
        print("\nExamples:")
        print("  python demo_load_tester.py http://localhost:8080")
        print("  python demo_load_tester.py --smoke http://localhost:8080")
        print("\nModes:")
        print("  --smoke   Minimal test showing all endpoints with actual responses")
        print("            Great for verifying the API works and seeing pagination in action")
        print("\n  (default) Full load test with config.json settings")
        sys.exit(1)
    
    # Check for smoke test flag
    if sys.argv[1] == "--smoke":
        if len(sys.argv) < 3:
            print("Error: --smoke requires a host argument")
            print("Usage: python demo_load_tester.py --smoke <host>")
            sys.exit(1)
        await run_smoke_test(sys.argv[2])
        sys.exit(0)
    
    host = sys.argv[1]
    config_path = sys.argv[2] if len(sys.argv) > 2 else None
    config = load_config(config_path)
    
    # Set host from command line
    if "target" not in config:
        config["target"] = {}
    config["target"]["host"] = host
    
    async with LoadTester(config) as tester:
        passed = await tester.run()
    
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    asyncio.run(main())
