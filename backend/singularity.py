"""
╔══════════════════════════════════════════════════════════════════════════════╗
║  ARC — ADAPTIVE RISK CORE  ·  Singularity Engine v3.0                     ║
║  👑 Owners: Team Pheonex                                   ║
║  🏆 Hacksagon 2026  ·  App Development Track                               ║
╠══════════════════════════════════════════════════════════════════════════════╣
║                                                                             ║
║  THREE-TIER AI PIPELINE ARCHITECTURE                                       ║
║  ─────────────────────────────────────────────────────────────────────    ║
║  TIER 1 · Data + Math Scoring                                              ║
║    • Momentum Score   M  = (trades_5m / min_trades) × √(volume_5m)        ║
║    • Safety Score     S  = (1 − top10_pct/100) × clamp(holders/30, 0, 1)  ║
║    • Vitality Score   V  = (bonding_curve_pct/100) × ln(1+reply_count)    ║
║    • T1_composite         = 0.40·M + 0.40·S + 0.20·V  ∈ [0, 1]           ║
║    • Gate: T1_composite ≥ 0.30  (fail → skip, no API cost)                ║
║                                                                             ║
║  TIER 2 · Market Condition Scoring                                         ║
║    • Dynamic MC_min   = base_mc_sol × regime_multiplier                    ║
║                        (HOT: ×0.7  NEUTRAL: ×1.0  COLD: ×1.4)            ║
║    • Investor Score   I  = log₂(holder_count) / log₂(target_holders)      ║
║    • Liquidity Ratio  L  = dex_liquidity_usd / (market_cap_sol × sol_usd) ║
║    • Coin Age Check   A  = 1 if age ∈ [120s, 7200s] else 0                ║
║    • T2_composite         = 0.35·I + 0.35·L_norm + 0.30·A                 ║
║    • Gate: T2_composite ≥ 0.40  AND  market_cap_sol ≥ MC_min              ║
║                                                                             ║
║  TIER 3 · Gemini 2.5-Flash AI Reasoning                                   ║
║    • Full natural-language risk analysis (all T1/T2 data injected)        ║
║    • Outputs {buy: bool, confidence: 0–1, primary_risk: str}              ║
║    • Gate: buy == true AND confidence ≥ 0.85 (regime-adaptive)            ║
║    • Budget: ≤ 20 calls / day (tracked in CSV AI-log)                     ║
║                                                                             ║
║  PIPELINE ORDER (token rejected at first failing gate)                     ║
║  ─────────────────────────────────────────────────────────────────────    ║
║    New Token → social pre-filter → slot reservation                        ║
║    → Phase 1 wait (120s) → MC alive check                                  ║
║    → Phase 2 wait (120s) → MC still alive check                            ║
║    → Dual rug check (RugCheck + GoPlus, fail-closed)                      ║
║    → Dev reputation check (pumpportal.fun history)                         ║
║    → TIER 1 Math Score (free, zero network)                                ║
║    → TIER 2 Market Score + Dynamic MC_min + Coin Age                      ║
║    → Kelly position sizing + slippage gate                                 ║
║    → TIER 3 Gemini AI Score (confidence ≥ 0.85)                           ║
║    → Risk limits (daily loss / consecutive losses)                         ║
║    → EXECUTE BUY                                                            ║
║                                                                             ║
║  SAFETY SYSTEM                                                             ║
║  ─────────────────────────────────────────────────────────────────────    ║
║    • Kill switch: SIGINT / SIGTERM → graceful close_all_positions()        ║
║    • Hard 20-min time limit per position (force-exit regardless of price)  ║
║    • Trailing stop: 15% drop from ATH → exit                              ║
║    • Circuit breaker: 5 consecutive failures → 120s trading pause          ║
║    • Max daily loss: 0.15 SOL cap                                          ║
║    • Max consecutive losses: 3 → halt                                      ║
║    • atexit crash handler: marks OPEN positions in CSV on hard crash       ║
║    • Startup recovery: re-adopts CSV status=OPEN positions after restart   ║
║                                                                             ║
║  CSV LOG + REINFORCEMENT LEARNING                                          ║
║  ─────────────────────────────────────────────────────────────────────    ║
║    simulation_data.csv      — positions + P/L (paper mode)                ║
║    real_data.csv            — positions + P/L (live mode)                 ║
║    simulation_data_ailog.csv / real_data_ailog.csv  — Gemini call log     ║
║    Columns: status, trade_id, symbol, mint, entry_mc, ath_mc,             ║
║             investment_sol, entry_time, t1_score, t2_score, ai_confidence, ║
║             regime, exit_mc, exit_reason, hold_minutes,                   ║
║             gross_pnl_sol, net_pnl_sol, timestamp                         ║
║    → All trade data feeds back into DynamicConfig recalibration           ║
║    → RegimeTracker adjusts AI confidence gate per rolling 10-trade window  ║
╚══════════════════════════════════════════════════════════════════════════════╝

Run:
    python singularity.py            # simulation / paper mode (safe default)
    python singularity.py --live     # live trading (requires paper_mode=False)

Secrets in .env — never in code:
    GEMINI_API_KEY
    BOT_PRIVATE_KEY
    BOT_PUBLIC_KEY
    RPC_URL_1, RPC_URL_2, RPC_URL_3  (at least one required for live)
    TELEGRAM_TOKEN
    TELEGRAM_CHAT_ID
    ARC_API_KEY
"""

from __future__ import annotations

import argparse
import asyncio
import atexit
import csv
import json
import logging
import logging.handlers
import math
import os
import random
import re
import signal
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional, Set, Tuple

import httpx
import websockets


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 0 — .env LOADER
# ══════════════════════════════════════════════════════════════════════════════

def _load_env() -> None:
    path = os.path.join(os.path.dirname(__file__), ".env")
    if not os.path.exists(path):
        return
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            k = k.strip()
            v = v.strip().strip('"').strip("'")
            if k and k not in os.environ:
                os.environ[k] = v


_load_env()


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 1 — STRUCTURED ROTATING LOGGER
# ══════════════════════════════════════════════════════════════════════════════

os.makedirs("logs", exist_ok=True)

_LOG_FMT     = "%(asctime)s [%(levelname)s] tid=%(trade_id)s  %(message)s"
_CONSOLE_FMT = "%(asctime)s [%(levelname)s] %(message)s"


# ── Patch print() → WebSocket broadcast ───────────────────────────
import builtins
_original_print = builtins.print


def _ws_print(*args, **kwargs):
    msg = " ".join(str(a) for a in args)
    _original_print(msg, **kwargs)
    try:
        import asyncio
        loop = asyncio.get_event_loop()
        if loop.is_running():
            from main import _broadcaster
            asyncio.ensure_future(_broadcaster.broadcast(msg))
    except (RuntimeError, ImportError):
        pass


builtins.print = _ws_print


def _setup_logging() -> logging.Logger:
    root = logging.getLogger("singularity")
    root.setLevel(logging.DEBUG)

    # ── Console Handler (with WebSocket patch) ──
    ch = logging.StreamHandler()
    ch.setLevel(logging.INFO)
    ch.setFormatter(logging.Formatter(_CONSOLE_FMT))
    root.addHandler(ch)

    # ── WebSocket Handler ──
    class WebSocketHandler(logging.Handler):
        def emit(self, record):
            try:
                msg = self.format(record)
                import asyncio
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    from main import _broadcaster
                    asyncio.ensure_future(_broadcaster.broadcast(msg))
            except Exception:
                pass

    wh = WebSocketHandler()
    wh.setLevel(logging.INFO)
    wh.setFormatter(logging.Formatter(_CONSOLE_FMT))
    root.addHandler(wh)

    for fname, level in [
        ("bot.log",    logging.DEBUG),
        ("trades.log", logging.INFO),
        ("errors.log", logging.ERROR),
    ]:
        fh = logging.handlers.RotatingFileHandler(
            f"logs/{fname}",
            maxBytes=10 * 1024 * 1024,
            backupCount=5,
        )
        fh.setLevel(level)
        fh.setFormatter(logging.Formatter(_LOG_FMT))
        root.addHandler(fh)

    return root


class TradeLogAdapter(logging.LoggerAdapter):
    """Injects `trade_id` into every log record so every line is traceable."""

    def process(self, msg: str, kwargs: dict) -> Tuple[str, dict]:
        kwargs.setdefault("extra", {})
        kwargs["extra"]["trade_id"] = self.extra.get("trade_id", "-")
        return msg, kwargs


_base_logger = _setup_logging()
log = TradeLogAdapter(_base_logger, {"trade_id": "-"})


