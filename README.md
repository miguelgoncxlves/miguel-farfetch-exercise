# Start Here: Size-Level Availability Exercise

This repository contains the assignment PDF, local mock dependencies, and a Java 21 service with the single-event flow, in-memory version ordering, and bounded retries. Bulk imports and durable recovery remain to be implemented.

| Document | Use it for |
|---|---|
| [Assignment PDF](Take-Home%20Exercise_%20Size-Level%20Availability%20Processor.pdf) | Open in a PDF reader or browser to read the assignment, ownership diagram, contracts, business rules, and deliverables. |
| [Run-Instructions](Run-Instructions.md) | Java 21 prerequisites and how to start the service locally. |
| [TODO](TODO.md) | Ordered implementation plan, with the completed scaffold and remaining work. |
| [Implementation Decisions](DECISIONS.md) | Behavior choices, retry/versioning rules, and current limitations. |
| This README | How to start the mocks, send a sample event to your service, and inspect its calls. |

The product and inventory scenario, APIs, contracts, and figures are hypothetical; they do not describe internal Farfetch systems. Extract this folder and run all commands below from the folder containing `compose.yaml`.

## Service status and provided components

| Service capability | Status / provided component |
|---|---|
| `POST /_dev/messages` for test input events | Implemented; sample event JSON file provided |
| CSV upload and job-status endpoints | To implement; 10,000-row CSV provided |
| Shared processing and asynchronous bulk work | Single-event flow implemented; Product Context and snapshot APIs provided |
| Calls that update availability and publish output events | Implemented for single events; Availability API and output-event HTTP mocks provided |
| Stock version ordering and transient dependency retries | Implemented in memory; retries `429`/`503` up to three total attempts |

The assignment PDF includes the blue **YOUR SOLUTION HERE** diagram, already rendered and available offline. This plain-text version also works in Markdown viewers:

```text
PROVIDED INPUTS                  YOUR SOLUTION HERE
Sample event --curl/Postman----> POST /_dev/messages
Bulk CSV ---------------------> POST /v1/availability/imports
                                GET  /v1/availability/imports/{jobId}
                                  |
                                Shared processing + async bulk worker
                                  |
                                  +--> PROVIDED: Product Context API
                                  +--> PROVIDED: Availability API Gateway
                                  +--> PROVIDED: output-event HTTP sink
```

No real broker is needed. You send the sample input to your app over HTTP; your app sends the transformed output to the provided HTTP sink. The production broker is a design choice to explain, not infrastructure you must run.

> **Security warning:** WireMock's admin endpoints are unauthenticated. The Compose port must bind only to `127.0.0.1`; the admin endpoints must not be exposed to any network.

## 1. Start the mocks

The package already includes these deterministic files under `wiremock\__files\`: `product-contexts-PT.json`, `bulk-stock.csv`, and `sample-event.json`. Do not delete them; no fixture-generation step is needed.

Docker Desktop or Docker Engine with Docker Compose v2 is required to run the mocks. Keep local port `8080` available. Run commands from the repository root; no scripts, Python, cloud account, or credentials are required.

```powershell
docker compose up -d
```

Compose runs the public WireMock image with the local mappings and included fixtures mounted read-only. Check readiness at `http://localhost:8080/health`.

**WireMock listens on port `8080`. Starting it does not create or start your application.** Run the Java service separately on a different port; the example below uses `5000`.

## 2. Send the sample input to your service

Start the application as described in [Run-Instructions](Run-Instructions.md). The `POST /_dev/messages` route is a test-only ingress and invokes the implemented single-event flow.

`wiremock\__files\sample-event.json` is an inbound `SellerInventoryChanged` message. The service's `POST /_dev/messages` route accepts it for local testing. This route is not provided by WireMock and does not require a broker. It invokes the single-event processing flow.

For example, if your service listens on port `5000`, run:

```powershell
curl.exe --request POST "http://localhost:5000/_dev/messages" `
  --header "Content-Type: application/json" `
  --data-binary "@wiremock\__files\sample-event.json"
```

Replace `5000` with your service's port, or use Postman or another HTTP client. The `@` tells curl to read the included JSON file as the request body. This example uses PowerShell command-line syntax, not a supplied script.

