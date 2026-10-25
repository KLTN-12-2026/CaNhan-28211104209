#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
BACKEND_DIR="$ROOT_DIR/backend"
ENV_FILE="${BACKEND_ENV_FILE:-$BACKEND_DIR/.env.local}"

usage() {
  printf 'Usage: bash scripts/run-backend-gitbash.sh [--env-file PATH]\n' >&2
}

if [[ ${1:-} == "--env-file" ]]; then
  [[ $# -eq 2 ]] || { usage; exit 2; }
  ENV_FILE="$2"
elif [[ $# -ne 0 ]]; then
  usage
  exit 2
fi

if [[ -n "$ENV_FILE" && "$ENV_FILE" != /* && ! "$ENV_FILE" =~ ^[A-Za-z]:[/\\] ]]; then
  ENV_FILE="$ROOT_DIR/$ENV_FILE"
fi

if [[ -n "$ENV_FILE" && -f "$ENV_FILE" ]]; then
  first_line=true
  while IFS= read -r line || [[ -n "$line" ]]; do
    line=${line%$'\r'}
    if [[ "$first_line" == true ]]; then
      line=${line#$'\xef\xbb\xbf'}
      first_line=false
    fi
    [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=(.*)$ ]]; then
      key=${BASH_REMATCH[2]}
      value=${BASH_REMATCH[3]}
      case "$key" in
        DB_URL|DB_USER|DB_PASSWORD|JWT_SECRET|PORT|FRONTEND_ORIGIN|TOKEN_MINUTES|SEED_ENABLED|SEED_PASSWORD)
          if [[ ${#value} -ge 2 && ( ( ${value:0:1} == '"' && ${value: -1} == '"' ) || ( ${value:0:1} == "'" && ${value: -1} == "'" ) ) ]]; then
            value=${value:1:${#value}-2}
          fi
          # The current shell wins; the file supplies only unset values.
          if [[ ! -v $key ]]; then
            export "$key=$value"
          fi
          ;;
      esac
    fi
  done < "$ENV_FILE"
elif [[ -n "$ENV_FILE" && "$ENV_FILE" != "$BACKEND_DIR/.env.local" ]]; then
  printf 'Selected backend env file does not exist.\n' >&2
  exit 2
fi

for key in DB_URL DB_USER DB_PASSWORD JWT_SECRET; do
  if [[ -z "${!key:-}" ]]; then
    printf 'Missing required backend setting: %s (configure backend/.env.local or the current shell).\n' "$key" >&2
    exit 2
  fi
done

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
  [[ -x "$JAVA_BIN" ]] || JAVA_BIN="$JAVA_HOME/bin/java.exe"
else
  JAVA_BIN="$(command -v java || true)"
  [[ -n "$JAVA_BIN" ]] || { printf 'Java 21 was not found on PATH.\n' >&2; exit 2; }
  JAVA_HOME="$(cd -- "$(dirname -- "$JAVA_BIN")/.." && pwd -P)"
fi

java_version=$("$JAVA_BIN" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
if [[ "$java_version" != 21 ]]; then
  printf 'Java 21 is required; detected major version: %s\n' "${java_version:-unknown}" >&2
  exit 2
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

BACKEND_PORT="${PORT:-8080}"
if [[ ! "$BACKEND_PORT" =~ ^[0-9]{1,5}$ ]] || (( 10#$BACKEND_PORT < 1 || 10#$BACKEND_PORT > 65535 )); then
  printf 'PORT must be an integer in 1..65535.\n' >&2
  exit 2
fi
if ( : > "/dev/tcp/127.0.0.1/$BACKEND_PORT" ) 2>/dev/null; then
  printf 'Backend port %s is already in use; stop the intended instance or choose another PORT before building.\n' "$BACKEND_PORT" >&2
  exit 2
fi

cd "$BACKEND_DIR"
printf 'Building backend with ./mvnw package ...\n'
./mvnw package
JAR="$BACKEND_DIR/target/kdrive-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then
  printf 'Expected packaged application JAR was not created: %s\n' "$JAR" >&2
  exit 1
fi
printf 'Starting backend from %s (Ctrl+C to stop).\n' "$JAR"
exec "$JAVA_BIN" -jar "$JAR"
