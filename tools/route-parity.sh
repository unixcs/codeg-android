#!/usr/bin/env bash
# Route-name parity guard: every send("…") literal in CodegClient.kt must exist
# as a route in the codeg server's API router (router.rs + cli_run/http.rs).
# An unknown name 501s at runtime ("not available in web mode") and every caller
# folds that into a generic failure — the mid-run insert feature shipped broken
# for months this way (acp_submit_session_feedback vs submit_session_feedback).
#
# Usage:
#   tools/route-parity.sh            # compare CodegClient.kt vs the committed snapshot
#   tools/route-parity.sh -g <path>  # (re)generate the snapshot from a server checkout
#
# CI equivalent: app/src/test/.../RouteParityTest.kt (reads the same snapshot).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SNAPSHOT="$REPO/tools/_server_routes_snapshot.txt"
CLIENT_SRC="$REPO/app/src/main/java/app/codeg/android/core/network/CodegClient.kt"

client_routes() {
    # NOTE [a-z0-9_]: the digit class matters — 'read_workspace_file_base64'
    # would be missed by a letters-only pattern and produce a permanent false
    # positive that trains everyone to ignore this script.
    grep -oE 'send\("[a-z0-9_]+"' "$CLIENT_SRC" | sed 's/send("//;s/"//' | sort -u
}

case "${1:-}" in
-g)
    SERVER_DIR="${2:?usage: tools/route-parity.sh -g <server-checkout-root>}"
    {
        grep -ohE '"/[a-z0-9_]+"' "$SERVER_DIR/src-tauri/src/web/router.rs"
        grep -ohE '"/[a-z0-9_]+"' "$SERVER_DIR/src-tauri/src/cli_run/http.rs"
    } | sed 's/"//g; s|^/||' | sort -u > "$SNAPSHOT"
    echo "snapshot written: $SNAPSHOT ($(wc -l < "$SNAPSHOT") routes)"
    ;;
"")
    [ -f "$SNAPSHOT" ] || { echo "snapshot missing — run: tools/route-parity.sh -g <server-checkout>" >&2; exit 2; }
    comm -23 <(client_routes) <(sort -u "$SNAPSHOT") > /tmp/route_parity_diff.$$
    if [ -s /tmp/route_parity_diff.$$ ]; then
        echo "ROUTES CALLED BY CODEGCLIENT BUT NOT REGISTERED SERVER-SIDE:"
        cat /tmp/route_parity_diff.$$
        rm -f /tmp/route_parity_diff.$$
        exit 1
    fi
    rm -f /tmp/route_parity_diff.$$
    echo "route parity OK ($(client_routes | wc -l) client routes)"
    ;;
*)
    echo "usage: tools/route-parity.sh [-g <server-checkout-root>]" >&2
    exit 2
    ;;
esac
