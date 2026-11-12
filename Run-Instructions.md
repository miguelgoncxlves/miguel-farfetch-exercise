# Run Instructions

The service targets Java 21, uses Maven, and listens on port `5000` by default. It exposes `GET /health` and `POST /_dev/messages`; bulk-import routes are not implemented yet.

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

The service keeps the latest applied version and any pending output publication in memory, keyed by seller, product, size, and country. Restarting the app clears this state, and separate app instances do not share it. Older versions and completed duplicate versions are skipped. For downstream HTTP `429` and `503` responses, the service makes up to three total attempts, honoring `Retry-After` when present; otherwise it starts at `100ms` and doubles the delay between attempts. Override these settings with `AVAILABILITY_RETRY_MAX_ATTEMPTS` and `AVAILABILITY_RETRY_BASE_DELAY`.

The Postman collection has ordered requests for checking version behavior. Restart the app before a fresh run so the in-memory version state starts empty. For the full flow and mock reset commands, see the [README](README.md).

## Start the provided mocks

Start Docker, then run this from the repository root:

```powershell
docker compose up -d
```

Check mock readiness at `http://localhost:8080/health`. The Compose configuration binds WireMock to `127.0.0.1`; keep its unauthenticated admin endpoints local.

For mock routes, fixtures, sample requests, and reset/stop commands, see the [README](README.md).