def get_trade_logger(trade_id: str) -> TradeLogAdapter:
    return TradeLogAdapter(_base_logger, {"trade_id": trade_id})


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 2 — STATIC CONFIG  (frozen — never mutated at runtime)
# ══════════════════════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class StaticConfig:
    # ── Mode ──────────────────────────────────────────────────────────────────
    paper_mode: bool = field(default_factory=lambda: os.getenv("PAPER_MODE", "true").lower() == "true")
    require_live_flag: bool = True          # must pass --live to override

    # ── CSV files ─────────────────────────────────────────────────────────────
    sim_csv:  str = "simulation_data.csv"
    real_csv: str = "real_data.csv"

    # ── Trade budget ──────────────────────────────────────────────────────────
    max_trades_per_day: int = 5
    max_open_positions: int = 2

    # ── Worker pool ───────────────────────────────────────────────────────────
    worker_count: int = 10
    queue_max_size: int = 500

    # ── Two-phase survival filter ─────────────────────────────────────────────
    survival_seconds: int = 120       # Phase-1 wait: filter instant rugs
    recheck_seconds:  int = 120       # Phase-2 wait: confirm coin still alive

    # ── AI (Tier 3) ───────────────────────────────────────────────────────────
    gemini_model: str = "gemini-2.5-flash"
    ai_daily_limit: int = 20          # Google AI Studio free-tier RPD cap
    ai_timeout_seconds: float = 5.0   # timeout = skip, never blocks worker

    # ── TIER 1 — Hard criteria (pre-AI, all must pass) ───────────────────────
    min_holder_count: int = 30        # C1: organic distribution
    max_top10_pct: float = 60.0       # C2: rug-risk concentration cap
    min_trades_5m: int = 10           # C3: real trading momentum
    min_t1_score: float = 0.30        # Tier-1 composite gate

    # ── TIER 2 — Market condition scoring ─────────────────────────────────────
    base_mc_min_sol: float = 5.0      # dynamic: ×0.7 HOT / ×1.0 NEUTRAL / ×1.4 COLD
    min_coin_age_seconds: int = 120   # coin must have survived phase-1 wait already
    max_coin_age_seconds: int = 7200  # discard stale coins older than 2 hours
    min_t2_score: float = 0.40        # Tier-2 composite gate
    target_holders: int = 200         # investor-score normalisation target

    # ── Risk hard limits (DynamicConfig may NOT override these) ───────────────
    max_daily_loss_sol: float = 0.15
    max_drawdown_pct: float = 0.40
    max_consecutive_losses: int = 3
    max_hold_minutes: float = 20.0    # SAFETY: hard kill after 20 min

    # ── Trailing stop ─────────────────────────────────────────────────────────
    trail_pct: float = 0.15           # sell if price drops 15% from ATH

    # ── Slippage gate ─────────────────────────────────────────────────────────
    max_slippage_pct: float = 0.03    # reject if estimated slippage > 3%

    # ── Exit polling fallback ─────────────────────────────────────────────────
    poll_fallback_seconds: float = 30.0

    # ── Security ──────────────────────────────────────────────────────────────
    rugcheck_score_limit: int = 6000
    dev_failed_coins_limit: int = 5

    # ── Jito ──────────────────────────────────────────────────────────────────
    jito_url: str = "https://mainnet.block-engine.jito.wtf/api/v1/bundles"
    jito_tip_cache_seconds: float = 60.0

    # ── Circuit breaker (SAFETY) ──────────────────────────────────────────────
    circuit_breaker_failures: int = 5
    circuit_breaker_pause_s: float = 120.0

    # ── Paper mode starting balance ───────────────────────────────────────────
    paper_starting_balance_sol: float = 0.15

    # ── RPC ───────────────────────────────────────────────────────────────────
    rpc_ping_interval_s: float = 60.0

    # ── Secrets (read from env at call-time — never stored in config) ─────────
    @property
    def gemini_api_key(self) -> str:
        return os.environ.get("GEMINI_API_KEY", "")

    @property
    def rpc_urls(self) -> List[str]:
        urls = [os.environ.get(f"RPC_URL_{i}", "") for i in range(1, 4)]
        urls = [u for u in urls if u]
        fallback = os.environ.get("RPC_URL", "")
        if fallback and fallback not in urls:
            urls.append(fallback)
        return urls or ["https://api.mainnet-beta.solana.com"]

    @property
    def wallet_private_key(self) -> str:
        return os.environ.get("BOT_PRIVATE_KEY", "")

    @property
    def telegram_token(self) -> str:
        return os.environ.get("TELEGRAM_TOKEN", "")

    @property
    def telegram_chat_id(self) -> str:
        return os.environ.get("TELEGRAM_CHAT_ID", "")

    @property
    def active_csv(self) -> str:
        return self.sim_csv if self.paper_mode else self.real_csv

    @property
    def active_ailog_csv(self) -> str:
        base = self.sim_csv if self.paper_mode else self.real_csv
        return base.replace(".csv", "_ailog.csv")

    def validate(self) -> "StaticConfig":
        assert 0 < self.trail_pct < 1,       "trail_pct must be 0–1"
        assert self.max_trades_per_day > 0,  "max_trades_per_day must be > 0"
        assert self.max_open_positions > 0,  "max_open_positions must be > 0"
        assert self.survival_seconds >= 0,   "survival_seconds must be >= 0"
        assert self.recheck_seconds >= 0,    "recheck_seconds must be >= 0"
        assert 0.0 <= self.min_t1_score <= 1.0, "min_t1_score must be 0–1"
        assert 0.0 <= self.min_t2_score <= 1.0, "min_t2_score must be 0–1"
        if not self.paper_mode:
            assert self.wallet_private_key,  "BOT_PRIVATE_KEY missing from .env"
            assert self.rpc_urls,            "At least one RPC_URL_* required"
            assert self.gemini_api_key,      "GEMINI_API_KEY missing from .env"
        if not self.telegram_token:
            log.warning("event=no_telegram TELEGRAM_TOKEN not set — alerts logged only")
        log.info("event=config_valid paper=%s csv=%s", self.paper_mode, self.active_csv)
        return self


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 3 — DYNAMIC CONFIG  (recalibrated after every trade)
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class DynamicConfig:
    base_capital_sol: float = 0.15
    max_position_sol: float = 0.05
    min_position_sol: float = 0.01
    kelly_fraction: float = 0.40
    min_ai_confidence: float = 0.85    # Tier-3 gate (regime-adaptive)
    sol_usd_price: float = 150.0
    regime_mc_multiplier: float = 1.0  # dynamic market cap minimum multiplier

    def recalibrate(
        self,
        balance_sol: float,
        sol_usd: float,
        regime: str,
    ) -> None:
        self.base_capital_sol = balance_sol
        self.sol_usd_price = sol_usd
        self.max_position_sol = min(0.08, balance_sol * 0.80)

        if regime == "HOT":
            self.kelly_fraction         = 0.50
            self.min_ai_confidence      = 0.82   # slightly more permissive
            self.regime_mc_multiplier   = 0.70   # lower MC bar (hot market)
        elif regime == "COLD":
            self.kelly_fraction         = 0.25
            self.min_ai_confidence      = 0.90   # tighter AI gate
            self.regime_mc_multiplier   = 1.40   # raise MC bar (cold market)
        else:  # NEUTRAL
            self.kelly_fraction         = 0.40
            self.min_ai_confidence      = 0.85
            self.regime_mc_multiplier   = 1.00

        log.info(
            "event=recalibrate balance=%.4f sol_usd=%.2f regime=%s "
            "kelly=%.2f max_pos=%.4f ai_gate=%.2f mc_mult=%.2f",
            balance_sol, sol_usd, regime,
            self.kelly_fraction, self.max_position_sol,
            self.min_ai_confidence, self.regime_mc_multiplier,
        )


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 4 — CSV PERSISTENCE  (replaces SQLite entirely)
# ══════════════════════════════════════════════════════════════════════════════
#
#  Column layout for the main CSV:
#    status, trade_id, symbol, mint, entry_mc, ath_mc, investment_sol,
#    entry_time, t1_score, t2_score, ai_confidence, regime,
#    exit_mc, exit_reason, hold_minutes, gross_pnl_sol, net_pnl_sol, timestamp
#
#  t1_score and t2_score are logged for reinforcement learning.
# ─────────────────────────────────────────────────────────────────────────────

_TRADE_COLS: List[str] = [
    "status",
    "trade_id",
    "symbol",
    "mint",
    "entry_mc",
    "ath_mc",
    "investment_sol",
    "entry_time",
    "t1_score",
    "t2_score",
    "ai_confidence",
    "regime",
    "exit_mc",
    "exit_reason",
    "hold_minutes",
    "gross_pnl_sol",
    "net_pnl_sol",
    "timestamp",
]

_AILOG_COLS: List[str] = ["timestamp", "symbol", "mint", "t1_score", "t2_score", "confidence"]

_csv_lock = asyncio.Lock()


def _csv_ensure_headers(path: str, cols: List[str]) -> None:
    if not os.path.isfile(path):
        with open(path, "w", newline="") as fh:
            csv.writer(fh).writerow(cols)


def _csv_read_all(path: str) -> List[dict]:
    if not os.path.isfile(path):
        return []
    with open(path, "r", newline="") as fh:
        return list(csv.DictReader(fh))


