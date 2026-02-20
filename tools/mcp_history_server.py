#!/usr/bin/env python3
"""
MCP server for Yomidroid lookup history.

Pulls the Room SQLite database from a connected Android device via adb
and exposes tools for querying lookup history (immersion tracking).

Usage:
    python3 tools/mcp_history_server.py

MCP config (~/.claude/claude_code_config.json):
    {
      "mcpServers": {
        "yomidroid-history": {
          "command": "python3",
          "args": ["tools/mcp_history_server.py"]
        }
      }
    }
"""

import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

# MCP protocol constants
JSONRPC_VERSION = "2.0"
MCP_PROTOCOL_VERSION = "2024-11-05"

DB_PATH = os.path.join(tempfile.gettempdir(), "yomidroid_history.db")
PACKAGE = "com.yomidroid"
DB_NAME = "yomidroid_history.db"

# Cache: don't re-pull if pulled recently (seconds)
PULL_CACHE_TTL = 30
_last_pull_time = 0.0


def pull_database() -> str:
    """Pull the Room database from the device via adb. Returns path to local copy."""
    global _last_pull_time

    now = time.time()
    if now - _last_pull_time < PULL_CACHE_TTL and os.path.exists(DB_PATH):
        return DB_PATH

    try:
        result = subprocess.run(
            ["adb", "exec-out", f"run-as {PACKAGE} cat databases/{DB_NAME}"],
            capture_output=True,
            timeout=10,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"adb failed (code {result.returncode}): {result.stderr.decode(errors='replace')}"
            )
        if len(result.stdout) < 100:
            raise RuntimeError(
                f"Database file too small ({len(result.stdout)} bytes) — "
                "is the app installed and has history?"
            )
        with open(DB_PATH, "wb") as f:
            f.write(result.stdout)
        _last_pull_time = now
        return DB_PATH
    except FileNotFoundError:
        raise RuntimeError("adb not found — is Android SDK platform-tools on PATH?")
    except subprocess.TimeoutExpired:
        raise RuntimeError("adb timed out — is a device connected?")


def query_db(sql: str, params: tuple = ()) -> list[dict]:
    """Execute a query against the pulled database and return rows as dicts."""
    db_path = pull_database()
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    try:
        rows = conn.execute(sql, params).fetchall()
        return [dict(row) for row in rows]
    finally:
        conn.close()


def format_timestamp(ts_ms: int) -> str:
    """Convert epoch milliseconds to ISO 8601 string."""
    dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
    return dt.isoformat()


def format_lookups(rows: list[dict]) -> list[dict]:
    """Format raw DB rows into clean lookup records."""
    results = []
    for row in rows:
        entry = {
            "id": row["id"],
            "word": row["word"],
            "reading": row["reading"],
            "definition": row["definition"],
            "sentence": row.get("sentence"),
            "timestamp": format_timestamp(row["timestamp"]),
            "timestamp_ms": row["timestamp"],
        }
        # Include source app fields if present
        if row.get("source_app_label"):
            entry["source_app"] = row["source_app_label"]
        if row.get("source_package"):
            entry["source_package"] = row["source_package"]
        if row.get("source_window_title"):
            entry["source_window_title"] = row["source_window_title"]
        results.append(entry)
    return results


# ─── Tool implementations ───────────────────────────────────────────

def get_recent_lookups(limit: int = 20) -> dict:
    """Get the most recent N lookup history entries."""
    rows = query_db(
        "SELECT * FROM lookup_history ORDER BY timestamp DESC LIMIT ?",
        (limit,),
    )
    return {"count": len(rows), "lookups": format_lookups(rows)}


def search_lookups(query: str) -> dict:
    """Search lookups by word, reading, or definition."""
    rows = query_db(
        """
        SELECT * FROM lookup_history
        WHERE word LIKE '%' || ? || '%'
           OR reading LIKE '%' || ? || '%'
           OR definition LIKE '%' || ? || '%'
        ORDER BY timestamp DESC
        """,
        (query, query, query),
    )
    return {"query": query, "count": len(rows), "lookups": format_lookups(rows)}


