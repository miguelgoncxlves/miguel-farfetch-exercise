# Run Instructions

The service targets Java 21, uses Maven, and listens on port `5000` by default. It exposes `GET /health`, `POST /_dev/messages`, and asynchronous CSV import routes. It uses an embedded H2 database, so no separate database installation is needed.

## Prerequisites

- JDK 21.
- Apache Maven 3.9 or newer.
- To run the provided mock services: Docker Desktop or Docker Engine with Docker Compose v2.
- Local ports `5000` (application) and `8080` (WireMock) available.

On Windows, verify which Java installation Maven uses:

```powershell
java -version
mvn -version
```

Both should report Java 21. If they report an older Java version, set `JAVA_HOME` to your JDK 21 installation and prepend its `bin` directory to `PATH` for the current PowerShell window:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
```

## Start the application

From the repository root, run:

```powershell
mvn spring-boot:run
```

Check `http://localhost:5000/health`; a running application responds with:

```json
{"status":"UP"}
```

Override the application port with `SERVER_PORT` or the mock-service base URL with `WIREMOCK_BASE_URL`. Defaults are `5000` and `http://localhost:8080`, respectively.

## Version ordering and retries

The service stores stock versions and processing/outbox state in `data\availability.mv.db`, keyed by seller, product, size, and country. The database directory is created automatically and is excluded from Git. The recovery worker retries pending availability writes or output publications after a restart. The default H2 file database is for a single local app instance; it is not a shared multi-instance database. Override the H2 JDBC URL with `AVAILABILITY_DATABASE_URL`.

Older versions and completed duplicate versions are skipped. For downstream HTTP `429` and `503` responses, the service makes up to three total attempts, honoring `Retry-After` when present; otherwise it starts at `100ms` and doubles the delay between attempts. Override these settings with `AVAILABILITY_RETRY_MAX_ATTEMPTS` and `AVAILABILITY_RETRY_BASE_DELAY`.

The Postman collection has ordered requests for checking version behavior. To reset state for a clean run, stop the app and remove only `data\availability.mv.db`, then restart it. For the full flow and mock reset commands, see the [README](README.md).

## CSV imports

Upload a multipart CSV file using form field `file`:

```powershell
$response = curl.exe --silent --show-error --request POST "http://localhost:5000/v1/availability/imports" `
  --form "file=@wiremock\__files\bulk-smoke.csv;type=text/csv" | ConvertFrom-Json
$response
Invoke-RestMethod -Uri "http://localhost:5000$($response.statusUrl)"
```

The upload returns `202 Accepted`; poll the supplied status URL until the job reaches a terminal state. The smoke CSV intentionally includes one successful row, one stale row (prime its version first using the matching Postman request), and two row failures. The full 10,000-row fixture is `wiremock\__files\bulk-stock.csv`; for that PT-only file, the importer fetches the 50,000-entry Product Context snapshot once, then performs an availability write and output publish for each successful row. For job states, counts, row results, upload limits, and complexity trade-offs, see the [README](README.md) and [DECISIONS](DECISIONS.md).

## Start the provided mocks

Start Docker, then run this from the repository root:

```powershell
docker compose up -d
```

Check mock readiness at `http://localhost:8080/health`. The Compose configuration binds WireMock to `127.0.0.1`; keep its unauthenticated admin endpoints local.

For mock routes, fixtures, sample requests, and reset/stop commands, see the [README](README.md).