The service looks up Product Context, writes availability, and POSTs its transformed `SizeAvailabilityUpdated` event to WireMock at `http://localhost:8080/mock-broker/v1/events`. Unknown products, inactive products, and products not enabled in the requested country return `422` without an availability write or output event. The WireMock route is an output sink only; it is not the inbound message endpoint or a real broker.

For each `(sellerId, productId, sizeId, countryCode)` stock key, an older `sourceVersion` is skipped as `stale_version`, and a repeated completed version is skipped as `duplicate_version`. New versions are processed in order for that key. The service retries downstream `429` and `503` responses up to three total attempts, honoring `Retry-After` or using short exponential delays. Version and pending-publication state is in memory only; it resets on app restart and is not shared across multiple app instances.

The [Postman collection](postman/size-level-availability.postman_collection.json) includes a sequence for manually exercising initial, duplicate, stale, newer, and independent-seller versions.

## 3. Inspect the calls your service made

Open `http://localhost:8080/__admin/requests` in Chrome to inspect the requests WireMock received, including request bodies. This is WireMock's request journal, not an application dashboard.

For a fresh sample event your service applies, expect a Product Context lookup, an availability write, and an output-event POST. The original `POST /_dev/messages` request goes to your app, so it will not appear in WireMock's journal.

## 4. Reset or stop the mocks

To clear only the request journal while leaving the mappings running, use PowerShell:

```powershell
curl.exe -X DELETE http://localhost:8080/__admin/requests
```

To reset the mock container to its file-backed startup state and clear its journal, restart the WireMock service:

```powershell
docker compose restart wiremock
```

Stop the mocks and remove the container/network when finished:

```powershell
docker compose down
```

`docker compose down` leaves the included fixture files and source configuration in place.

## Reference: mock routes and fixtures

The mock base URL is `http://localhost:8080`. The configured country is `PT`. Your service calls these provided routes; you do not implement them.

| Method | Route | Purpose |
|---|---|---|
| `GET` | `/health` | WireMock readiness check |
| `GET` | `/v1/products/{productId}/sizes/{sizeId}?countryCode=PT` | Look up one Product Context |
| `GET` | `/v1/product-contexts/snapshot?countryCode=PT` | Return the generated Product Context snapshot |
| `PUT` | `/v1/availability/{sellerId}/{productId}/{sizeId}?countryCode=PT` | Submit an Availability API update |
| `POST` | `/mock-broker/v1/events` | Publish an output event; a normal request returns `202` |
| `GET` | `/fixtures/bulk-stock.csv` | Download the deterministic bulk CSV |

The single-item route and snapshot return the same Product Context model. Product IDs range from `style-1` through `style-10000`; sizes are `size-34`, `size-36`, `size-38`, `size-40`, and `size-42`. The snapshot contains 50,000 Product Context objects and the CSV has 10,000 data rows. Fixture order is deterministic but not sorted. The sample context `(style-123, size-38, PT)` is present; `style-9999` is inactive, and `(style-9998, size-38, PT)` returns with `countryEnabled: false`. Other unknown product/size/country combinations return `404`.

## Reference: deterministic fault inputs

Use these request values to exercise error handling. They are fixed WireMock mappings, not queued faults:

| Mock | Input | Response |
|---|---|---|
| Product Context | `GET /v1/products/style-90001/sizes/size-38?countryCode=PT` | `429` with `Retry-After: 5` |
| Product Context | `GET /v1/products/style-90002/sizes/size-38?countryCode=PT` | `503` |
| Availability API | `PUT /v1/availability/seller-429/style-123/size-38?countryCode=PT` | `429` with `Retry-After: 5` |
| Availability API | `PUT /v1/availability/seller-503/style-123/size-38?countryCode=PT` | `503` |
| Event sink | `POST /mock-broker/v1/events` with an event whose `eventId` is `fault-429` | `429` with `Retry-After: 5` |
| Event sink | `POST /mock-broker/v1/events` with an event whose `eventId` is `fault-503` | `503` |

Availability requests use a JSON body such as `{"availableQuantity":3,"sourceVersion":7}`. The event sink selects its fault response from the submitted event's `eventId`.