def _csv_rewrite(path: str, cols: List[str], rows: List[dict]) -> None:
    with open(path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


class CsvStore:
    """All CSV read/write operations for one bot instance."""

    def __init__(self, cfg: StaticConfig) -> None:
        self._cfg = cfg

    def _main(self) -> str:
        return self._cfg.active_csv

    def _ailog(self) -> str:
        return self._cfg.active_ailog_csv

    async def upsert_position(self, position: dict) -> None:
        async with _csv_lock:
            path = self._main()
            _csv_ensure_headers(path, _TRADE_COLS)
            rows = _csv_read_all(path)
            updated = False
            for i, row in enumerate(rows):
                if row.get("trade_id") == position.get("trade_id"):
                    rows[i] = position
                    updated = True
                    break
            if not updated:
                rows.append(position)
            _csv_rewrite(path, _TRADE_COLS, rows)

    async def close_position(self, trade_id: str, exit_fields: dict) -> None:
        async with _csv_lock:
            path = self._main()
            _csv_ensure_headers(path, _TRADE_COLS)
            rows = _csv_read_all(path)
            for row in rows:
                if row.get("trade_id") == trade_id:
                    row.update(exit_fields)
                    row["status"] = "CLOSED"
                    row["timestamp"] = datetime.now(timezone.utc).isoformat()
                    break
            _csv_rewrite(path, _TRADE_COLS, rows)

    def load_open_positions(self) -> Dict[str, dict]:
        path = self._main()
        if not os.path.isfile(path):
            return {}
        result: Dict[str, dict] = {}
        for row in _csv_read_all(path):
            if row.get("status") != "OPEN":
                continue
            mint = row.get("mint", "")
            if not mint:
                continue
            try:
                result[mint] = {
                    "trade_id":       row["trade_id"],
                    "symbol":         row["symbol"],
                    "mint":           mint,
                    "entry_mc":       float(row.get("entry_mc") or 0),
                    "ath_mc":         float(row.get("ath_mc") or 0),
                    "investment_sol": float(row.get("investment_sol") or 0),
                    "entry_time":     float(row.get("entry_time") or time.time()),
                    "t1_score":       float(row.get("t1_score") or 0),
                    "t2_score":       float(row.get("t2_score") or 0),
                    "ai_confidence":  float(row.get("ai_confidence") or 0),
                    "regime":         row.get("regime", "NEUTRAL"),
                    "last_ws_update": time.time(),
                }
            except (ValueError, KeyError) as exc:
                log.warning("event=csv_parse_error trade_id=%s err=%s",
                            row.get("trade_id", "?"), exc)
        return result

    def mint_is_closed(self, mint: str) -> bool:
        path = self._main()
        if not os.path.isfile(path):
            return False
        for row in _csv_read_all(path):
            if row.get("mint") == mint and row.get("status") == "CLOSED":
                return True
        return False

    async def log_ai_call(
        self, symbol: str, mint: str,
        t1_score: float = 0.0, t2_score: float = 0.0, confidence: float = 0.0,
    ) -> None:
        async with _csv_lock:
            path = self._ailog()
            _csv_ensure_headers(path, _AILOG_COLS)
            with open(path, "a", newline="") as fh:
                csv.DictWriter(fh, fieldnames=_AILOG_COLS).writerow({
                    "timestamp":  datetime.now(timezone.utc).isoformat(),
                    "symbol":     symbol,
                    "mint":       mint,
                    "t1_score":   round(t1_score, 4),
                    "t2_score":   round(t2_score, 4),
                    "confidence": round(confidence, 4),
                })

    async def count_ai_calls_today(self) -> int:
        async with _csv_lock:
            path = self._ailog()
            if not os.path.isfile(path):
                return 0
            cutoff = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
            return sum(
                1 for row in _csv_read_all(path)
                if row.get("timestamp", "") > cutoff
            )

    def mark_open_as_crashed(self) -> None:
        try:
            path = self._main()
            if not os.path.isfile(path):
                return
            rows = _csv_read_all(path)
            changed = False
            for row in rows:
                if row.get("status") == "OPEN":
                    row["exit_reason"] = "crash_atexit"
                    row["status"]      = "CLOSED"
                    row["timestamp"]   = datetime.now(timezone.utc).isoformat()
                    changed = True
            if changed:
                _csv_rewrite(path, _TRADE_COLS, rows)
                print(f"\n⚠️  atexit: marked open positions as crash_atexit in {path}")
        except Exception as exc:
            print(f"⚠️  atexit handler error: {exc}")


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 5 — TRADE COUNTER  (UTC midnight auto-reset)
# ══════════════════════════════════════════════════════════════════════════════

class TradeCounter:
    def __init__(self) -> None:
        self._count: int = 0
        self._reset_date = datetime.now(timezone.utc).date()
        self._lock = asyncio.Lock()

    def _maybe_reset(self) -> None:
        today = datetime.now(timezone.utc).date()
        if today > self._reset_date:
            log.info("event=daily_reset prev_date=%s prev_count=%d",
                     self._reset_date, self._count)
            self._count = 0
            self._reset_date = today

    async def increment(self) -> int:
        async with self._lock:
            self._maybe_reset()
            self._count += 1
            return self._count

    async def is_limit_reached(self, max_trades: int) -> bool:
        async with self._lock:
            self._maybe_reset()
            return self._count >= max_trades

    async def get_count(self) -> int:
        async with self._lock:
            self._maybe_reset()
            return self._count


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 6 — WEIGHTED RPC POOL
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class RPCEndpoint:
    url: str
    avg_latency_ms: float = 100.0
    error_count: int = 0
    slot_lag: int = 0
    _ema_alpha: float = 0.20
    _last_slot: int = field(default=0, init=False, repr=False)

    def update_latency(self, sample_ms: float) -> None:
        self.avg_latency_ms = (
            self._ema_alpha * sample_ms
            + (1 - self._ema_alpha) * self.avg_latency_ms
        )

    @property
    def weight(self) -> float:
        latency_score = 1000.0 / max(self.avg_latency_ms, 1.0)
        error_penalty = 0.5 ** min(self.error_count, 10)
        lag_penalty   = 1.0 / (1.0 + self.slot_lag * 0.10)
        return latency_score * error_penalty * lag_penalty


class RPCPool:
    def __init__(self, urls: List[str], http: httpx.AsyncClient) -> None:
        self.endpoints = [RPCEndpoint(url=u) for u in urls]
        self._http = http
        self._best_slot: int = 0

    def select(self) -> RPCEndpoint:
        weights = [ep.weight for ep in self.endpoints]
        total = sum(weights)
        r = random.uniform(0, total)
        cumulative = 0.0
        for ep in self.endpoints:
            cumulative += ep.weight
            if r <= cumulative:
                return ep
        return self.endpoints[0]

    async def ping_all(self) -> None:
        slot_results: List[int] = []
        await asyncio.gather(
            *[self._ping(ep, slot_results) for ep in self.endpoints],
            return_exceptions=True,
        )
        if slot_results:
            self._best_slot = max(slot_results)
            for ep in self.endpoints:
                ep.slot_lag = max(0, self._best_slot - ep._last_slot)

    async def _ping(self, ep: RPCEndpoint, slot_collector: List[int]) -> None:
        start = time.monotonic()
        try:
            resp = await self._http.post(
                ep.url,
                json={"jsonrpc": "2.0", "id": 1, "method": "getSlot", "params": []},
                timeout=5.0,
            )
            latency_ms = (time.monotonic() - start) * 1000.0
            ep.update_latency(latency_ms)
            ep.error_count = max(0, ep.error_count - 1)
            slot = resp.json().get("result", 0)
            ep._last_slot = slot
            slot_collector.append(slot)
            log.debug("event=rpc_ping url=%.35s latency=%.0fms slot=%d weight=%.2f",
                      ep.url, latency_ms, slot, ep.weight)
        except Exception as exc:
            ep.error_count += 1
            log.debug("event=rpc_ping_fail url=%.35s err=%s", ep.url, exc)

    async def run_ping_loop(
        self, interval_s: float, shutdown: threading.Event
    ) -> None:
        while not shutdown.is_set():
            await self.ping_all()
            await asyncio.sleep(interval_s)


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 7 — PER-DOMAIN RATE LIMITER  (token bucket)
# ══════════════════════════════════════════════════════════════════════════════

class RateLimiter:
    def __init__(self, rate_per_minute: float) -> None:
        self._rate       = rate_per_minute / 60.0
        self._max_tokens = rate_per_minute
        self._tokens: float = self._max_tokens
        self._last_refill = time.monotonic()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        async with self._lock:
            now     = time.monotonic()
            elapsed = now - self._last_refill
            self._tokens = min(
                self._max_tokens, self._tokens + elapsed * self._rate
            )
            self._last_refill = now
            if self._tokens < 1.0:
                wait = (1.0 - self._tokens) / self._rate
                await asyncio.sleep(wait)
                self._tokens = 0.0
            else:
                self._tokens -= 1.0


_RATE: Dict[str, RateLimiter] = {
    "rugcheck":    RateLimiter(30),
    "goplus":      RateLimiter(20),
    "pumpportal":  RateLimiter(60),
    "dexscreener": RateLimiter(30),
    "gemini":      RateLimiter(60),
    "coingecko":   RateLimiter(10),
    "telegram":    RateLimiter(30),
}


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 8 — HTTP HELPERS  (retry + exponential backoff + jitter)
# ══════════════════════════════════════════════════════════════════════════════

async def http_get(
    client: httpx.AsyncClient,
    url: str,
    domain: str = "",
    retries: int = 3,
    base_delay: float = 1.0,
    timeout: float = 10.0,
) -> Optional[httpx.Response]:
    limiter = _RATE.get(domain)
    if limiter:
        await limiter.acquire()
    for attempt in range(retries):
        try:
            return await client.get(url, timeout=timeout)
        except Exception as exc:
            if attempt == retries - 1:
                log.debug("event=http_get_fail url=%.60s err=%s", url, exc)
                return None
            await asyncio.sleep(
                base_delay * (2 ** attempt) + random.uniform(0.0, 0.5)
            )
    return None


async def http_post_json(
    client: httpx.AsyncClient,
    url: str,
    payload: dict,
    domain: str = "",
    retries: int = 3,
    base_delay: float = 1.0,
    timeout: float = 10.0,
) -> Optional[httpx.Response]:
    limiter = _RATE.get(domain)
    if limiter:
        await limiter.acquire()
    for attempt in range(retries):
        try:
            return await client.post(url, json=payload, timeout=timeout)
        except Exception as exc:
            if attempt == retries - 1:
                log.debug("event=http_post_fail url=%.60s err=%s", url, exc)
                return None
            await asyncio.sleep(
                base_delay * (2 ** attempt) + random.uniform(0.0, 0.5)
            )
    return None


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 9 — CIRCUIT BREAKER  (SAFETY SYSTEM)
# ══════════════════════════════════════════════════════════════════════════════

class CircuitBreaker:
    def __init__(self, threshold: int, pause_s: float) -> None:
        self._threshold = threshold
        self._pause_s   = pause_s
        self._failures  = 0
        self._open_until: float = 0.0
        self._lock = asyncio.Lock()

    @property
    def is_open(self) -> bool:
        return time.monotonic() < self._open_until

    async def record_failure(self) -> None:
        async with self._lock:
            self._failures += 1
            if self._failures >= self._threshold:
                self._open_until = time.monotonic() + self._pause_s
                log.warning(
                    "event=circuit_breaker_open failures=%d pause_s=%.0f",
                    self._failures, self._pause_s,
                )

    async def record_success(self) -> None:
        async with self._lock:
            self._failures = max(0, self._failures - 1)


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 10 — REGIME TRACKER
# ══════════════════════════════════════════════════════════════════════════════

class RegimeTracker:
    """
    HOT     (>60% wins)   → loosen Kelly, lower AI gate, reduce MC minimum
    NEUTRAL (30–60% wins) → baseline
    COLD    (<30% wins)   → tighten Kelly, raise AI gate, increase MC minimum
    """

    def __init__(self, window: int = 10) -> None:
        self._results: List[bool] = []
        self._window = window
        self._lock = asyncio.Lock()

    async def record(self, won: bool) -> None:
        async with self._lock:
            self._results.append(won)
            if len(self._results) > self._window:
                self._results.pop(0)

    async def regime(self) -> str:
        async with self._lock:
            if len(self._results) < 3:
                return "NEUTRAL"
            wr = sum(self._results) / len(self._results)
        return "HOT" if wr > 0.60 else ("COLD" if wr < 0.30 else "NEUTRAL")

    async def win_rate(self) -> float:
        async with self._lock:
            return (
                sum(self._results) / len(self._results)
                if self._results else 0.50
            )


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 11 — RISK ENGINE  (Kelly criterion + ruin constraint)
# ══════════════════════════════════════════════════════════════════════════════
#
#  Formula:
#    Kelly raw   = (p × b − (1−p)) / b
#    Kelly sized = kelly_fraction × kelly_raw × balance
#    Ruin cap    = balance × (1 − (1 − 0.05)^(1/3))
#    Position    = min(kelly_sized, ruin_cap, max_position_sol)
#
#  where p = win_rate, b = avg_win/loss_ratio
#  Negative Kelly → return 0.0 (no edge, skip)
# ─────────────────────────────────────────────────────────────────────────────

class RiskEngine:
    def position_size(
        self,
        balance: float,
        win_rate: float,
        avg_win_loss_ratio: float,
        kelly_fraction: float,
        max_position_sol: float,
        min_position_sol: float,
    ) -> float:
        b          = max(avg_win_loss_ratio, 0.01)
        loss_rate  = 1.0 - win_rate
        kelly_raw  = (win_rate * b - loss_rate) / b
        if kelly_raw <= 0.0:
            return 0.0
        kelly_size = kelly_fraction * kelly_raw * balance
        ruin_cap   = balance * (1.0 - (1.0 - 0.05) ** (1.0 / 3.0))
        size = min(kelly_size, ruin_cap, max_position_sol)
        return max(round(size, 6), min_position_sol)

    @staticmethod
    def estimate_slippage(position_sol: float, liquidity_sol: float) -> float:
        """Slippage model: slippage ≈ √(position / liquidity)"""
        if liquidity_sol <= 0:
            return 1.0
        return math.sqrt(position_sol / max(liquidity_sol, 1e-6))


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 12 — REALISTIC PAPER EXECUTOR
# ══════════════════════════════════════════════════════════════════════════════

class PaperExecutor:
    """
    Entry slippage: +1.5%–3.0%  (wide spread on new tokens)
    Exit  slippage: −1.0%–2.5%  (thinner on the way out)
    Platform fee:   0.25% each way
    Jito tip sim:   0.0003 SOL per tx
    """

    PLATFORM_FEE = 0.0025
    SIM_TIP_SOL  = 0.0003

    def buy(self, amount_sol: float) -> float:
        slippage = random.uniform(0.015, 0.030)
        fee      = amount_sol * self.PLATFORM_FEE
        cost     = amount_sol * (1.0 + slippage) + fee + self.SIM_TIP_SOL
        return round(cost, 6)

    def sell(self, investment_sol: float, mc_ratio: float) -> float:
        gross    = investment_sol * mc_ratio
        slippage = random.uniform(0.010, 0.025)
        fee      = gross * self.PLATFORM_FEE
        net      = gross * (1.0 - slippage) - fee - self.SIM_TIP_SOL
        return round(max(0.0, net), 6)


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 13 — SOL PRICE CACHE
# ══════════════════════════════════════════════════════════════════════════════

class SolPriceCache:
    def __init__(self, http: httpx.AsyncClient, ttl_s: float = 60.0) -> None:
        self._http = http
        self._ttl  = ttl_s
        self._price: float = 150.0
        self._fetched_at: float = 0.0

    async def get(self) -> float:
        if time.monotonic() - self._fetched_at > self._ttl:
            await self._refresh()
        return self._price

    async def _refresh(self) -> None:
        resp = await http_get(
            self._http,
            "https://api.coingecko.com/api/v3/simple/price"
            "?ids=solana&vs_currencies=usd",
            domain="coingecko",
        )
        try:
            if resp and resp.status_code == 200:
                self._price      = float(resp.json()["solana"]["usd"])
                self._fetched_at = time.monotonic()
                log.debug("event=sol_price_refreshed price=%.2f", self._price)
        except Exception as exc:
            log.debug("event=sol_price_parse_fail err=%s", exc)


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 14 — DYNAMIC JITO TIP MANAGER
# ══════════════════════════════════════════════════════════════════════════════

class JitoTipManager:
    _TIP_URL = "https://bundles.jito.wtf/api/v1/bundles/tip_floor"

    def __init__(self, http: httpx.AsyncClient, cache_s: float = 60.0) -> None:
        self._http      = http
        self._cache_s   = cache_s
        self._p75: int  = 1_000
        self._p90: int  = 2_000
        self._fetched_at: float = 0.0

    async def get_tip(self, urgent: bool = False) -> int:
        if time.monotonic() - self._fetched_at > self._cache_s:
            await self._refresh()
        return self._p90 if urgent else self._p75

    async def _refresh(self) -> None:
        try:
            resp = await self._http.get(self._TIP_URL, timeout=5.0)
            if resp.status_code == 200:
                d = resp.json()
                self._p75 = int(d.get(
                    "p75", d.get("landed_tips_75th_percentile_lamports", 1_000)
                ))
                self._p90 = int(d.get(
                    "p90", d.get("landed_tips_90th_percentile_lamports", 2_000)
                ))
                self._fetched_at = time.monotonic()
                log.debug("event=jito_tip_refresh p75=%d p90=%d", self._p75, self._p90)
        except Exception as exc:
            log.debug("event=jito_tip_fail err=%s using_fallback", exc)


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 15 — JITO SIMULATE-THEN-SEND
# ══════════════════════════════════════════════════════════════════════════════

async def jito_simulate_and_send(
    transaction_b64: str,
    http: httpx.AsyncClient,
    jito_url: str,
) -> Optional[str]:
    sim_payload = {
        "jsonrpc": "2.0", "id": 1, "method": "simulateBundle",
        "params": [
            {"encodedTransactions": [transaction_b64]},
            {"simulationBank": "tip", "skipSigVerify": True,
             "replaceRecentBlockhash": True},
        ],
    }
    try:
        sim_resp = await http.post(jito_url, json=sim_payload, timeout=8.0)
        sim_data = sim_resp.json()
        if "error" in sim_data:
            log.warning("event=jito_sim_rejected reason=%s",
                        sim_data["error"].get("message", "unknown"))
            return None
        for tx in sim_data.get("result", {}).get("value", []):
            if tx.get("err") is not None:
                log.warning("event=jito_sim_tx_err err=%s", tx["err"])
                return None
        log.info("event=jito_sim_passed")
    except Exception as exc:
        log.warning("event=jito_sim_unreachable err=%s proceeding_anyway", exc)

    send_payload = {
        "jsonrpc": "2.0", "id": 1,
        "method": "sendBundle",
        "params": [[transaction_b64]],
    }
    try:
        resp = await http.post(jito_url, json=send_payload, timeout=10.0)
        bundle_id = resp.json().get("result")
        log.info("event=jito_bundle_sent id=%s", bundle_id)
        return bundle_id
    except Exception as exc:
        log.error("event=jito_send_fail err=%s", exc)
        return None


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 16 — TELEGRAM ALERTER
# ══════════════════════════════════════════════════════════════════════════════

class TelegramAlerter:
    def __init__(
        self, token: str, chat_id: str, http: httpx.AsyncClient
    ) -> None:
        self._token   = token
        self._chat_id = chat_id
        self._http    = http
        self._enabled = bool(token and chat_id)

    async def send(self, text: str) -> None:
        if not self._enabled:
            log.info("event=telegram_skipped text=%.80s", text)
            return
        try:
            await _RATE["telegram"].acquire()
            await self._http.post(
                f"https://api.telegram.org/bot{self._token}/sendMessage",
                json={"chat_id": self._chat_id, "text": text,
                      "parse_mode": "HTML"},
                timeout=5.0,
            )
        except Exception as exc:
            log.warning("event=telegram_fail err=%s", exc)

    async def startup_msg(self, cfg: StaticConfig) -> None:
        mode = "📄 SIMULATION" if cfg.paper_mode else "🔴 LIVE TRADING"
        await self.send(
            f"🚀 <b>ARC Singularity v3.0 Online</b> — Hacksagon 2026 · App Development Track\n"
            f"👑 Team Pheonex\n"
            f"Mode: {mode} | CSV: {cfg.active_csv}\n"
            f"Trades/day: {cfg.max_trades_per_day} | "
            f"Positions: {cfg.max_open_positions}\n"
            f"Trail: {cfg.trail_pct:.0%} | Kill: {cfg.max_hold_minutes:.0f}min | "
            f"AI: {cfg.gemini_model} ({cfg.ai_daily_limit}/day)\n"
            f"Pipeline: {cfg.survival_seconds}s wait → MC check → "
            f"{cfg.recheck_seconds}s recheck → T1 Math → T2 Market → T3 Gemini"
        )

    async def snipe(
        self,
        symbol: str,
        t1_score: float,
        t2_score: float,
        confidence: float,
        size_sol: float,
        entry_mc: Optional[float],
        regime: str,
        holders: Optional[int],
        top10: Optional[float],
        trades_5m: Optional[int],
        slot: int,
        max_slots: int,
        trade_id: str,
        paper_mode: bool,
    ) -> None:
        mode_tag = "📄 PAPER" if paper_mode else "🔴 LIVE"
        await self.send(
            f"🔥 <b>SNIPE [{mode_tag}]: ${symbol}</b>  <code>{trade_id}</code>\n"
            f"T1: {t1_score:.2f} | T2: {t2_score:.2f} | AI: {confidence:.2f} | Size: {size_sol:.4f} SOL\n"
            f"Entry MC: {entry_mc or '?'} SOL | Holders: {holders} | Top10: {top10}% | Trades/5m: {trades_5m}\n"
            f"Regime: {regime} | Slot {slot}/{max_slots}"
        )

    async def exit_alert(
        self,
        symbol: str,
        reason: str,
        hold_min: float,
        pnl_pct: float,
        pnl_sol: float,
        trade_id: str,
        paper_mode: bool,
    ) -> None:
        emoji    = "📈" if pnl_sol >= 0 else "📉"
        mode_tag = "📄 PAPER" if paper_mode else "🔴 LIVE"
        await self.send(
            f"{emoji} <b>EXIT [{mode_tag}]: ${symbol}</b>  <code>{trade_id}</code>\n"
            f"Reason: {reason} | Hold: {hold_min:.1f} min\n"
            f"P/L: {pnl_pct:+.1f}%  ({pnl_sol:+.4f} SOL)"
        )

    async def circuit_open(self) -> None:
        await self.send(
            "⚠️ <b>CIRCUIT BREAKER OPEN</b>\n"
            "Consecutive API failures exceeded threshold. Trading paused 120s."
        )

    async def kill_switch(self, reason: str) -> None:
        await self.send(f"🛑 <b>KILL SWITCH ACTIVATED</b>\n{reason}")

    async def shutdown_msg(self) -> None:
        await self.send("🛑 <b>ARC Singularity v3.0 shutting down.</b>")


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 17 — TIER 3 AI LAYER  (Gemini 2.5-flash — final validation gate)
# ══════════════════════════════════════════════════════════════════════════════

def _extract_ai_json(raw: str) -> Optional[dict]:
    """Robustly extract the first valid JSON object from Gemini output."""
    cleaned   = re.sub(r"```(?:json)?\s*", "", raw).strip()
    start     = cleaned.find("{")
    if start == -1:
        return None
    candidate  = cleaned[start:]
    search_end = len(candidate)
    while search_end > 0:
        rbrace = candidate.rfind("}", 0, search_end)
        if rbrace == -1:
            return None
        try:
            return json.loads(candidate[: rbrace + 1])
        except json.JSONDecodeError:
            search_end = rbrace
    return None


def _build_ai_prompt(
    token: dict,
    onchain: dict,
    t1_score: float,
    t2_score: float,
    cfg: StaticConfig,
    regime: str,
) -> str:
    """
    Tier-3 prompt: injects Tier-1 and Tier-2 scores alongside all raw data.
    Gemini is asked to evaluate subtler risks the math cannot catch.
    """
    total_wait = cfg.survival_seconds + cfg.recheck_seconds
    return f"""You are a Solana meme coin risk analyst for ARC — Adaptive Risk Core.
This token has PASSED Tier-1 (Math Score: {t1_score:.2f}) and Tier-2 (Market Score: {t2_score:.2f}).
Current market regime: {regime}. Be strict. Your role is to catch what math cannot.

TOKEN METADATA:
- Symbol:       {token.get('symbol', '???')}
- Has Twitter:  {bool(token.get('twitter'))}
- Has Telegram: {bool(token.get('telegram'))}
- Description:  {str(token.get('description', ''))[:150]}

ON-CHAIN DATA (collected {total_wait}s after launch — two-phase rug check passed):
- Market Cap SOL:         {onchain.get('market_cap')}
- Coin Age (seconds):     {onchain.get('coin_age_seconds')}
- Holder Count:           {onchain.get('holder_count')}     [T1-C1 ✓]
- Top-10 Concentration:   {onchain.get('top10_concentration_pct')}%   [T1-C2 ✓]
- Bundles Detected:       {onchain.get('bundles_detected')}   [T1-C0 ✓]
- Bonding Curve Progress: {onchain.get('bonding_curve_pct')}%
- Community Replies:      {onchain.get('reply_count')}
- Trades Last 5 min:      {onchain.get('trade_count_5m')}   [T1-C3 ✓]
- Volume Last 5 min:      {onchain.get('volume_sol_5m')} SOL
- DEX Liquidity USD:      {onchain.get('dex_liquidity_usd')}
- DEX Volume 24h USD:     {onchain.get('dex_volume_24h_usd')}

TIER SCORES (for context):
- T1 Math Score:  {t1_score:.3f}  (gate ≥ {cfg.min_t1_score})
- T2 Market Score:{t2_score:.3f}  (gate ≥ {cfg.min_t2_score})

REJECT if ANY of:
  • Description is empty or obviously AI-generated filler
  • Volume/5m is implausibly low vs trade count (wash-trading signal)
  • Bonding curve < 5% after {total_wait}s (not gaining real traction)
  • Community replies = 0 (zero organic interest)
  • DEX liquidity absent after this long (no real market forming)
  • Narrative/name is a clear rug signal or copycat

Respond ONLY with JSON — no markdown, no preamble.
Format: {{"buy": true/false, "confidence": 0.0-1.0, "primary_risk": "one_word_label"}}"""


async def call_gemini(
    prompt: str,
    api_key: str,
    model: str,
    timeout_s: float,
    http: httpx.AsyncClient,
) -> Optional[str]:
    """Direct Gemini REST call with hard timeout. Timeout → None (never blocks)."""
    url = (
        f"https://generativelanguage.googleapis.com/v1beta/models/"
        f"{model}:generateContent?key={api_key}"
    )
    payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.1, "maxOutputTokens": 200},
    }
    try:
        resp = await asyncio.wait_for(
            http_post_json(
                http, url, payload,
                domain="gemini",
                timeout=timeout_s + 2,
            ),
            timeout=timeout_s,
        )
        if resp is None:
            return None
        parts = (
            resp.json()
            .get("candidates", [{}])[0]
            .get("content", {})
            .get("parts", [{}])
        )
        return parts[0].get("text", "") if parts else None
    except asyncio.TimeoutError:
        log.warning("event=ai_timeout model=%s", model)
        return None
    except Exception as exc:
        log.warning("event=ai_error err=%s", exc)
        return None


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 18 — SECURITY LAYER  (RugCheck + GoPlus, fail-closed)
# ══════════════════════════════════════════════════════════════════════════════

