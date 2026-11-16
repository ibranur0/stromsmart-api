#!/usr/bin/env bash
# Setter opp Google Cloud for Strømsmart API. Kjøres en gang, i Cloud Shell:
#
#   bash oppsett.sh <prosjekt-id> '<tilkoblingsstreng fra Neon>'
#
# Bruk tilkoblingsstrengen med -pooler i vertsnavnet.
# Skriptet kan kjøres flere ganger. Det som finnes fra før, hoppes over.
#
# Det lager:
#   - Artifact Registry for bildene (beholder de to nyeste)
#   - hemmeligheter i Secret Manager (database og import-token)
#   - en tjenestekonto appen kjører som, som bare kan lese hemmelighetene
#   - en tjenestekonto GitHub Actions ruller ut med (Workload Identity Federation, ingen nøkkelfil)
#   - en Cloud Scheduler-jobb som henter priser hver halvtime
#   - en Cloud Scheduler-jobb som holder appen i gang
#   - et budsjettvarsel
set -euo pipefail

PROJECT=${1:?Bruk: bash oppsett.sh <prosjekt-id> <neon-tilkoblingsstreng>}
NEON_URL=${2:?Mangler tilkoblingsstrengen fra Neon}
GITHUB_REPO=${GITHUB_REPO:-ibranur0/stromsmart-api}
REGION=europe-west2
SERVICE=stromsmart-api

gcloud config set project "$PROJECT" >/dev/null
NUMBER=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')
RUN_SA="stromsmart-run@$PROJECT.iam.gserviceaccount.com"
DEPLOY_SA="github-deploy@$PROJECT.iam.gserviceaccount.com"
URL="https://$SERVICE-$NUMBER.$REGION.run.app"

exists() { "$@" >/dev/null 2>&1; }

echo "== Tjenester"
gcloud services enable run.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com \
  cloudscheduler.googleapis.com iamcredentials.googleapis.com sts.googleapis.com billingbudgets.googleapis.com

echo "== Artifact Registry"
if ! exists gcloud artifacts repositories describe "$SERVICE" --location "$REGION"; then
  gcloud artifacts repositories create "$SERVICE" --location "$REGION" --repository-format docker
fi
cat > /tmp/opprydding.json <<'JSON'
[{"name": "behold-to-nyeste", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 2}},
 {"name": "slett-resten", "action": {"type": "Delete"}, "condition": {"tagState": "any"}}]
JSON
gcloud artifacts repositories set-cleanup-policies "$SERVICE" --location "$REGION" --policy /tmp/opprydding.json --no-dry-run >/dev/null

