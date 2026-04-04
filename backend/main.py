"""
====================================================================
🚀 ARC — Adaptive Risk Core  ·  API Server v3.0
👑 Team Pheonex  |  🏆 Hacksagon 2026 · App Development Track
====================================================================
FastAPI server connecting the Singularity Engine v3.0 to the
Android dashboard app.

Endpoints:
  POST /start      — Launch the Singularity engine (background task)
  POST /stop       — Signal graceful shutdown (kill switch)
  GET  /stats      — Current P/L, tier scores, trade stats, AI usage
  GET  /health     — Health check with engine status
  POST /config     — Toggle simulate mode (paper/live) before start
  WS   /ws/logs    — Real-time token scan stream to Android UI
====================================================================
"""

import asyncio
import atexit
import csv
import json
import os
from contextlib import asynccontextmanager
from datetime import datetime

import httpx
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Header, HTTPException, Depends
from fastapi.middleware.cors import CORSMiddleware

# ── Patch print() → WebSocket broadcast ───────────────────────────
import builtins
_original_print = builtins.print


def _ws_print(*args, **kwargs):
    msg = " ".join(str(a) for a in args)
    _original_print(msg, **kwargs)
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            asyncio.ensure_future(_broadcaster.broadcast(msg))
    except RuntimeError:
        pass


builtins.print = _ws_print


# ══════════════════════════════════════════════════════════════════
#  🔑 API Key Authentication
#  CHANGE 1: Added require_api_key dependency.
#  The key is read from the ARC_API_KEY env var (see .env.example),
#  defaulting to the value the Android app hard-codes.
#  Applied to all REST endpoints; WebSocket is exempt per spec.
# ══════════════════════════════════════════════════════════════════

_API_KEY = os.getenv("ARC_API_KEY", "arc-pheonex-2026")


async def require_api_key(x_arc_key: str = Header(default="")) -> None:
    """FastAPI dependency — rejects requests without the correct header."""
    if x_arc_key != _API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing X-ARC-Key header")


# ══════════════════════════════════════════════════════════════════
#  📡 WebSocket connection manager
# ══════════════════════════════════════════════════════════════════

