# Strømsmart API

En tjeneste som henter spotprisene på strøm for de fem prisområdene i Norge hver dag, lagrer dem i PostgreSQL, og finner det billigste tidspunktet å bruke strøm. Den er backenden til [Strømsmart](https://github.com/ibranur0/portfolio/tree/main/projects/stromsmart) i porteføljen min.

## Endepunkter

| Metode | Adresse | Hva |
| --- | --- | --- |
| `GET` | `/api/v1/areas` | De fem prisområdene |
| `GET` | `/api/v1/prices/{område}` | Prisene i dag, og i morgen når de er publisert (rundt kl. 13) |
| `GET` | `/api/v1/prices/{område}/{dato}` | Prisene for en dag |
| `POST` | `/api/v1/plans` | Billigste tidspunkt for et forbruk, innenfor en eventuell frist |

Prisene er i kroner per kWh, med mva hvis ikke `?vat=false` er satt (Nord-Norge har ikke mva på strøm). Alle tider er i norsk tid. Dokumentasjonen, der API-et også kan prøves, ligger på `/swagger-ui.html`.

```http
POST /api/v1/plans
Content-Type: application/json

{ "area": "NO1", "mode": "FLEXIBLE", "kwh": 30, "kw": 7.4, "deadline": "2026-11-16T07:00:00+01:00" }
```

`FLEXIBLE` er forbruk som kan deles opp (elbil), og finner de billigste timene. `WINDOW` er et apparat som må gå i ett strekk (oppvaskmaskin, med `hours` i stedet for `kw`), og finner det billigste sammenhengende tidsrommet. Svaret viser også hva det ville kostet å starte med en gang.

Feil gis som [Problem Details](https://www.rfc-editor.org/rfc/rfc9457) med en norsk forklaring som kan vises direkte til brukeren.

## Slik virker den

- **Innhenting.** Hver halvtime ser appen etter dager som mangler, for alle prisområdene, og henter dem fra [hvakosterstrommen.no](https://www.hvakosterstrommen.no). Morgendagens priser hentes etter kl. 13. Feiler ett kall, prøves det igjen neste runde. Lokalt gjør en tidsplan i appen dette. I skyen sover appen når ingen bruker den, så der kaller Cloud Scheduler `POST /internal/import` med en hemmelig nøkkel.
- **Lagring.** En tabell i PostgreSQL, med prisområde og starttidspunkt som primærnøkkel, laget med Flyway. Skrivingen er vanlig SQL med `JdbcClient` og `ON CONFLICT`, så samme dag kan hentes flere ganger uten duplikater.
- **Tid.** Et døgn er et døgn i Oslo. Dagene med overgang til og fra sommertid har 23 og 25 timer, og det er testet.
- **Planlegging.** Ren Java uten avhengigheter, overført fra Python-versjonen i porteføljen. Testene bruker de samme tilfellene og tallene, så de to regner likt.

## Teknologi

Java 25, Spring Boot 4, PostgreSQL 18, Flyway, springdoc-openapi, JUnit 5, Testcontainers, GraalVM native image og Docker.

## Drift

Appen kjører på Google Cloud Run i London, i samme region som databasen hos [Neon](https://neon.com). Databasen sover når ingen bruker den.

- **Native image.** GraalVM kompilerer appen til en kjørbar fil på forhånd. Den starter på rundt to sekunder i skyen, og det meste av den tiden går med til å vekke databasen. Med en vanlig JVM tok oppstarten over ti sekunder da jeg målte.
- **Holdes i gang.** Cloud Run stopper appen etter et kvarter uten trafikk. Et lite kall hvert tiende minutt hindrer det, så besøkende slipper å vente på oppstarten.
- **Utrulling.** Ved push til `main` kjører GitHub Actions testene og bygger bildet. `sky/test-bilde.sh` starter det mot en tom database og sjekker at det svarer riktig, og først da rulles det ut. Innloggingen mot Google Cloud skjer med Workload Identity Federation, så ingen nøkkelfil ligger lagret noe sted.
- **Hemmeligheter.** Databasepassordet og nøkkelen til innhentingen ligger i Secret Manager, og appen kjører som en egen tjenestekonto som bare kan lese dem.

Oppsettet i Google Cloud ligger i `sky/oppsett.sh`.

## Kjøre lokalt

Krever Java 25 og Docker.

```bash
./mvnw spring-boot:run
```

Spring Boot starter PostgreSQL fra `compose.yaml` selv. API-et svarer på http://localhost:8080.

## Tester

```bash
./mvnw verify
```

Testene dekker planleggingen, klienten mot pris-API-et, innhentingen, API-et med feilsvar og CORS, og lagringen og hele flyten mot en ekte PostgreSQL i Docker (Testcontainers). De kjøres også på GitHub ved hver push.