async def dual_security_check(
    mint: str,
    dev: str,
    http: httpx.AsyncClient,
    cfg: StaticConfig,
) -> bool:
    """
    RugCheck + GoPlus, fail-closed.
    Both unreachable → reject (never trade blind).
    Either flags → reject.
    Dev reputation via pumpportal.fun coin history.
    """
    rugcheck_ok: Optional[bool] = None
    goplus_ok:   Optional[bool] = None
    dev_ok:      bool = True

    async def check_rugcheck():
        nonlocal rugcheck_ok
        resp = await http_get(
            http,
            f"https://api.rugcheck.xyz/v1/tokens/{mint}/report",
            domain="rugcheck",
        )
        if resp and resp.status_code == 200:
            try:
                rugcheck_ok = resp.json().get("score", 0) <= cfg.rugcheck_score_limit
            except Exception:
                pass

    async def check_goplus():
        nonlocal goplus_ok
        resp = await http_get(
            http,
            f"https://api.gopluslabs.io/api/v1/token_security/solana"
            f"?contract_addresses={mint}",
            domain="goplus",
        )
        if resp and resp.status_code == 200:
            try:
                result    = resp.json().get("result", {}).get(mint.lower(), {})
                honeypot  = int(result.get("is_honeypot",  0))
                blacklist = int(result.get("is_blacklisted", 0))
                goplus_ok = not honeypot and not blacklist
            except Exception:
                pass

    async def check_dev():
        nonlocal dev_ok
        resp = await http_get(
            http,
            f"https://pumpportal.fun/api/coins/user-created-coins/{dev}?limit=10",
            domain="pumpportal",
        )
        if resp and resp.status_code == 200:
            try:
                failed = sum(
                    1 for c in resp.json()
                    if float(c.get("market_cap", 0)) < 40
                )
                if failed > cfg.dev_failed_coins_limit:
                    log.info("event=dev_reject dev=%s failed_coins=%d", dev[:8], failed)
                    dev_ok = False
            except Exception:
                pass

    await asyncio.gather(check_rugcheck(), check_goplus(), check_dev())

    if not dev_ok:
        return False

    if rugcheck_ok is None and goplus_ok is None:
        log.warning("event=security_both_down mint=%s rejecting", mint[:8])
        return False

    return (rugcheck_ok is not False) and (goplus_ok is not False)


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 19 — ON-CHAIN CONTEXT  (pumpportal.fun + RugCheck + DEXScreener)
# ══════════════════════════════════════════════════════════════════════════════

