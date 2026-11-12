# Implementation Decisions

This document records the behavior and trade-offs chosen for the Java 21 size-level availability exercise. It describes the implementation currently in the repository, not a production-ready deployment design.

## Runtime and local testing

- Use Java 21, Maven, and Spring Boot for the service.
- Run the application locally on port `5000` by default and WireMock on `8080`. The app calls WireMock; no local broker is required.
- Use `POST /_dev/messages` as a development-only entry point for one `SellerInventoryChanged` event.

## Single-event processing

- Look up Product Context before writing availability. Unknown, inactive, or country-disabled products return `422`, with no availability update or output event.
- For eligible products, write availability and then publish a normalized `SizeAvailabilityUpdated` event to the HTTP sink.
- Treat the HTTP sink as a test substitute for a broker, not as the production broker decision.

## Stock identity and event versions

- Scope stock versions to `(sellerId, productId, sizeId, countryCode)`. Seller identity matters for stock, even though Product Context is looked up by product, size, and country.
- Skip a lower version as `stale_version`. Skip an already completed equal version as `duplicate_version`; neither case calls a downstream service.
- Use 64 striped locks to serialize same-key updates. Different keys can proceed concurrently unless they map to the same stripe. The shared processing service is intended to be used by both event and future CSV paths.

## Dependency retries

- Retry only HTTP `429 Too Many Requests` and `503 Service Unavailable` responses, with three total attempts.
- Honor `Retry-After` in either seconds or HTTP-date format. If the header is absent or invalid, use exponential delays starting at `100ms` (`100ms`, then `200ms`).
- Do not retry validation errors, missing Product Context, or other non-retryable response statuses.

## Partial downstream success

- Availability update and output publication are separate remote calls and cannot be made atomic by this service.
- If the availability update succeeds but output publication fails, retain the applied version and pending output event in memory. A replay retries that same output event without repeating the availability update; a newer version must first retry the pending publication.
- This is a process-local recovery measure only. A restart loses the state, and multiple app instances do not coordinate. A durable version store plus an outbox or reconciliation strategy remains necessary for production; see TODO item 4.

## Manual verification

Import `postman/size-level-availability.postman_collection.json`, restart the app to clear its in-memory state, and send requests 1–5 in order:

1. Process source version 7: expect `{"status":"processed"}`.
2. Replay version 7 with another event ID: expect `{"status":"skipped","reason":"duplicate_version"}`.
3. Send version 6 for the same stock key: expect `{"status":"skipped","reason":"stale_version"}`.
4. Send version 8: expect `{"status":"processed"}`.
5. Send version 6 for a different seller: expect `{"status":"processed"}` because it is a separate stock-version stream.

To exercise dependency retries through the app, change the collection's `productId` to `style-90002` for Product Context `503`, or change `sellerId` to `seller-503` for availability `503`. The `style-90001` and `seller-429` fixtures return `429` with `Retry-After: 5`, so each can take about ten seconds before the final failure. The app maps exhausted downstream failures to `502`.

The event-sink fault fixture matches an output event ID of `fault-429`, while the app generates output IDs. Therefore, the collection can exercise that fixture with its direct sink request, but cannot trigger the app's output-publish retry end-to-end. Automated tests cover retrying output publication.