class LogBroadcaster:
    def __init__(self):
        self.clients: list[WebSocket] = []
        self._lock = asyncio.Lock()

    async def connect(self, ws: WebSocket):
        await ws.accept()
        async with self._lock:
            self.clients.append(ws)

    async def disconnect(self, ws: WebSocket):
        async with self._lock:
            if ws in self.clients:
                self.clients.remove(ws)

    async def broadcast(self, message: str):
        payload = json.dumps({
            "ts":  datetime.now().strftime("%H:%M:%S"),
            "msg": message,
        })
        dead = []
        for ws in list(self.clients):
            try:
                await ws.send_text(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            await self.disconnect(ws)


_broadcaster = LogBroadcaster()

# ── Engine state (populated on /start) ────────────────────────────
engine_task: asyncio.Task | None = None
_engine  = None   # SingularityEngine instance
_store   = None   # CsvStore instance
_cfg     = None   # StaticConfig instance

# CHANGE 2: Added wallet_sol to stats_store so Android StatsResponse.walletSol
# is satisfied (maps to SOL balance; engine populates when running).
stats_store = {
    "engine_running":    False,
    "mode":              "SIMULATION",
    "trades_open":       0,
    "trades_closed":     0,
    "win_rate":          0.0,
    "avg_profit_pct":    0.0,
    "ai_calls_today":    0,
    "ai_calls_limit":    20,
    "tokens_scanned":    0,
    "avg_t1_score":      0.0,
    "avg_t2_score":      0.0,
    "avg_ai_confidence": 0.0,
    "started_at":        None,
    "daily_pnl_sol":     0.0,
    "wallet_sol":        0.0,   # ← new: current SOL balance shown in Android
}

# CHANGE 3: simulate override — set by /config before engine starts.
# Allows Android simulate toggle to influence engine mode at launch time
# without touching singularity.py.
_simulate_override: bool | None = None


# ══════════════════════════════════════════════════════════════════
#  Trade analysis helpers (with caching)
# ══════════════════════════════════════════════════════════════════

_stats_cache = {"data": {}, "mtime": 0}


def _safe_float(v) -> float | None:
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def read_and_analyze_trades(csv_path: str) -> dict:
    if not os.path.isfile(csv_path):
        return {}

    mtime = os.path.getmtime(csv_path)
    if _stats_cache["mtime"] == mtime:
        return _stats_cache["data"]

    trades = []
    with open(csv_path, newline="") as fh:
        for row in csv.DictReader(fh):
            if row.get("status") != "CLOSED":
                continue
            gross = _safe_float(row.get("gross_pnl_sol"))
            inv   = _safe_float(row.get("investment_sol"))
            if gross is None or not inv:
                continue
            row["_profit_pct"] = gross / inv * 100.0
            trades.append(row)

    if not trades:
        return {}

    wins   = [t for t in trades if t["_profit_pct"] > 0]
    all_p  = [t["_profit_pct"] for t in trades]
    t1s    = [_safe_float(t.get("t1_score")) for t in trades if _safe_float(t.get("t1_score"))]
    t2s    = [_safe_float(t.get("t2_score")) for t in trades if _safe_float(t.get("t2_score"))]
    ais    = [_safe_float(t.get("ai_confidence")) for t in trades if _safe_float(t.get("ai_confidence"))]

    analysis = {
        "total_trades":      len(trades),
        "win_count":         len(wins),
        "loss_count":        len(trades) - len(wins),
        "win_rate":          len(wins) / len(trades) if trades else 0.0,
        "avg_profit_pct":    sum(all_p) / len(all_p) if all_p else 0.0,
        "best_trade_pct":    max(all_p) if all_p else 0.0,
        "worst_trade_pct":   min(all_p) if all_p else 0.0,
        "avg_t1_score":      sum(t1s) / len(t1s) if t1s else 0.0,
        "avg_t2_score":      sum(t2s) / len(t2s) if t2s else 0.0,
        "avg_ai_confidence": sum(ais) / len(ais) if ais else 0.0,
    }

    _stats_cache["data"] = analysis
    _stats_cache["mtime"] = mtime
    return analysis


# ══════════════════════════════════════════════════════════════════
#  App lifecycle
# ══════════════════════════════════════════════════════════════════

@asynccontextmanager
async def lifespan(app: FastAPI):
    print("🚀 ARC Singularity v3.0 API Server Online — App Development Track · Hacksagon 2026")
    print("👑 Team Pheonex")
    yield
    print("🛑 ARC Server shutting down…")
    if _engine is not None:
        _engine.shutdown_event.set()


app = FastAPI(
    title="ARC — Adaptive Risk Core v3.0",
    description=(
        "AI-Powered Solana Meme Coin Sniper — Three-Tier Pipeline Architecture.\n"
        "Tier-1: Math Scoring | Tier-2: Market Analysis | Tier-3: Gemini AI"
    ),
    version="3.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ══════════════════════════════════════════════════════════════════
#  REST Endpoints
#  CHANGE 4: Every REST endpoint now carries Depends(require_api_key).
#  The WebSocket /ws/logs is intentionally excluded (auth over WS
#  requires a different flow and the spec says REST-only).
# ══════════════════════════════════════════════════════════════════

@app.post("/start", tags=["Engine"], dependencies=[Depends(require_api_key)])
async def start_engine():
    """Launch the Singularity v3 engine as a background asyncio task."""
    global engine_task, _engine, _store, _cfg

    if engine_task and not engine_task.done():
        return {"status": "already_running", "message": "Engine is already active."}

    try:
        # CHANGE 5: Honor the simulate override set via /config.
        # Writing to os.environ here is safe because StaticConfig() reads
        # it at construction time, and we're still single-process.
        if _simulate_override is not None:
            os.environ["PAPER_MODE"] = "true" if _simulate_override else "false"

        from singularity import (
            StaticConfig, DynamicConfig, CsvStore,
            TelegramAlerter, SingularityEngine,
        )

        cfg = StaticConfig()
        cfg.validate()

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

        atexit.register(store.mark_open_as_crashed)

        _engine = engine
        _store  = store
        _cfg    = cfg

        async def _run():
            stats_store["engine_running"]  = True
            stats_store["started_at"]      = datetime.now().isoformat()
            stats_store["mode"]            = "SIMULATION" if cfg.paper_mode else "LIVE"
            stats_store["ai_calls_limit"]  = cfg.ai_daily_limit
            # Seed wallet balance from dynamic config
            stats_store["wallet_sol"]      = round(dyn.base_capital_sol, 6)
            try:
                await telegram.startup_msg(cfg)
                await engine.run()
            finally:
                stats_store["engine_running"] = False
                try:
                    atexit.unregister(store.mark_open_as_crashed)
                except Exception:
                    pass
                await http.aclose()

        engine_task = asyncio.create_task(_run(), name="arc-singularity-engine-v3")
        return {
            "status":  "started",
            "mode":    stats_store["mode"],
            "engine":  "ARC Singularity v3.0",
            "tiers":   "T1:Math | T2:Market | T3:Gemini",
        }

    except Exception as e:
        return {"status": "error", "message": str(e)}


@app.post("/stop", tags=["Engine"], dependencies=[Depends(require_api_key)])
async def stop_engine():
    """Activate kill switch — signals engine to shut down gracefully."""
    if _engine is None:
        return {"status": "not_running"}
    try:
        _engine.shutdown_event.set()
        return {
            "status":  "stopping",
            "message": "Kill switch activated. Closing all positions…",
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


@app.get("/stats", tags=["Stats"], dependencies=[Depends(require_api_key)])
async def get_stats():
    """Return engine stats, three-tier scores, trade metrics, and AI usage."""
    if _engine is not None and _store is not None and _cfg is not None:
        try:
            stats_store["trades_open"]   = len(_engine.active_positions)
            stats_store["ai_calls_today"] = await _store.count_ai_calls_today()
            stats_store["daily_pnl_sol"]  = round(_engine._daily_pnl, 6)
            # Refresh wallet balance from live dynamic config
            if hasattr(_engine, "dyn"):
                stats_store["wallet_sol"] = round(_engine.dyn.base_capital_sol, 6)

            analysis = read_and_analyze_trades(_cfg.active_csv)
            if analysis:
                stats_store["trades_closed"]     = analysis["total_trades"]
                stats_store["win_rate"]           = round(analysis["win_rate"] * 100, 1)
                stats_store["avg_profit_pct"]     = round(analysis["avg_profit_pct"], 2)
                stats_store["avg_t1_score"]       = round(analysis["avg_t1_score"], 3)
                stats_store["avg_t2_score"]       = round(analysis["avg_t2_score"], 3)
                stats_store["avg_ai_confidence"]  = round(analysis["avg_ai_confidence"], 3)
        except Exception:
            pass

    return stats_store


@app.get("/trades", tags=["Stats"], dependencies=[Depends(require_api_key)])
async def get_trades():
    """Return the full trade history from the active CSV file."""
    csv_path = "simulation_data.csv"
    if _cfg is not None:
        csv_path = _cfg.active_csv
    else:
        is_paper = _simulate_override if _simulate_override is not None else True
        csv_path = "simulation_data.csv" if is_paper else "real_data.csv"

    if not os.path.isfile(csv_path):
        return []

    trades = []
    try:
        with open(csv_path, newline="") as fh:
            reader = csv.DictReader(fh)
            for row in reader:
                # Type conversion for JSON compatibility
                for key in [
                    "entry_mc", "ath_mc", "investment_sol", "exit_mc",
                    "hold_minutes", "gross_pnl_sol", "net_pnl_sol",
                    "t1_score", "t2_score", "ai_confidence"
                ]:
                    if key in row:
                        row[key] = _safe_float(row[key])
                trades.append(row)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error reading trades: {str(e)}")

    return trades


@app.get("/health", tags=["Health"], dependencies=[Depends(require_api_key)])
async def health():
    return {
        "status":         "ok",
        "engine":         "ARC Singularity v3.0",
        "version":        "3.0.0",
        "engine_running": stats_store["engine_running"],
        "mode":           stats_store["mode"],
        "project":        "Hacksagon 2026 · App Development Track",
        "owners":         "Team Pheonex",
    }


# CHANGE 6: New /config endpoint wires up the Android simulate toggle.
# The Android app calls POST /config?enabled=true/false.
# This sets _simulate_override so the next /start picks it up from .env.
# Also immediately updates stats_store["mode"] for display before engine starts.
@app.post("/config", tags=["Engine"], dependencies=[Depends(require_api_key)])
async def set_config(simulate: bool):
    """Toggle simulation/live mode. Takes effect on next /start call."""
    global _simulate_override
    _simulate_override = simulate
    if not stats_store["engine_running"]:
        stats_store["mode"] = "SIMULATION" if simulate else "LIVE"
    return {
        "simulate": simulate,
        "mode":     stats_store["mode"],
        "message":  f"Mode will be {'SIMULATION' if simulate else 'LIVE'} on next engine start",
    }


# ══════════════════════════════════════════════════════════════════
#  WebSocket endpoint — no auth (spec: REST-only auth)
# ══════════════════════════════════════════════════════════════════

@app.websocket("/ws/logs")
async def ws_logs(ws: WebSocket):
    """Real-time log stream from the engine to the Android dashboard."""
    await _broadcaster.connect(ws)
    try:
        while True:
            await ws.receive_text()   # Keep alive — client sends pings
    except WebSocketDisconnect:
        await _broadcaster.disconnect(ws)


# ══════════════════════════════════════════════════════════════════
#  Entrypoint
# ══════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8001,
        reload=False,
        log_level="info",
    )