async def fetch_onchain_context(
    mint: str, http: httpx.AsyncClient, token_created_at: Optional[float] = None
) -> dict:
    """All token data from pumpportal.fun. DEXScreener as independent cross-check."""
    ctx: dict = {
        "market_cap":             None,
        "reply_count":            None,
        "holder_count":           None,
        "top10_concentration_pct":None,
        "bundles_detected":       None,
        "bonding_curve_pct":      None,
        "trade_count_5m":         None,
        "volume_sol_5m":          None,
        "virtual_sol_reserves":   None,
        "dex_liquidity_usd":      None,
        "dex_volume_24h_usd":     None,
        "liquidity_sol":          None,
        "coin_age_seconds":       None,
        "created_timestamp":      None,
    }

    async def fetch_pumpportal_core():
        resp = await http_get(http, f"https://pumpportal.fun/api/coins/{mint}", "pumpportal")
        if resp and resp.status_code == 200:
            try:
                d = resp.json()
                ctx["market_cap"]           = d.get("market_cap")
                ctx["reply_count"]          = d.get("reply_count")
                ctx["virtual_sol_reserves"] = d.get("virtual_sol_reserves")
                ctx["bonding_curve_pct"]    = d.get("bonding_curve_status", {}).get("percentage")
                created = d.get("created_timestamp")
                if created:
                    ctx["created_timestamp"] = created
                    ctx["coin_age_seconds"]  = int(time.time() - created / 1000)
            except Exception: pass

    async def fetch_holders():
        resp = await http_get(http, f"https://pumpportal.fun/api/coins/{mint}/holders?limit=10", "pumpportal")
        if resp and resp.status_code == 200:
            try:
                d = resp.json()
                ctx["holder_count"] = d.get("total")
                top10 = d.get("holders", [])
                if top10:
                    ctx["top10_concentration_pct"] = round(sum(float(h.get("percentage", 0)) for h in top10), 2)
            except Exception: pass

    async def fetch_trades():
        resp = await http_get(http, f"https://pumpportal.fun/api/trades/{mint}?limit=100&minimumSize=0", "pumpportal")
        if resp and resp.status_code == 200:
            try:
                trades  = resp.json()
                cutoff  = datetime.utcnow() - timedelta(minutes=5)
                recent  = [t for t in trades if datetime.utcfromtimestamp(t.get("timestamp", 0) / 1000) > cutoff]
                ctx["trade_count_5m"] = len(recent)
                ctx["volume_sol_5m"]  = round(sum(float(t.get("sol_amount", 0)) / 1e9 for t in recent), 4)
            except Exception: pass

    async def fetch_rugcheck_summary():
        resp = await http_get(http, f"https://api.rugcheck.xyz/v1/tokens/{mint}/report/summary", "rugcheck")
        if resp and resp.status_code == 200:
            try:
                risks = resp.json().get("risks", [])
                ctx["bundles_detected"] = any("bundle" in r.get("name", "").lower() or "bundle" in r.get("description", "").lower() for r in risks)
            except Exception: pass

    async def fetch_dexscreener():
        resp = await http_get(http, f"https://api.dexscreener.com/latest/dex/tokens/{mint}", "dexscreener")
        if resp and resp.status_code == 200:
            try:
                pairs = resp.json().get("pairs") or []
                if pairs:
                    best = pairs[0]
                    ctx["dex_liquidity_usd"]  = best.get("liquidity", {}).get("usd")
                    ctx["dex_volume_24h_usd"] = best.get("volume", {}).get("h24")
            except Exception: pass

    await asyncio.gather(
        fetch_pumpportal_core(),
        fetch_holders(),
        fetch_trades(),
        fetch_rugcheck_summary(),
        fetch_dexscreener()
    )

    if ctx["coin_age_seconds"] is None and token_created_at is not None:
        ctx["coin_age_seconds"] = int(time.time() - token_created_at)

    if ctx["dex_liquidity_usd"]:
        ctx["liquidity_sol"] = ctx["dex_liquidity_usd"] / 150.0

    return ctx


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 20 — TIER 1: MATH SCORER  (zero network cost)
# ══════════════════════════════════════════════════════════════════════════════
#
#  Three sub-scores, each clamped to [0, 1]:
#
#  Momentum Score  M  = clamp(trades_5m / min_trades, 0, 1) × clamp(√volume_5m, 0, 1)
#  Safety Score    S  = (1 − top10_pct/100) × clamp(holder_count / min_holders, 0, 1)
#                       × (0 if bundles_detected else 1)
#  Vitality Score  V  = clamp(bonding_curve_pct/100, 0, 1) × ln(1 + reply_count)/ln(11)
#
#  T1_composite = 0.40·M + 0.40·S + 0.20·V
#  Gate: T1_composite ≥ cfg.min_t1_score
# ─────────────────────────────────────────────────────────────────────────────

class Tier1MathScorer:
    """
    Pure mathematical pre-filter — runs entirely from on-chain context.
    No Gemini calls, no additional HTTP requests. Zero marginal cost.
    """

    def score(self, onchain: dict, cfg: StaticConfig) -> Tuple[float, Optional[str]]:
        """
        Returns (composite_score, rejection_reason).
        rejection_reason is None if the token passes.
        """
        # ── Instant reject: bundle detected (no math redeems this) ────────────
        if onchain.get("bundles_detected") is True:
            return 0.0, "T1-C0:bundles_detected"

        holders   = onchain.get("holder_count") or 0
        top10     = onchain.get("top10_concentration_pct")
        trades_5m = onchain.get("trade_count_5m") or 0
        volume_5m = onchain.get("volume_sol_5m") or 0.0
        bonding   = onchain.get("bonding_curve_pct") or 0.0
        replies   = onchain.get("reply_count") or 0

        # ── Hard criteria (fail immediately) ──────────────────────────────────
        if holders < cfg.min_holder_count:
            return 0.0, f"T1-C1:holders={holders}<{cfg.min_holder_count}"

        if top10 is not None and top10 >= cfg.max_top10_pct:
            return 0.0, f"T1-C2:top10={top10:.1f}%>={cfg.max_top10_pct}%"

        if trades_5m < cfg.min_trades_5m:
            return 0.0, f"T1-C3:trades_5m={trades_5m}<{cfg.min_trades_5m}"

        # ── Momentum Score M ──────────────────────────────────────────────────
        m_base     = min(trades_5m / max(cfg.min_trades_5m, 1), 2.0) / 2.0
        m_volume   = min(math.sqrt(max(volume_5m, 0.0)), 1.0)
        M          = min(m_base * (0.5 + 0.5 * m_volume), 1.0)

        # ── Safety Score S ────────────────────────────────────────────────────
        concentration_factor = 1.0 - ((top10 or 0.0) / 100.0)
        holder_ratio         = min(holders / max(cfg.min_holder_count, 1), 3.0) / 3.0
        S                    = concentration_factor * holder_ratio

        # ── Vitality Score V ──────────────────────────────────────────────────
        curve_factor  = min(max(bonding, 0.0) / 100.0, 1.0)
        reply_factor  = math.log1p(replies) / math.log1p(10)   # normalised at 10 replies
        V             = min(curve_factor * (0.5 + 0.5 * reply_factor), 1.0)

        # ── Composite ─────────────────────────────────────────────────────────
        composite = 0.40 * M + 0.40 * S + 0.20 * V

        if composite < cfg.min_t1_score:
            return composite, f"T1-score={composite:.3f}<{cfg.min_t1_score}"

        return composite, None


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 21 — TIER 2: MARKET SCORER  (dynamic thresholds + coin life)
# ══════════════════════════════════════════════════════════════════════════════
#
#  Dynamic Market Cap Minimum:
#    MC_min = base_mc_min_sol × regime_multiplier
#
#  Investor Score (logarithmic):
#    I = log₂(holder_count + 1) / log₂(target_holders + 1)   ∈ [0, 1]
#
#  Liquidity Ratio:
#    L = dex_liquidity_usd / (market_cap_sol × sol_usd)
#    L_norm = clamp(L / 0.5, 0, 1)   (0.5 = ideal L/MC ratio)
#
#  Coin Age Score:
#    A = 1.0  if min_age ≤ coin_age ≤ max_age
#        0.5  if coin_age > max_age  (stale but still alive)
#        0.0  if coin_age < min_age  (too fresh, survival pending)
#
#  T2_composite = 0.35·I + 0.35·L_norm + 0.30·A
#  Gate: T2_composite ≥ cfg.min_t2_score  AND  market_cap_sol ≥ MC_min
# ─────────────────────────────────────────────────────────────────────────────

