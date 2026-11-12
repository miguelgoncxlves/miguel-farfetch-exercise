# Implementation Decisions

This document records the behavior and trade-offs chosen for the Java 21 size-level availability exercise. It describes the implementation currently in the repository, not a production-ready deployment design.

## Runtime and local testing

- Use Java 21, Maven, and Spring Boot for the service.
- Run the application locally on port `5000` by default, WireMock on `8080`, and persist local stock/outbox state in the embedded H2 database at `data\availability.mv.db`. No separate database installation or local broker is required.
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

- The Availability PUT and output publication are separate remote calls and cannot be made atomic by this service.
- Persist an `AVAILABILITY_PENDING` record, including the input, canonical context, and output event with a stable generated ID, before calling the Availability API. This gives recovery enough information to resume without repeating the Product Context lookup.
- After the PUT succeeds, persist `PUBLICATION_PENDING` before publishing. After the publisher accepts the event, mark the operation complete and retain only the latest source version.
- A scheduled recovery worker starts scanning after a one-second initial delay and then every five seconds. It retries the Availability PUT if the durable phase is still `AVAILABILITY_PENDING`; otherwise it retries only publication. An input replay or a newer version also tries to finish the pending operation first.
- Exhausted `429`/`503` attempts and transport failures remain eligible for scheduled recovery. Other downstream HTTP errors are recorded as `AVAILABILITY_FAILED` or `PUBLICATION_FAILED` and are not polled automatically; an input replay retries them after the underlying issue is corrected. A newer version tries to resolve the earlier operation first so it cannot silently overtake it.
- A crash after remote PUT success but before saving `PUBLICATION_PENDING` can cause the same PUT to be sent again. This relies on the API's PUT operation being idempotent for the same stock key and source version. A crash after event acceptance but before marking completion can cause a repeat publish with the same event ID; downstream consumers must deduplicate by that ID.
- The H2 file is durable across local process restarts but supports only one local application instance in this setup. A production multi-instance service should use a shared database, transactionally update state/outbox records, coordinate stock-key processing, and publish through an outbox relay.

## Manual verification

Import `postman/size-level-availability.postman_collection.json` and send requests 1–5 in order. To reset the version sequence, stop the app and remove only `data\availability.mv.db` before restarting:

1. Process source version 7: expect `{"status":"processed"}`.
2. Replay version 7 with another event ID: expect `{"status":"skipped","reason":"duplicate_version"}`.
3. Send version 6 for the same stock key: expect `{"status":"skipped","reason":"stale_version"}`.
4. Send version 8: expect `{"status":"processed"}`.
5. Send version 6 for a different seller: expect `{"status":"processed"}` because it is a separate stock-version stream.

To exercise dependency retries through the app, change the collection's `productId` to `style-90002` for Product Context `503`, or change `sellerId` to `seller-503` for availability `503`. The `style-90001` and `seller-429` fixtures return `429` with `Retry-After: 5`, so each can take about ten seconds before the final failure. The app maps exhausted downstream failures to `502`.

The event-sink fault fixture matches an output event ID of `fault-429`, while the app generates output IDs. Therefore, the collection can exercise that fixture with its direct sink request, but cannot trigger the app's output-publish retry end-to-end. Automated tests cover retrying output publication.

To test durable publication recovery manually, add a temporary high-priority WireMock `503` mapping for `POST /mock-broker/v1/events`:

```powershell
$mappingJson = '{"priority":1,"request":{"method":"POST","urlPath":"/mock-broker/v1/events"},"response":{"status":503,"headers":{"Content-Type":"application/json"}}}'
$mapping = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/__admin/mappings' -ContentType 'application/json' -Body $mappingJson
```

Send request 1 using a new seller ID. The app should return `502` after the publisher retries, while one availability PUT and a pending outbox record remain. Stop the app, remove the temporary mapping by its returned ID, then restart:

```powershell
Invoke-RestMethod -Method Delete -Uri "http://localhost:8080/__admin/mappings/$($mapping.id)"
```

Within the configured poll interval, the recovery worker should publish the saved event without repeating the availability PUT. The automated store and service tests cover persistence across store recreation and recovery without an incoming replay. These unauthenticated admin calls are local-test-only; keep WireMock bound to localhost.