def get_lookup_stats() -> dict:
    """Get aggregate statistics about lookup history."""
    total = query_db("SELECT COUNT(*) as c FROM lookup_history")[0]["c"]
    unique = query_db("SELECT COUNT(DISTINCT word) as c FROM lookup_history")[0]["c"]

    # Lookups per day (last 30 days)
    thirty_days_ago = int((time.time() - 30 * 86400) * 1000)
    daily = query_db(
        """
        SELECT date(timestamp/1000, 'unixepoch') as day, COUNT(*) as count
        FROM lookup_history
        WHERE timestamp > ?
        GROUP BY day
        ORDER BY day DESC
        """,
        (thirty_days_ago,),
    )

    # Most looked-up words (top 20)
    top_words = query_db(
        """
        SELECT word, reading, COUNT(*) as count
        FROM lookup_history
        GROUP BY word
        ORDER BY count DESC
        LIMIT 20
        """
    )

    # Lookups by source app
    by_app = query_db(
        """
        SELECT COALESCE(source_app_label, 'Unknown') as app,
               COUNT(*) as count
        FROM lookup_history
        GROUP BY app
        ORDER BY count DESC
        LIMIT 10
        """
    )

    return {
        "total_lookups": total,
        "unique_words": unique,
        "lookups_per_day": [{"date": d["day"], "count": d["count"]} for d in daily],
        "most_looked_up": [
            {"word": w["word"], "reading": w["reading"], "count": w["count"]}
            for w in top_words
        ],
        "by_app": [{"app": a["app"], "count": a["count"]} for a in by_app],
    }


def get_lookups_since(hours_ago: float = 1.0) -> dict:
    """Get all lookups in the last N hours (for session review)."""
    since_ms = int((time.time() - hours_ago * 3600) * 1000)
    rows = query_db(
        "SELECT * FROM lookup_history WHERE timestamp > ? ORDER BY timestamp DESC",
        (since_ms,),
    )
    return {
        "hours_ago": hours_ago,
        "count": len(rows),
        "lookups": format_lookups(rows),
    }


def get_familiarity_report(limit: int = 50) -> dict:
    """Get words ranked by lookup frequency with recency weighting."""
    rows = query_db(
        """
        SELECT word, reading,
               COUNT(*) as total_lookups,
               MAX(timestamp) as last_seen,
               GROUP_CONCAT(DISTINCT source_app_label) as source_apps
        FROM lookup_history
        GROUP BY word
        HAVING total_lookups > 1
        ORDER BY total_lookups DESC
        LIMIT ?
        """,
        (limit,),
    )

    now_ms = time.time() * 1000
    results = []
    for row in rows:
        days_since_last = (now_ms - row["last_seen"]) / (1000 * 86400)
        score = row["total_lookups"] * (1.0 / (1 + days_since_last / 7.0))
        results.append({
            "word": row["word"],
            "reading": row["reading"],
            "total_lookups": row["total_lookups"],
            "last_seen": format_timestamp(row["last_seen"]),
            "familiarity_score": round(score, 2),
            "source_apps": [a for a in (row["source_apps"] or "").split(",") if a],
        })

    # Re-sort by familiarity score
    results.sort(key=lambda x: x["familiarity_score"], reverse=True)

    return {"count": len(results), "words": results}


def get_lookups_by_app() -> dict:
    """Get lookup stats grouped by source app."""
    rows = query_db(
        """
        SELECT COALESCE(source_app_label, 'Unknown') as app,
               COUNT(*) as total_lookups,
               COUNT(DISTINCT word) as unique_words,
               MIN(timestamp) as first_lookup,
               MAX(timestamp) as last_lookup
        FROM lookup_history
        GROUP BY app
        ORDER BY total_lookups DESC
        """
    )

    results = []
    for row in rows:
        results.append({
            "app": row["app"],
            "total_lookups": row["total_lookups"],
            "unique_words": row["unique_words"],
            "first_lookup": format_timestamp(row["first_lookup"]),
            "last_lookup": format_timestamp(row["last_lookup"]),
        })

    return {"count": len(results), "apps": results}