class Tier2MarketScorer:
    """
    Market condition scoring layer — evaluates dynamic minimum market cap,
    investor count depth, liquidity quality, and coin age validity.
    """

    def score(
        self,
        onchain: dict,
        cfg: StaticConfig,
        dyn: DynamicConfig,
    ) -> Tuple[float, Optional[str]]:
        """
        Returns (composite_score, rejection_reason).
        rejection_reason is None if the token passes.
        """
        mc_sol      = onchain.get("market_cap") or 0.0
        holders     = onchain.get("holder_count") or 0
        liq_usd     = onchain.get("dex_liquidity_usd") or 0.0
        age_s       = onchain.get("coin_age_seconds")
        sol_usd     = dyn.sol_usd_price

        # ── Dynamic market cap gate ────────────────────────────────────────────
        mc_min = cfg.base_mc_min_sol * dyn.regime_mc_multiplier
        if mc_sol < mc_min:
            return 0.0, f"T2-MC:sol={mc_sol:.2f}<min={mc_min:.2f}"

        # ── Coin age gate ─────────────────────────────────────────────────────
        if age_s is not None:
            if age_s < cfg.min_coin_age_seconds:
                return 0.0, f"T2-age:age={age_s}s<{cfg.min_coin_age_seconds}s"
        # (stale coins still score 0.5 on age dimension, not rejected)

        # ── Investor Score I ──────────────────────────────────────────────────
        log2_holders = math.log2(max(holders + 1, 1))
        log2_target  = math.log2(max(cfg.target_holders + 1, 2))
        I            = min(log2_holders / log2_target, 1.0)

        # ── Liquidity Ratio L ─────────────────────────────────────────────────
        mc_usd = mc_sol * max(sol_usd, 1.0)
        if mc_usd > 0 and liq_usd > 0:
            L_raw  = liq_usd / mc_usd
            L_norm = min(L_raw / 0.50, 1.0)   # 0.5 = ideal 50% liquidity/MC
        else:
            L_norm = 0.0

        # ── Coin Age Score A ──────────────────────────────────────────────────
        if age_s is None:
            A = 0.5   # unknown age → neutral
        elif age_s < cfg.min_coin_age_seconds:
            A = 0.0
        elif age_s > cfg.max_coin_age_seconds:
            A = 0.5   # stale but alive
        else:
            A = 1.0

        # ── Composite ─────────────────────────────────────────────────────────
        composite = 0.35 * I + 0.35 * L_norm + 0.30 * A

        if composite < cfg.min_t2_score:
            return composite, (
                f"T2-score={composite:.3f}<{cfg.min_t2_score} "
                f"(I={I:.2f} L={L_norm:.2f} A={A:.2f})"
            )

        return composite, None


# ══════════════════════════════════════════════════════════════════════════════
#  SECTION 22 — SINGULARITY ENGINE  (main orchestrator)
# ══════════════════════════════════════════════════════════════════════════════

