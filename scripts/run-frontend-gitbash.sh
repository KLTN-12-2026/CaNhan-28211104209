#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
FRONTEND_DIR="$ROOT_DIR/frontend"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
if [[ ! "$FRONTEND_PORT" =~ ^[0-9]{1,5}$ ]] || (( 10#$FRONTEND_PORT < 1 || 10#$FRONTEND_PORT > 65535 )); then
  printf 'FRONTEND_PORT must be an integer in 1..65535.\n' >&2
  exit 2
fi
if ( : > "/dev/tcp/127.0.0.1/$FRONTEND_PORT" ) 2>/dev/null; then
  printf 'Frontend port %s is already in use; choose another FRONTEND_PORT before building.\n' "$FRONTEND_PORT" >&2
  exit 2
fi
export VITE_PROXY_TARGET="${VITE_PROXY_TARGET:-http://127.0.0.1:8080}"
node --input-type=module - <<'JS'
try {
  const response = await fetch(`${process.env.VITE_PROXY_TARGET.replace(/\/$/, '')}/api/v1/health`, {
    signal: AbortSignal.timeout(5000),
  });
  if (!response.ok || (await response.json()).status !== 'UP') throw new Error();
} catch {
  console.error('Backend is not ready. Start it and wait for startup, then check VITE_PROXY_TARGET before starting frontend.');
  process.exit(2);
}
JS

export VITE_DATA_MODE=api
export VITE_API_BASE_URL=/api/v1
if [[ -n "${MSYS2_ENV_CONV_EXCL:-}" ]]; then
  case ";$MSYS2_ENV_CONV_EXCL;" in
    *";VITE_API_BASE_URL;"*) ;;
    *) export MSYS2_ENV_CONV_EXCL="$MSYS2_ENV_CONV_EXCL;VITE_API_BASE_URL" ;;
  esac
else
  export MSYS2_ENV_CONV_EXCL=VITE_API_BASE_URL
fi

cd "$FRONTEND_DIR"
printf 'Building frontend in API mode ...\n'
npm run build
printf 'Starting Vite on 127.0.0.1:%s (Ctrl+C to stop).\n' "$FRONTEND_PORT"
exec npm run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT" --strictPort