# ─── MCP protocol handling ──────────────────────────────────────────

TOOLS = [
    {
        "name": "get_recent_lookups",
        "description": "Get the most recent N words looked up in Yomidroid, with timestamps, readings, definitions, and sentences.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of lookups to return (default 20)",
                    "default": 20,
                },
            },
        },
    },
    {
        "name": "search_lookups",
        "description": "Search Yomidroid lookup history by word, reading, or definition.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search term to match against word, reading, or definition",
                },
            },
            "required": ["query"],
        },
    },
    {
        "name": "get_lookup_stats",
        "description": "Get aggregate statistics: total lookups, unique words, lookups per day (last 30 days), and most looked-up words.",
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "get_lookups_since",
        "description": "Get all lookups from the last N hours. Useful for reviewing an immersion session.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "hours_ago": {
                    "type": "number",
                    "description": "How many hours back to look (default 1.0)",
                    "default": 1.0,
                },
            },
        },
    },
    {
        "name": "get_familiarity_report",
        "description": "Get words ranked by lookup frequency with recency weighting. Words looked up many times recently score highest — useful for identifying words you keep forgetting.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of words to return (default 50)",
                    "default": 50,
                },
            },
        },
    },
    {
        "name": "get_lookups_by_app",
        "description": "Get lookup stats grouped by source app. Shows which games/apps generate the most lookups, with unique word counts and date ranges.",
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
    },
]


def handle_request(request: dict) -> dict:
    """Process a single JSON-RPC request and return a response."""
    method = request.get("method", "")
    req_id = request.get("id")
    params = request.get("params", {})

    if method == "initialize":
        return {
            "jsonrpc": JSONRPC_VERSION,
            "id": req_id,
            "result": {
                "protocolVersion": MCP_PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": {
                    "name": "yomidroid-history",
                    "version": "1.0.0",
                },
            },
        }

    if method == "notifications/initialized":
        return None  # No response for notifications

    if method == "tools/list":
        return {
            "jsonrpc": JSONRPC_VERSION,
            "id": req_id,
            "result": {"tools": TOOLS},
        }

    if method == "tools/call":
        tool_name = params.get("name", "")
        arguments = params.get("arguments", {})

        try:
            if tool_name == "get_recent_lookups":
                result = get_recent_lookups(arguments.get("limit", 20))
            elif tool_name == "search_lookups":
                result = search_lookups(arguments["query"])
            elif tool_name == "get_lookup_stats":
                result = get_lookup_stats()
            elif tool_name == "get_lookups_since":
                result = get_lookups_since(arguments.get("hours_ago", 1.0))
            elif tool_name == "get_familiarity_report":
                result = get_familiarity_report(arguments.get("limit", 50))
            elif tool_name == "get_lookups_by_app":
                result = get_lookups_by_app()
            else:
                return {
                    "jsonrpc": JSONRPC_VERSION,
                    "id": req_id,
                    "error": {"code": -32601, "message": f"Unknown tool: {tool_name}"},
                }

            return {
                "jsonrpc": JSONRPC_VERSION,
                "id": req_id,
                "result": {
                    "content": [
                        {"type": "text", "text": json.dumps(result, indent=2, ensure_ascii=False)}
                    ]
                },
            }
        except Exception as e:
            return {
                "jsonrpc": JSONRPC_VERSION,
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": f"Error: {e}"}],
                    "isError": True,
                },
            }

    # Unknown method
    if req_id is not None:
        return {
            "jsonrpc": JSONRPC_VERSION,
            "id": req_id,
            "error": {"code": -32601, "message": f"Unknown method: {method}"},
        }
    return None


def main():
    """Run the MCP server using stdio transport."""
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            response = {
                "jsonrpc": JSONRPC_VERSION,
                "id": None,
                "error": {"code": -32700, "message": "Parse error"},
            }
            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()
            continue

        response = handle_request(request)
        if response is not None:
            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