class SingularityEngine:

    def __init__(
        self,
        cfg: StaticConfig,
        dyn: DynamicConfig,
        store: CsvStore,
        telegram: TelegramAlerter,
        http: httpx.AsyncClient,
    ) -> None:
        self.cfg   = cfg
        self.dyn   = dyn
        self.store = store
        self.tg    = telegram
        self.http  = http

        # SAFETY: kill switch
        self.shutdown_event = threading.Event()
        self.token_queue: asyncio.Queue = asyncio.Queue(maxsize=cfg.queue_max_size)
        self.active_positions: Dict[str, dict] = {}
        self.pending_mints: Set[str] = set()
        self._pos_lock = asyncio.Lock()
        self._ai_lock  = asyncio.Lock()

        self.trade_counter = TradeCounter()
        self.regime        = RegimeTracker()
        self.risk          = RiskEngine()
        self.tier1         = Tier1MathScorer()
        self.tier2         = Tier2MarketScorer()
        self.breaker       = CircuitBreaker(
            cfg.circuit_breaker_failures, cfg.circuit_breaker_pause_s
        )
        self.paper         = PaperExecutor()
        self.jito          = JitoTipManager(http, cfg.jito_tip_cache_seconds)
        self.sol_price     = SolPriceCache(http)
        self.rpc_pool      = RPCPool(cfg.rpc_urls, http)

        self._daily_pnl:     float = 0.0
        self._consec_losses: int   = 0

    # ─────────────────────────────────────────────────────────────────────────
    #  Startup: restore open positions from CSV
    # ─────────────────────────────────────────────────────────────────────────

    async def restore_state(self) -> None:
        positions = self.store.load_open_positions()
        if positions:
            async with self._pos_lock:
                self.active_positions.update(positions)
            log.info("event=state_restored positions=%d csv=%s",
                     len(positions), self.cfg.active_csv)
            for mint, pos in positions.items():
                print(
                    f"  ♻️  Re-adopted open position: "
                    f"{pos['symbol']} ({mint[:8]}…) "
                    f"investment={pos['investment_sol']:.4f} SOL"
                )

    # ─────────────────────────────────────────────────────────────────────────
    #  Close a single position
    # ─────────────────────────────────────────────────────────────────────────

    async def close_position(
        self, mint: str, current_mc: float, reason: str
    ) -> None:
        async with self._pos_lock:
            pos = self.active_positions.pop(mint, None)
        if pos is None:
            return

        symbol     = pos["symbol"]
        trade_id   = pos.get("trade_id", "-")
        tlog       = get_trade_logger(trade_id)
        investment = pos["investment_sol"]
        entry_mc   = pos.get("entry_mc") or current_mc
        entry_time = pos.get("entry_time", time.time())
        hold_min   = (time.time() - entry_time) / 60.0

        if self.cfg.paper_mode:
            mc_ratio = current_mc / max(entry_mc, 1e-9)
            net_sol  = self.paper.sell(investment, mc_ratio)
        else:
            tip = await self.jito.get_tip(urgent=True)
            tlog.info("event=live_sell symbol=%s tip=%d", symbol, tip)
            mc_ratio = current_mc / max(entry_mc, 1e-9)
            net_sol  = round(investment * mc_ratio, 6)

        gross_pnl = net_sol - investment
        pnl_pct   = (gross_pnl / investment * 100) if investment > 0 else 0.0
        won       = gross_pnl > 0

        await self.regime.record(won)
        self._daily_pnl       += gross_pnl
        self._consec_losses    = 0 if won else self._consec_losses + 1

        await self.store.close_position(
            trade_id,
            {
                "exit_mc":       current_mc,
                "exit_reason":   reason,
                "hold_minutes":  round(hold_min, 2),
                "gross_pnl_sol": round(gross_pnl, 6),
                "net_pnl_sol":   round(gross_pnl, 6),
                "ath_mc":        pos.get("ath_mc", 0.0),
            },
        )

        regime_now = await self.regime.regime()
        sol_usd    = await self.sol_price.get()
        self.dyn.recalibrate(
            balance_sol=self.dyn.base_capital_sol + gross_pnl,
            sol_usd=sol_usd,
            regime=regime_now,
        )

        tlog.info(
            "event=position_closed symbol=%s reason=%s hold=%.1fm "
            "pnl_sol=%.4f pnl_pct=%.1f regime=%s",
            symbol, reason, hold_min, gross_pnl, pnl_pct, regime_now,
        )
        print(
            f"{'📈' if won else '📉'} EXIT {symbol} | {reason} | "
            f"Hold {hold_min:.1f}m | P/L: {pnl_pct:+.1f}% "
            f"({gross_pnl:+.4f} SOL)"
        )

        await self.tg.exit_alert(
            symbol=symbol, reason=reason, hold_min=hold_min,
            pnl_pct=pnl_pct, pnl_sol=gross_pnl,
            trade_id=trade_id, paper_mode=self.cfg.paper_mode,
        )

    # ─────────────────────────────────────────────────────────────────────────
    #  KILL SWITCH: close ALL positions (shutdown + atexit path)
    # ─────────────────────────────────────────────────────────────────────────

    async def close_all_positions(self, reason: str = "shutdown") -> None:
        async with self._pos_lock:
            mints = list(self.active_positions.keys())
        if mints:
            log.info("event=kill_switch_triggered count=%d reason=%s",
                     len(mints), reason)
            await self.tg.kill_switch(
                f"Closing {len(mints)} position(s). Reason: {reason}"
            )
        for mint in mints:
            async with self._pos_lock:
                pos = self.active_positions.get(mint)
            if pos:
                exit_mc = pos.get("ath_mc") or pos.get("entry_mc") or 0.0
                await self.close_position(mint, exit_mc, reason)

    # ─────────────────────────────────────────────────────────────────────────
    #  THREE-TIER PIPELINE  (runs inside worker pool)
    # ─────────────────────────────────────────────────────────────────────────

    async def process_token(self, token: dict) -> None:
        trade_id = str(uuid.uuid4())[:8]
        tlog     = get_trade_logger(trade_id)
        cfg      = self.cfg
        mint:    str = token.get("mint", "")
        dev:     str = token.get("creator", "")
        symbol:  str = token.get("symbol", "???")
        token_ts:    Optional[float] = token.get("created_timestamp")
        if token_ts:
            token_created_at = token_ts / 1000.0
        else:
            token_created_at = time.time()

        if not mint:
            return

        # ── Gate 0: Duplicate guard ───────────────────────────────────────────
        async with self._pos_lock:
            if mint in self.active_positions or mint in self.pending_mints:
                return
        if self.store.mint_is_closed(mint):
            return

        # ── Gate 1: Social pre-filter (free, zero network) ────────────────────
        if not (token.get("twitter") or token.get("telegram")):
            return

        # ── Gate 2: Daily limit + circuit breaker ─────────────────────────────
        if await self.trade_counter.is_limit_reached(cfg.max_trades_per_day):
            return
        if self.breaker.is_open:
            tlog.warning("event=circuit_breaker_skip symbol=%s", symbol)
            return

        # ── Slot reservation ──────────────────────────────────────────────────
        async with self._pos_lock:
            occupied = len(self.active_positions) + len(self.pending_mints)
            if occupied >= cfg.max_open_positions:
                return
            self.pending_mints.add(mint)

        try:
            # ╔══════════════════════════════════════════════════════════════╗
            # ║  PHASE 1: 120s SURVIVAL WAIT + MARKET CAP ALIVE CHECK       ║
            # ╚══════════════════════════════════════════════════════════════╝
            tlog.info("event=phase1_wait symbol=%s seconds=%d",
                      symbol, cfg.survival_seconds)
            print(f"  ⏳ Phase-1: {symbol} waiting {cfg.survival_seconds}s…")
            await asyncio.sleep(cfg.survival_seconds)
            if self.shutdown_event.is_set():
                return

            ctx_phase1 = await fetch_onchain_context(mint, self.http, token_created_at)
            mc_phase1  = ctx_phase1.get("market_cap") or 0
            if mc_phase1 == 0:
                tlog.info("event=rug_phase1 symbol=%s mc=0", symbol)
                print(f"  💀 Phase-1: {symbol} rugged (MC=0), skipping.")
                return
            tlog.info("event=phase1_alive symbol=%s mc=%.2f", symbol, mc_phase1)

            # ╔══════════════════════════════════════════════════════════════╗
            # ║  PHASE 2: 120s RECHECK + MARKET CAP STILL ALIVE             ║
            # ╚══════════════════════════════════════════════════════════════╝
            tlog.info("event=phase2_wait symbol=%s seconds=%d",
                      symbol, cfg.recheck_seconds)
            print(f"  ⏳ Phase-2: {symbol} recheck in {cfg.recheck_seconds}s…")
            await asyncio.sleep(cfg.recheck_seconds)
            if self.shutdown_event.is_set():
                return

            onchain   = await fetch_onchain_context(mint, self.http, token_created_at)
            mc_phase2 = onchain.get("market_cap") or 0
            if mc_phase2 == 0:
                tlog.info("event=rug_phase2 symbol=%s mc=0", symbol)
                print(f"  💀 Phase-2: {symbol} rugged between checks, skipping.")
                return
            tlog.info("event=phase2_alive symbol=%s mc=%.2f", symbol, mc_phase2)

            # Slot re-check post-wait
            async with self._pos_lock:
                if len(self.active_positions) >= cfg.max_open_positions:
                    return

            # ╔══════════════════════════════════════════════════════════════╗
            # ║  DUAL SECURITY CHECK (RugCheck + GoPlus, fail-closed)        ║
            # ╚══════════════════════════════════════════════════════════════╝
            if not await dual_security_check(mint, dev, self.http, cfg):
                tlog.info("event=security_reject symbol=%s", symbol)
                print(f"  🔒 Security: {symbol} rejected by RugCheck/GoPlus.")
                return

            # Update SOL price before scoring
            sol_usd = await self.sol_price.get()
            if onchain.get("dex_liquidity_usd"):
                onchain["liquidity_sol"] = onchain["dex_liquidity_usd"] / max(sol_usd, 1.0)

            # ╔══════════════════════════════════════════════════════════════╗
            # ║  TIER 1: MATH SCORE (Momentum × Safety × Vitality)          ║
            # ╚══════════════════════════════════════════════════════════════╝
            t1_score, t1_reject = self.tier1.score(onchain, cfg)
            if t1_reject:
                tlog.info("event=tier1_reject symbol=%s reason=%s t1=%.3f",
                          symbol, t1_reject, t1_score)
                print(f"  📐 Tier-1: {symbol} → {t1_reject}")
                return
            tlog.info("event=tier1_pass symbol=%s t1=%.3f", symbol, t1_score)
            print(f"  ✅ Tier-1: {symbol} score={t1_score:.3f}")

            # ╔══════════════════════════════════════════════════════════════╗
            # ║  TIER 2: MARKET SCORE (Dynamic MC_min + Investors + Age)    ║
            # ╚══════════════════════════════════════════════════════════════╝
            regime_now = await self.regime.regime()
            self.dyn.recalibrate(
                balance_sol=self.dyn.base_capital_sol,
                sol_usd=sol_usd,
                regime=regime_now,
            )

            t2_score, t2_reject = self.tier2.score(onchain, cfg, self.dyn)
            if t2_reject:
                tlog.info("event=tier2_reject symbol=%s reason=%s t2=%.3f",
                          symbol, t2_reject, t2_score)
                print(f"  📊 Tier-2: {symbol} → {t2_reject}")
                return
            tlog.info("event=tier2_pass symbol=%s t2=%.3f", symbol, t2_score)
            print(f"  ✅ Tier-2: {symbol} score={t2_score:.3f}")

            # ── Kelly position sizing + slippage pre-check ────────────────────
            win_rate = await self.regime.win_rate()
            size_sol = self.risk.position_size(
                balance            = self.dyn.base_capital_sol,
                win_rate           = max(win_rate, 0.35),
                avg_win_loss_ratio = 2.0,
                kelly_fraction     = self.dyn.kelly_fraction,
                max_position_sol   = self.dyn.max_position_sol,
                min_position_sol   = self.dyn.min_position_sol,
            )
            if size_sol <= 0:
                tlog.info("event=no_edge symbol=%s kelly=0", symbol)
                return

            liquidity_sol = onchain.get("liquidity_sol") or 0.0
            slippage_est  = self.risk.estimate_slippage(size_sol, liquidity_sol)
            if slippage_est > cfg.max_slippage_pct:
                tlog.info("event=slippage_reject symbol=%s slippage=%.3f limit=%.3f",
                          symbol, slippage_est, cfg.max_slippage_pct)
                print(f"  💧 Slippage: {symbol} est={slippage_est:.2%} > limit={cfg.max_slippage_pct:.2%}")
                return

            # ── AI budget gate ────────────────────────────────────────────────
            async with self._ai_lock:
                ai_count = await self.store.count_ai_calls_today()
                if ai_count >= cfg.ai_daily_limit:
                    tlog.warning("event=ai_budget_exhausted count=%d symbol=%s",
                                 ai_count, symbol)
                    return
                # Log AI call now (before awaiting Gemini) to prevent race
                await self.store.log_ai_call(
                    symbol, mint, t1_score=t1_score, t2_score=t2_score
                )

            # ╔══════════════════════════════════════════════════════════════╗
            # ║  TIER 3: GEMINI AI SCORE  (confidence ≥ 0.85 to trade)     ║
            # ╚══════════════════════════════════════════════════════════════╝
            if not cfg.gemini_api_key:
                tlog.error("event=no_gemini_key symbol=%s", symbol)
                return

            prompt = _build_ai_prompt(token, onchain, t1_score, t2_score, cfg, regime_now)
            print(f"  🤖 Tier-3: {symbol} → calling Gemini… (AI #{ai_count+1}/{cfg.ai_daily_limit})")
            raw = await call_gemini(
                prompt, cfg.gemini_api_key,
                cfg.gemini_model, cfg.ai_timeout_seconds, self.http,
            )
            if raw is None:
                await self.breaker.record_failure()
                return

            await self.breaker.record_success()
            ai_data = _extract_ai_json(raw)
            if ai_data is None:
                tlog.warning("event=ai_parse_fail symbol=%s raw=%.80s", symbol, raw)
                return

            should_buy:  bool  = bool(ai_data.get("buy", False))
            confidence:  float = float(ai_data.get("confidence", 0.0))
            risk_label:  str   = ai_data.get("primary_risk", "unknown")

            # Update AI log with actual confidence
            await self.store.log_ai_call(
                symbol, mint, t1_score=t1_score, t2_score=t2_score, confidence=confidence
            )

            tlog.info(
                "event=tier3_result symbol=%s buy=%s confidence=%.2f risk=%s",
                symbol, should_buy, confidence, risk_label,
            )

            if not should_buy or confidence < self.dyn.min_ai_confidence:
                tlog.info(
                    "event=tier3_reject symbol=%s confidence=%.2f threshold=%.2f risk=%s",
                    symbol, confidence, self.dyn.min_ai_confidence, risk_label,
                )
                print(
                    f"  🤖 Tier-3: {symbol} REJECTED — "
                    f"confidence={confidence:.2f} < {self.dyn.min_ai_confidence:.2f} | risk={risk_label}"
                )
                return

            print(
                f"  🟢 Tier-3: {symbol} APPROVED — "
                f"confidence={confidence:.2f} | T1={t1_score:.2f} | T2={t2_score:.2f}"
            )

            # ── Risk limits (SAFETY SYSTEM) ────────────────────────────────────
            if self._daily_pnl < -cfg.max_daily_loss_sol:
                tlog.warning("event=daily_loss_limit symbol=%s pnl=%.4f",
                             symbol, self._daily_pnl)
                return
            if self._consec_losses >= cfg.max_consecutive_losses:
                tlog.warning("event=consec_loss_limit symbol=%s count=%d",
                             symbol, self._consec_losses)
                return

            # ── Execute buy ───────────────────────────────────────────────────
            entry_mc = onchain.get("market_cap")
            success  = await self._execute_buy(mint, symbol, size_sol, tlog)
            if not success:
                return

            position = {
                "status":         "OPEN",
                "trade_id":       trade_id,
                "symbol":         symbol,
                "mint":           mint,
                "entry_mc":       entry_mc,
                "ath_mc":         entry_mc or 0.0,
                "investment_sol": size_sol,
                "entry_time":     time.time(),
                "t1_score":       round(t1_score, 4),
                "t2_score":       round(t2_score, 4),
                "ai_confidence":  confidence,
                "regime":         regime_now,
                "exit_mc":        "",
                "exit_reason":    "",
                "hold_minutes":   "",
                "gross_pnl_sol":  "",
                "net_pnl_sol":    "",
                "timestamp":      datetime.now(timezone.utc).isoformat(),
                # Runtime fields (not CSV columns)
                "last_ws_update": time.time(),
            }

            async with self._pos_lock:
                self.active_positions[mint] = position

            await self.store.upsert_position(position)
            await self.trade_counter.increment()

            tlog.info(
                "event=position_opened symbol=%s size=%.4f entry_mc=%s "
                "t1=%.3f t2=%.3f ai=%.2f regime=%s",
                symbol, size_sol, entry_mc, t1_score, t2_score, confidence, regime_now,
            )
            print(
                f"\n🔥 SNIPE: {symbol} | T1={t1_score:.2f} T2={t2_score:.2f} AI={confidence:.2f} | "
                f"size={size_sol:.4f} SOL | MC={entry_mc} | "
                f"mode={'SIM' if cfg.paper_mode else 'LIVE'}"
            )

            async with self._pos_lock:
                n_open = len(self.active_positions)

            await self.tg.snipe(
                symbol=symbol,
                t1_score=t1_score,
                t2_score=t2_score,
                confidence=confidence,
                size_sol=size_sol,
                entry_mc=entry_mc,
                regime=regime_now,
                holders=onchain.get("holder_count"),
                top10=onchain.get("top10_concentration_pct"),
                trades_5m=onchain.get("trade_count_5m"),
                slot=n_open,
                max_slots=cfg.max_open_positions,
                trade_id=trade_id,
                paper_mode=cfg.paper_mode,
            )

        except Exception as exc:
            log.error(
                "event=process_token_error symbol=%s err=%s",
                symbol, exc, exc_info=True,
            )
        finally:
            self.pending_mints.discard(mint)

    # ─────────────────────────────────────────────────────────────────────────
    #  Buy execution
    # ─────────────────────────────────────────────────────────────────────────

    async def _execute_buy(
        self,
        mint: str,
        symbol: str,
        amount_sol: float,
        tlog: TradeLogAdapter,
    ) -> bool:
        if self.cfg.paper_mode:
            cost = self.paper.buy(amount_sol)
            tlog.info(
                "event=paper_buy symbol=%s amount=%.4f simulated_cost=%.4f",
                symbol, amount_sol, cost,
            )
            return True

        # Live path — pumpportal.fun bot-trading endpoint
        tip = await self.jito.get_tip(urgent=False)
        tlog.info("event=live_buy symbol=%s amount=%.4f jito_tip=%d",
                  symbol, amount_sol, tip)

        url     = "https://pumpportal.fun/api/trade-local"
        payload = {
            "publicKey":        os.environ.get("BOT_PUBLIC_KEY", ""),
            "action":           "buy",
            "mint":             mint,
            "denominatedInSol": "true",
            "amount":           amount_sol,
            "slippage":         15,
            "priorityFee":      tip / 1e9,
            "pool":             "pump",
        }
        resp = await http_post_json(
            self.http, url, payload, domain="pumpportal"
        )
        if resp and resp.status_code == 200:
            tlog.info("event=live_buy_ok symbol=%s", symbol)
            return True

        tlog.warning("event=live_buy_fail symbol=%s status=%s",
                     symbol, resp.status_code if resp else "None")
        return False

    # ─────────────────────────────────────────────────────────────────────────
    #  Worker pool
    # ─────────────────────────────────────────────────────────────────────────

    async def worker(self, worker_id: int) -> None:
        log.info("event=worker_ready id=%d", worker_id)
        while not self.shutdown_event.is_set():
            try:
                token = await asyncio.wait_for(
                    self.token_queue.get(), timeout=1.0
                )
                try:
                    await self.process_token(token)
                except Exception as exc:
                    log.error("event=worker_unhandled id=%d err=%s",
                              worker_id, exc)
                finally:
                    self.token_queue.task_done()
            except asyncio.TimeoutError:
                continue
        log.info("event=worker_stopped id=%d", worker_id)

    # ─────────────────────────────────────────────────────────────────────────
    #  Three-layer exit monitor  (SAFETY: 20-min hard kill)
    # ─────────────────────────────────────────────────────────────────────────

    async def exit_monitor(self) -> None:
        """
        Layer 1 (Primary)   — pumpportal.fun WebSocket trailing-stop (15% from ATH).
        Layer 2 (Secondary) — HTTP polling fallback every 30 s if WS stale.
        Layer 3 (Tertiary)  — Hard 20-min max_hold_minutes kill (SAFETY SYSTEM).
        """
        subscribed: Set[str] = set()
        log.info("event=exit_monitor_start")

        while not self.shutdown_event.is_set():
            try:
                async with websockets.connect(
                    "wss://pumpportal.fun/api/data"
                ) as ws:
                    log.info("event=exit_ws_connected")

                    while not self.shutdown_event.is_set():
                        async with self._pos_lock:
                            current_mints = set(self.active_positions.keys())
                        new_mints = current_mints - subscribed
                        if new_mints:
                            await ws.send(json.dumps({
                                "method": "subscribeTokenTrade",
                                "keys":   list(new_mints),
                            }))
                            subscribed.update(new_mints)

                        now = time.time()
                        mints_to_close: List[Tuple[str, float, str]] = []

                        async with self._pos_lock:
                            snapshot = dict(self.active_positions)

                        for mint, pos in snapshot.items():
                            hold_min = (now - pos.get("entry_time", now)) / 60.0

                            # Layer 3: Hard 20-min kill switch (SAFETY)
                            if hold_min >= self.cfg.max_hold_minutes:
                                log.warning(
                                    "event=hard_timeout symbol=%s hold=%.1fm limit=%.0fm",
                                    pos["symbol"], hold_min, self.cfg.max_hold_minutes,
                                )
                                mints_to_close.append((
                                    mint,
                                    pos.get("entry_mc", 0.0),
                                    "hard_timeout_20min",
                                ))
                                continue

                            # Layer 2: Stale WS → poll pumpportal.fun
                            stale = (
                                now - pos.get("last_ws_update", now)
                                > self.cfg.poll_fallback_seconds
                            )
                            if stale:
                                resp = await http_get(
                                    self.http,
                                    f"https://pumpportal.fun/api/coins/{mint}",
                                    "pumpportal",
                                )
                                if resp and resp.status_code == 200:
                                    try:
                                        mc = float(
                                            resp.json().get("market_cap") or 0
                                        )
                                        if mc > 0:
                                            async with self._pos_lock:
                                                if mint in self.active_positions:
                                                    p = self.active_positions[mint]
                                                    if mc > p["ath_mc"]:
                                                        p["ath_mc"] = mc
                                                    ath = p["ath_mc"]
                                                    p["last_ws_update"] = now
                                            stop = ath * (1.0 - self.cfg.trail_pct)
                                            if ath > 0 and mc < stop:
                                                mints_to_close.append((
                                                    mint, mc,
                                                    "trailing_stop_poll",
                                                ))
                                    except Exception:
                                        pass

                        for mint, mc, reason in mints_to_close:
                            await self.close_position(mint, mc, reason)
                            subscribed.discard(mint)

                        # Layer 1: WebSocket price tick
                        try:
                            msg  = await asyncio.wait_for(ws.recv(), timeout=2.0)
                            data = json.loads(msg)
                            mint       = data.get("mint")
                            current_mc = float(data.get("marketCapSol") or 0)

                            if not mint or current_mc <= 0:
                                continue

                            should_close: Optional[Tuple[str, float, str]] = None
                            async with self._pos_lock:
                                if mint in self.active_positions:
                                    pos = self.active_positions[mint]
                                    pos["last_ws_update"] = time.time()
                                    if current_mc > pos["ath_mc"]:
                                        pos["ath_mc"] = current_mc
                                    stop = pos["ath_mc"] * (1.0 - self.cfg.trail_pct)
                                    if pos["ath_mc"] > 0 and current_mc < stop:
                                        should_close = (
                                            mint, current_mc,
                                            "trailing_stop_ws",
                                        )

                            if should_close:
                                await self.close_position(*should_close)
                                subscribed.discard(should_close[0])

                        except asyncio.TimeoutError:
                            continue
                        except (json.JSONDecodeError, ValueError):
                            continue

            except Exception as exc:
                if self.shutdown_event.is_set():
                    return
                log.warning("event=exit_ws_dropped err=%s reconnecting_3s", exc)
                await asyncio.sleep(3)

    # ─────────────────────────────────────────────────────────────────────────
    #  Token stream (pumpportal.fun WebSocket)
    # ─────────────────────────────────────────────────────────────────────────

    _RUG_RE = re.compile(
        r"\b(rug|scam|fake|honeypot|test)\b", re.IGNORECASE
    )

    async def token_stream(self) -> None:
        log.info("event=token_stream_start")
        while not self.shutdown_event.is_set():
            try:
                async with websockets.connect(
                    "wss://pumpportal.fun/api/data"
                ) as ws:
                    await ws.send(json.dumps({"method": "subscribeNewToken"}))
                    log.info("event=pumpportal_ws_connected")
                    async for msg in ws:
                        if self.shutdown_event.is_set():
                            break
                        try:
                            token = json.loads(msg)
                        except json.JSONDecodeError:
                            continue
                        if "mint" not in token:
                            continue
                        if not (token.get("twitter") or token.get("telegram")):
                            continue
                        if self._RUG_RE.search(token.get("symbol", "")):
                            continue
                        try:
                            self.token_queue.put_nowait(token)
                        except asyncio.QueueFull:
                            log.debug(
                                "event=queue_full dropping=%s",
                                token.get("symbol", "???"),
                            )
            except Exception as exc:
                if self.shutdown_event.is_set():
                    break
                log.warning(
                    "event=pumpportal_ws_dropped err=%s reconnect_2s", exc
                )
                await asyncio.sleep(2)

    # ─────────────────────────────────────────────────────────────────────────
    #  Graceful shutdown (KILL SWITCH)
    # ─────────────────────────────────────────────────────────────────────────

    async def shutdown(self) -> None:
        log.info("event=shutdown_start")
        await self.tg.shutdown_msg()
        await self.close_all_positions("kill_switch_shutdown")
        log.info(
            "event=shutdown_complete daily_pnl=%.4f csv=%s",
            self._daily_pnl, self.cfg.active_csv,
        )
        print(f"\n💾 All data saved to: {self.cfg.active_csv}")
        print(
            f"👋 ARC Singularity v3.0 shutdown complete. "
            f"Daily P/L: {self._daily_pnl:+.4f} SOL"
        )

    # ─────────────────────────────────────────────────────────────────────────
    #  Main run loop
    # ─────────────────────────────────────────────────────────────────────────

    async def run(self) -> None:
        await self.restore_state()

        def _on_signal() -> None:
            log.info("event=signal_received activating_kill_switch")
            self.shutdown_event.set()

        _loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                _loop.add_signal_handler(sig, _on_signal)
            except (NotImplementedError, AttributeError):
                signal.signal(sig, lambda *_: _on_signal())

        workers = [
            asyncio.create_task(self.worker(i + 1), name=f"worker-{i+1}")
            for i in range(self.cfg.worker_count)
        ]

        background_tasks = [
            asyncio.create_task(self.exit_monitor(), name="exit_monitor"),
            asyncio.create_task(
                self.rpc_pool.run_ping_loop(
                    self.cfg.rpc_ping_interval_s, self.shutdown_event
                ),
                name="rpc_pinger",
            ),
        ]

        async def _shutdown_watcher() -> None:
            while not self.shutdown_event.is_set():
                await asyncio.sleep(0.5)
            await self.shutdown()
            all_tasks = workers + background_tasks
            for t in all_tasks:
                t.cancel()
            await asyncio.gather(*all_tasks, return_exceptions=True)

        shutdown_task = asyncio.create_task(
            _shutdown_watcher(), name="shutdown_watcher"
        )

        await self.token_stream()

        if not shutdown_task.done():
            await shutdown_task

        await self.token_queue.join()
        log.info("event=run_complete")


