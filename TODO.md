# Implementation TODO

Ordered by implementation priority. Point 1 is complete; the remaining items describe the next work.

- [x] **1. Set up the Java 21 application and local mock configuration.**
  - **How it was done:** Added a Maven project targeting Java 21 with Spring Boot 3.4.5, a runnable application entry point, a `GET /health` endpoint, and a focused health test. The app defaults to port `5000` and reads the mock base URL from `WIREMOCK_BASE_URL` (default `http://localhost:8080`). Added `.gitignore` for Maven build output and documented local run instructions in [Run-Instructions.md](Run-Instructions.md).
  - **Why:** Establishes a small, runnable baseline before implementing business behavior. No local broker or mock-server code is needed.

- [ ] **2. Implement and test the single-event processing flow.**
  - Add `POST /_dev/messages` with validated request models, then route the request through shared processing logic: look up Product Context, update availability, and publish the normalized `SizeAvailabilityUpdated` event.
  - Decide and document how to handle unknown, inactive, or country-disabled product contexts.
  - **Why:** Proves the core business flow with one event before introducing bulk processing.

- [ ] **3. Enforce version ordering and safe retries across both input paths.**
  - Track `sourceVersion` for each `(sellerId, productId, sizeId, countryCode)` stock key; reject stale versions, handle duplicate/equal-version delivery idempotently, and coordinate updates to the same key across event and bulk processing.
  - Use bounded retries for transient `429` and `503` responses, respecting `Retry-After`; do not retry permanent validation or missing-context failures.
  - **Why:** Prevents replays or out-of-order work from overwriting newer stock and makes dependency failures recoverable.

- [ ] **4. Handle partial downstream success.**
  - Define recovery behavior when an availability write succeeds but publishing the output event fails. For a production-oriented design, explain durable version state and an outbox or reconciliation strategy; the two remote calls are not atomic.
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
