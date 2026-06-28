#!/usr/bin/env bash
# Simulate two bot players so you can validate the UI with a real 3-player table.
# Prerequisites: backend running on :8080 (./gradlew :server-gcp:runLocal)
#
# Usage: ./simulate-bots.sh

set -euo pipefail

BASE="http://localhost:8080"
BOT1_JAR=$(mktemp /tmp/poker-bot1.XXXXXX)
BOT2_JAR=$(mktemp /tmp/poker-bot2.XXXXXX)
trap 'rm -f "$BOT1_JAR" "$BOT2_JAR"' EXIT

parse_json() { python3 -c "import sys,json; d=json.load(sys.stdin); print(d$1)" 2>/dev/null; }

echo "Waiting for server at $BASE..."
until curl -sf "$BASE/api/tables" > /dev/null 2>&1 || \
      curl -s -o /dev/null -w "%{http_code}" "$BASE/api/tables" | grep -qE '^[245]'; do
  sleep 1
done

# ── Create table as Bot1 ───────────────────────────────────────────────────────
RESPONSE=$(curl -s -c "$BOT1_JAR" -X POST "$BASE/api/tables" \
  -H "Content-Type: application/json" \
  -d '{"playerName":"Bot1","startingChips":1000,"turnTimerSeconds":120,"maxPlayers":6}')
TABLE_ID=$(echo "$RESPONSE" | parse_json "['tableId']")

if [ -z "$TABLE_ID" ]; then
  echo "Failed to create table. Is the server running?"
  exit 1
fi

# ── Bot2 joins ────────────────────────────────────────────────────────────────
JOIN2=$(curl -s -c "$BOT2_JAR" -X POST "$BASE/api/tables/$TABLE_ID/players" \
  -H "Content-Type: application/json" \
  -d '{"playerName":"Bot2"}')

# Parse player IDs so we know whose turn it is
BOT1_ID=$(curl -s -b "$BOT1_JAR" "$BASE/api/tables/$TABLE_ID" | parse_json "['players'][0]['id']") || BOT1_ID=""
BOT2_ID=$(echo "$JOIN2" | parse_json "['playerId']") || BOT2_ID=""

echo ""
echo "┌─────────────────────────────────────────────────────────────────┐"
echo "│  Table created with Bot1 and Bot2.                              │"
echo "│                                                                 │"
echo "│  Open in browser:  http://localhost:5173/table/$TABLE_ID           │"
echo "│                                                                 │"
echo "│  Join the table under any name. Then press Enter here           │"
echo "│  to start the first round.                                      │"
echo "└─────────────────────────────────────────────────────────────────┘"
read -r

# ── Start round ───────────────────────────────────────────────────────────────
curl -s -b "$BOT1_JAR" -X POST "$BASE/api/tables/$TABLE_ID/start-round" > /dev/null

echo ""
echo "Round started. Bots will call on their turns. Press Ctrl-C to stop."
echo ""

LAST_STATUS=""

while true; do
  sleep 1

  STATUS=$(curl -s "$BASE/api/tables/$TABLE_ID" | parse_json "['gameStatus']") || continue

  if [ "$STATUS" != "$LAST_STATUS" ]; then
    echo "[$(date +%H:%M:%S)] Game status: $STATUS"
    LAST_STATUS="$STATUS"
  fi

  if [ "$STATUS" = "WAITING" ]; then
    curl -s -b "$BOT1_JAR" -X POST "$BASE/api/tables/$TABLE_ID/ready" > /dev/null 2>&1 || true
    curl -s -b "$BOT2_JAR" -X POST "$BASE/api/tables/$TABLE_ID/ready" > /dev/null 2>&1 || true
  elif [ "$STATUS" = "RUNNING" ]; then
    # Only act for the bot whose turn it actually is — prevents simultaneous actions
    NEXT=$(curl -s "$BASE/api/tables/$TABLE_ID" | parse_json "['nextPlayerIdToAct']") || NEXT=""
    if [ "$NEXT" = "$BOT1_ID" ]; then
      sleep 1.5
      curl -s -b "$BOT1_JAR" -X POST "$BASE/api/tables/$TABLE_ID/action" \
        -H "Content-Type: application/json" -d '{"type":"CALL"}' > /dev/null 2>&1 || true
      echo "[$(date +%H:%M:%S)] Bot1 called"
    elif [ "$NEXT" = "$BOT2_ID" ]; then
      sleep 1.5
      curl -s -b "$BOT2_JAR" -X POST "$BASE/api/tables/$TABLE_ID/action" \
        -H "Content-Type: application/json" -d '{"type":"CALL"}' > /dev/null 2>&1 || true
      echo "[$(date +%H:%M:%S)] Bot2 called"
    fi
  fi
done