echo "== Hemmeligheter"
# postgresql://bruker:passord@vert/database?sslmode=require  ->  JDBC-adresse, bruker og passord
rest=${NEON_URL#*://}
userinfo=${rest%%@*}
hostpart=${rest#*@}
DB_USER=${userinfo%%:*}
DB_PASSWORD=${userinfo#*:}
DB_URL="jdbc:postgresql://${hostpart%%\?*}?sslmode=require"
FLYWAY_URL=${DB_URL/-pooler./.}

secret() {
  if exists gcloud secrets describe "$1"; then
    printf '%s' "$2" | gcloud secrets versions add "$1" --data-file=- >/dev/null
    # Eldre versjoner teller mot gratisgrensen, så de slettes.
    for old in $(gcloud secrets versions list "$1" --filter='state=enabled' --sort-by='~createTime' --format='value(name)' | tail -n +2); do
      gcloud secrets versions destroy "$old" --secret "$1" --quiet >/dev/null
    done
  else
    printf '%s' "$2" | gcloud secrets create "$1" --data-file=- --replication-policy automatic >/dev/null
  fi
}
secret db-url "$DB_URL"
secret flyway-url "$FLYWAY_URL"
secret db-user "$DB_USER"
secret db-password "$DB_PASSWORD"
if ! exists gcloud secrets describe import-token; then
  secret import-token "$(openssl rand -hex 32)"
fi
IMPORT_TOKEN=$(gcloud secrets versions access latest --secret import-token)

echo "== Tjenestekontoer"
exists gcloud iam service-accounts describe "$RUN_SA" || \
  gcloud iam service-accounts create stromsmart-run --display-name "Strømsmart API (kjøring)"
exists gcloud iam service-accounts describe "$DEPLOY_SA" || \
  gcloud iam service-accounts create github-deploy --display-name "GitHub Actions (utrulling)"

for s in db-url flyway-url db-user db-password import-token; do
  gcloud secrets add-iam-policy-binding "$s" --member "serviceAccount:$RUN_SA" \
    --role roles/secretmanager.secretAccessor >/dev/null
done
for role in roles/run.admin roles/artifactregistry.writer roles/cloudscheduler.jobRunner; do
  gcloud projects add-iam-policy-binding "$PROJECT" --member "serviceAccount:$DEPLOY_SA" \
    --role "$role" --condition=None >/dev/null
done
gcloud iam service-accounts add-iam-policy-binding "$RUN_SA" --member "serviceAccount:$DEPLOY_SA" \
  --role roles/iam.serviceAccountUser >/dev/null

echo "== Workload Identity Federation for $GITHUB_REPO"
exists gcloud iam workload-identity-pools describe github --location global || \
  gcloud iam workload-identity-pools create github --location global --display-name "GitHub Actions"
exists gcloud iam workload-identity-pools providers describe github --workload-identity-pool github --location global || \
  gcloud iam workload-identity-pools providers create-oidc github --workload-identity-pool github --location global \
    --issuer-uri https://token.actions.githubusercontent.com \
    --attribute-mapping 'google.subject=assertion.sub,attribute.repository=assertion.repository' \
    --attribute-condition "assertion.repository == '$GITHUB_REPO'"
POOL="projects/$NUMBER/locations/global/workloadIdentityPools/github"
gcloud iam service-accounts add-iam-policy-binding "$DEPLOY_SA" --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/$POOL/attribute.repository/$GITHUB_REPO" >/dev/null

echo "== Cloud Scheduler"
if exists gcloud scheduler jobs describe hent-priser --location "$REGION"; then
  verb=update
else
  verb=create
fi
gcloud scheduler jobs "$verb" http hent-priser --location "$REGION" \
  --schedule '*/30 * * * *' --time-zone Europe/Oslo \
  --uri "$URL/internal/import" --http-method POST \
  --"$( [ "$verb" = create ] && echo headers || echo update-headers )" "Authorization=Bearer $IMPORT_TOKEN" \
  --attempt-deadline 300s >/dev/null

# Cloud Run stopper appen etter et kvarter uten trafikk, og da må neste besøkende vente
# på oppstarten. Et lite kall hvert tiende minutt holder den i gang. Databasen røres ikke.
if ! exists gcloud scheduler jobs describe hold-vaken --location "$REGION"; then
  gcloud scheduler jobs create http hold-vaken --location "$REGION"     --schedule '*/10 * * * *' --time-zone Europe/Oslo     --uri "$URL/actuator/health/liveness" --http-method GET --attempt-deadline 30s >/dev/null
fi

echo "== Budsjettvarsel"
BILLING=$(gcloud billing projects describe "$PROJECT" --format='value(billingAccountName)' | sed 's|billingAccounts/||')
if [ -n "$BILLING" ] && ! gcloud billing budgets list --billing-account "$BILLING" --format='value(displayName)' | grep -qx 'Strømsmart'; then
  gcloud billing budgets create --billing-account "$BILLING" --display-name 'Strømsmart' \
    --filter-projects "projects/$PROJECT" --budget-amount 10 \
    --threshold-rule percent=0.01 --threshold-rule percent=0.5 --threshold-rule percent=1.0 >/dev/null
fi

cat <<EOF

Ferdig. Legg inn disse som variabler i GitHub (Settings > Secrets and variables > Actions > Variables):

  GCP_PROJECT       $PROJECT
  GCP_WIF_PROVIDER  $POOL/providers/github
  GCP_DEPLOY_SA     $DEPLOY_SA

Eller med gh på din egen maskin:

  gh variable set GCP_PROJECT --body '$PROJECT' -R $GITHUB_REPO
  gh variable set GCP_WIF_PROVIDER --body '$POOL/providers/github' -R $GITHUB_REPO
  gh variable set GCP_DEPLOY_SA --body '$DEPLOY_SA' -R $GITHUB_REPO

Neste push til main ruller ut appen til $URL
EOF