# ══════════════════════════════════════════════════════════════════════════════
#  ENTRY POINT
# ══════════════════════════════════════════════════════════════════════════════

async def main() -> None:
    parser = argparse.ArgumentParser(
        description="ARC Singularity Engine v3.0 — Hacksagon 2026 · App Development Track"
    )
    parser.add_argument(
        "--live", action="store_true",
        help="Enable live trading (requires paper_mode=False in StaticConfig)",
    )
    args = parser.parse_args()

    cfg = StaticConfig()

    if not cfg.paper_mode:
        if cfg.require_live_flag and not args.live:
            print(
                "\n⛔  LIVE MODE BLOCKED\n"
                "  paper_mode=False but --live flag not provided.\n"
                "  Run:  python singularity.py --live\n"
            )
            return
        print("\n" + "=" * 60)
        print("  🔴 LIVE TRADING MODE — REAL FUNDS AT RISK")
        print("=" * 60)
        print("\nStarting in 10 seconds… Press Ctrl+C to abort.\n")
        for i in range(10, 0, -1):
            print(f"  {i}…", end="\r", flush=True)
            await asyncio.sleep(1)
        print("  Starting.               ")

    cfg.validate()

    log.info(
        "event=startup mode=%s csv=%s workers=%d queue=%d "
        "ai=%s ai_daily=%d t1_gate=%.2f t2_gate=%.2f "
        "survival=%ds recheck=%ds hold_limit=%.0fm",
        "PAPER" if cfg.paper_mode else "LIVE",
        cfg.active_csv,
        cfg.worker_count, cfg.queue_max_size,
        cfg.gemini_model, cfg.ai_daily_limit,
        cfg.min_t1_score, cfg.min_t2_score,
        cfg.survival_seconds, cfg.recheck_seconds,
        cfg.max_hold_minutes,
    )

    print("=" * 65)
    print("🚀 ARC — ADAPTIVE RISK CORE  ·  Singularity Engine v3.0")
    print("👑 Team Pheonex  |  🏆 Hacksagon 2026 · App Development Track")
    print("=" * 65)
    print(f"📊 Mode:      {'🧪 SIMULATION' if cfg.paper_mode else '🔴 LIVE TRADING'}")
    print(f"💾 CSV:       {cfg.active_csv}")
    print(f"⚙️  Workers:   {cfg.worker_count}  |  Queue: {cfg.queue_max_size}")
    print(f"🤖 AI:        {cfg.gemini_model}  |  Limit: {cfg.ai_daily_limit}/day")
    print(f"📐 Tier-1:    Math score gate ≥ {cfg.min_t1_score}")
    print(f"   Formulas: M=trades/volume · S=concentration·holders · V=bonding·replies")
    print(f"   Weights:  0.40·M + 0.40·S + 0.20·V")
    print(f"📊 Tier-2:    Market score gate ≥ {cfg.min_t2_score}")
    print(f"   Formulas: I=log₂(holders) · L=liq/MC · A=coin_age")
    print(f"   Weights:  0.35·I + 0.35·L + 0.30·A  |  MC_min={cfg.base_mc_min_sol}×regime")
    print(f"🤖 Tier-3:    Gemini AI  |  confidence ≥ 0.85 (regime-adaptive)")
    print(f"🛡️  Safety:    Kill switch  |  {cfg.max_hold_minutes:.0f}min hard limit  |  15% trail stop")
    print(f"⏱️  Pipeline:  {cfg.survival_seconds}s wait → MC check → "
          f"{cfg.recheck_seconds}s recheck → T1 → T2 → T3 AI")
    print("=" * 65)

    http = httpx.AsyncClient(
        timeout=12.0,
        limits=httpx.Limits(
            max_connections=100, max_keepalive_connections=40
        ),
    )

    store    = CsvStore(cfg)
    dyn      = DynamicConfig()
    if cfg.paper_mode:
        dyn.base_capital_sol = cfg.paper_starting_balance_sol

    telegram = TelegramAlerter(cfg.telegram_token, cfg.telegram_chat_id, http)
    engine   = SingularityEngine(cfg, dyn, store, telegram, http)

    # Crash safety: mark open positions if process dies hard
    atexit.register(store.mark_open_as_crashed)

    await telegram.startup_msg(cfg)

    try:
        await engine.run()
    finally:
        try:
            atexit.unregister(store.mark_open_as_crashed)
        except Exception:
            pass
        await http.aclose()
        log.info("event=http_closed")


if __name__ == "__main__":
    asyncio.run(main())
