# Implementation TODO

Ordered by implementation priority. Points 1–3 are complete; the remaining items describe the next work.

- [x] **1. Set up the Java 21 application and local mock configuration.**
  - **How it was done:** Added a Maven project targeting Java 21 with Spring Boot 3.4.5, a runnable application entry point, a `GET /health` endpoint, and a focused health test. The app defaults to port `5000` and reads the mock base URL from `WIREMOCK_BASE_URL` (default `http://localhost:8080`). Added `.gitignore` for Maven build output and documented local run instructions in [Run-Instructions.md](Run-Instructions.md).
  - **Why:** Establishes a small, runnable baseline before implementing business behavior. No local broker or mock-server code is needed.

- [x] **2. Implement and test the single-event processing flow.**
  - **How it was done:** Added a validated `POST /_dev/messages` endpoint and shared processing service. It fetches Product Context, stops with `422` and makes no availability write or event publish for unknown, inactive, or country-disabled contexts, then writes availability and publishes a normalized `SizeAvailabilityUpdated` event for eligible contexts. Added focused controller and HTTP-flow tests.
  - **Why:** Proves the core business flow with one event before introducing bulk processing.

- [x] **3. Enforce version ordering and safe retries across both input paths.**
  - **How it was done:** The shared processing service serializes updates by `(sellerId, productId, sizeId, countryCode)`, skips older versions and completed duplicates, and retries `429`/`503` downstream responses up to three total attempts. It honors `Retry-After` or uses exponential delays starting at `100ms`. If availability succeeds but output publication fails, the applied version and pending event are retained in memory so a replay can retry publication without repeating the availability write. When CSV processing is added, it must call this same service.
  - **Why:** Prevents replays or out-of-order work from overwriting newer stock and makes transient dependency failures recoverable.

- [ ] **4. Handle partial downstream success.**
  - The current in-memory pending-event state supports retry during the running process, but a restart can still lose it. Add durable version state and an outbox or reconciliation strategy; the two remote calls are not atomic.
  - **Why:** Avoids silently losing events or incorrectly advancing version state after only part of the flow succeeds.

- [ ] **5. Add asynchronous CSV imports and job status.**
  - Implement `POST /v1/availability/imports` with synchronous request/header validation, `202 Accepted`, and a job ID. Process accepted rows asynchronously through the same business flow as events.
  - Implement `GET /v1/availability/imports/{jobId}` with job state, total/processed/succeeded/failed counts, and row-level results. Document whether stale rows are skipped or failed, and continue after individual row failures.
  - Use bounded concurrency so large imports do not overwhelm the service or downstream dependencies.
  - **Why:** Meets the bulk-import requirement while reusing the tested business flow and reporting partial failures.

- [ ] **6. Match bulk rows against Product Context snapshots.**
  - Fetch one snapshot per distinct country in an upload and index it by `(productId, sizeId, countryCode)`; do not make one context lookup per row. Keep seller identity in the stock-version key, not the context key.
  - Document matching time and space complexity and behavior as row and snapshot sizes grow.
  - **Why:** Makes the provided 10,000-row import and 50,000-context snapshot practical without unnecessary per-row HTTP calls.

- [ ] **7. Complete focused tests and solution documentation.**
  - Cover the event flow, stale and duplicate versions across both inputs, context edge cases, malformed uploads, row-level failures, and the supplied transient-failure cases.
  - Document assumptions, run instructions, retry/idempotency and partial-failure behavior, concurrency limits, context-matching complexity, and a production broker choice.
  - **Why:** Demonstrates correctness for the assignment's important cases and makes design tradeoffs explainable.
