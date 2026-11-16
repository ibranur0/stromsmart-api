#!/usr/bin/env bash
# Starter det ferdige bildet mot en tom PostgreSQL og sjekker at det virker.
# Noen feil finnes bare i native image og vises ikke i de vanlige testene.
#
#   bash sky/test-bilde.sh <bilde>
set -euo pipefail

IMAGE=${1:?Bruk: bash sky/test-bilde.sh <bilde>}
NET=stromsmart-test-$$
URL=http://localhost:18080

cleanup() {
  docker rm -f "$NET-app" "$NET-db" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
fail() {
  echo "FEIL: $1"
  docker logs "$NET-app" 2>&1 | tail -40 || true
  exit 1
}
trap cleanup EXIT

docker network create "$NET" >/dev/null
docker run -d --name "$NET-db" --network "$NET" \
  -e POSTGRES_DB=stromsmart -e POSTGRES_USER=stromsmart -e POSTGRES_PASSWORD=stromsmart postgres:18-alpine >/dev/null
for _ in $(seq 1 60); do
  # Postgres starter to ganger i et nytt bilde, så vi venter til en spørring går gjennom.
  docker exec "$NET-db" psql -U stromsmart -h 127.0.0.1 -c 'select 1' >/dev/null 2>&1 && break
  sleep 0.5
done

docker run -d --name "$NET-app" --network "$NET" -p 18080:8080 \
  -e SPRING_PROFILES_ACTIVE=cloud \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$NET-db:5432/stromsmart" \
  -e SPRING_DATASOURCE_USERNAME=stromsmart -e SPRING_DATASOURCE_PASSWORD=stromsmart \
  -e STROMSMART_IMPORT_TOKEN=test -e STROMSMART_PRICES_HISTORY_DAYS=0 -e STROMSMART_PRICES_PAUSE=0s \
  -e STROMSMART_CORS_ALLOWED_ORIGINS=https://eksempel.no "$IMAGE" >/dev/null

for _ in $(seq 1 40); do
  curl -fsS -m 2 "$URL/actuator/health" >/dev/null 2>&1 && break
  sleep 0.5
done
# Svaret hentes først og sjekkes etterpå. Med "curl | grep -q" avslutter grep tidlig, og da feiler curl.
has() { grep -q -- "$2" <<<"$1"; }
get() { curl -sS -m "${TIMEOUT:-5}" "$@"; }

has "$(get "$URL/actuator/health")" '"UP"' || fail "appen startet ikke"
log=$(docker logs "$NET-app" 2>&1)
has "$log" 'Successfully applied 1 migration' || fail "migreringen kjørte ikke på en tom database"
grep -o 'Started StromsmartApiApplication in [0-9.]* seconds' <<<"$log"

[ "$(get -o /dev/null -w '%{http_code}' -X POST "$URL/internal/import")" = 401 ] || fail "innhenting uten nøkkel ble ikke avvist"
# Dagens priser hentes fra hvakosterstrommen.no. Er den nede, hopper vi over pristestene.
saved=$(TIMEOUT=120 get -X POST -H 'Authorization: Bearer test' "$URL/internal/import")
echo "innhenting: $saved"
has "$saved" '"savedDays":' || fail "innhentingen feilet"
has "$(get "$URL/api/v1/areas")" '"NO5"' || fail "/api/v1/areas"
has "$(get "$URL/api/v1/prices/NO9")" 'Ukjent prisområde' || fail "feilsvar på norsk"
has "$(get -o /dev/null -D - -H 'Origin: https://eksempel.no' "$URL/api/v1/areas")" 'Access-Control-Allow-Origin: https://eksempel.no' || fail "CORS"
has "$(get "$URL/v3/api-docs")" '/api/v1/plans' || fail "dokumentasjonen"

if has "$saved" '"savedDays":5'; then
  has "$(get "$URL/api/v1/prices/NO1")" '"intervals":\[{' || fail "prisene for i dag"
  has "$(get -X POST -H 'Content-Type: application/json' -d '{"area":"NO1","mode":"WINDOW","kwh":1,"hours":1}' "$URL/api/v1/plans")" '"blocks":\[{' || fail "planleggingen"
  echo "priser og plan: i orden"
else
  echo "pris-API-et ga ikke alle fem områdene, så priser og plan ble ikke testet"
fi
echo "Bildet virker."
