# Implementation Decisions

This document records the behavior and trade-offs chosen for the Java 21 size-level availability exercise. It describes the implementation currently in the repository, not a production-ready deployment design.

## Runtime and local testing

- Use Java 21, Maven, and Spring Boot for the service.
- Run the application locally on port `5000` by default, WireMock on `8080`, and persist local stock/outbox state in the embedded H2 database at `data\availability.mv.db`. No separate database installation or local broker is required.
- Use `POST /_dev/messages` as a development-only entry point for one `SellerInventoryChanged` event.

## Production broker choice

For a production event backbone, choose Apache Kafka. It fits the expected high-volume inventory stream and bulk imports, retains events for replay, and lets independent consumer groups scale without competing for one queue. Partition input and output topics by `(sellerId, productId, sizeId, countryCode)` and process records sequentially within each partition so messages for one stock key stay ordered; different partitions can be processed concurrently. The existing source-version check remains necessary because producers and retries can still deliver duplicates or older versions.

| Option | Strengths for this service | Trade-offs |
|---|---|---|
| **Kafka (recommended)** | Durable event log, per-partition ordering, replay, and independent consumer groups suit inventory events and recovery. | Requires partition planning, operational monitoring, and consumer-offset management. |
| RabbitMQ | Flexible exchanges/routing and acknowledgments are a good fit for command or work queues. | Queue-oriented consumption is less natural for replaying historical inventory events or scaling multiple independent subscribers. |

Use at-least-once delivery rather than assuming a transaction spans the broker, database, and Availability API. Persist the input/version and pending output in a database-backed inbox/outbox before acknowledging the input; publish output events from the outbox with a stable event ID, and require consumers to deduplicate that ID. Availability writes must remain idempotent for a stock key and source version. Kafka is a production design recommendation only; the exercise uses its provided HTTP sink and needs no broker installation.

## Single-event processing

- Look up Product Context before writing availability. Unknown, inactive, or country-disabled products return `422`, with no availability update or output event.
- For eligible products, write availability and then publish a normalized `SizeAvailabilityUpdated` event to the HTTP sink.
- Treat the HTTP sink as a test substitute for the production broker described above.

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

## Asynchronous CSV imports

- Accept a multipart `file` with the required `sellerId`, `productId`, `sizeId`, `countryCode`, `availableQuantity`, and `sourceVersion` headers. Validate the CSV structure and headers before accepting the upload; validate row values asynchronously.
- Return `202 Accepted` with a job ID and status URL. Job states are `QUEUED`, `PROCESSING`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, and `FAILED`.
- `processedRows` counts every row that has an outcome. `succeededRows` counts completed processing, `skippedRows` counts stale or duplicate versions, and `failedRows` counts invalid rows and downstream errors. A stale row is not a failure and does not stop the rest of the upload.
- A transient downstream error after the shared processor exhausts its immediate attempts is recorded as a failed row result for that job. Its durable stock/outbox operation may still be completed by the recovery worker later; the job report describes the original row-processing attempt.
- Limit uploads to 10 MB. Run at most two import jobs at once with two queued; process rows sequentially within each job so a large upload cannot fan out unbounded downstream calls. Reject a full queue with `503`.
- Keep job status and row results in memory for this local exercise, retaining the latest 20 jobs and evicting older terminal jobs as needed. They are lost on application restart, unlike stock/outbox state, which is file-backed in H2.
- Validate CSV rows before fetching context so invalid rows do not trigger snapshot requests. Fetch one snapshot for each distinct valid country in the upload, using the existing retry policy for transient failures.
- Build a sparse `HashMap` index with a composite record key `(productId, sizeId, countryCode)`. First collect the distinct keys requested by the upload, then scan each snapshot once and retain only matching entries. `sellerId` stays out of this context key and remains part of `StockKey`, because sellers share product metadata but have independent stock versions.
- Resolve a row's context through the shared processing service after stale/duplicate version checks. Thus an old or completed duplicate remains skipped without requiring a context entry. A missing entry fails only that row; a snapshot fetch failure marks applicable rows as failed and does not stop other countries.
- The snapshot is an upload-time view: later Product Context changes do not affect that job. Each active import job retains its row data, results, and sparse indexes until completion; at most two jobs run concurrently.

### Product Context matching complexity

Let `R_c` be the number of valid CSV rows for country `c`, `R = sum(R_c)`, `N_c` the number of entries in that country's snapshot, and `M_c` the number of distinct requested row keys found there (`M_c <= min(R_c, N_c)`). Snapshot responses are unordered, so the importer scans each entry once; hashing and matching are expected constant time per key.

| Strategy | Context HTTP calls | Expected matching time | Retained lookup space | Trade-off |
|---|---:|---:|---:|---|
| Single-item lookup per row | `R` | `O(R)` local work plus `R` network round trips | `O(R)` row/job state | Avoids snapshot memory, but repeats network latency and metadata reads. |
| Scan full snapshot for every row | One per country | `O(sum(R_c * N_c))` | `O(R)` row/job state | Low index memory, but 10,000 rows against 50,000 contexts can require about 500 million comparisons. |
| Hash-index every snapshot entry | One per country | `O(R + sum(N_c))` | `O(R + sum(N_c))` | Fast lookups, but retains contexts no row will use. |
| **Chosen: sparse hash index of requested keys** | **One per country** | **Expected `O(R + sum(N_c))`** | **`O(R + sum(M_c))` retained** | **Same optimal scan-and-match time, while retaining only contexts the upload can use.** |

For the supplied one-country fixture, `R = 10,000` and `N_PT = 50,000`; the chosen design scans the 50,000-entry response once and indexes at most 10,000 requested context keys. The response list itself is temporarily materialized, so per-job peak additional snapshot memory is `O(max(N_c))` before that response becomes eligible for collection. The row list and job results already require `O(R)` space. These bounds are per job; two concurrent imports can approximately double the live working set. The `HashMap` bounds are expected-case bounds, as usual for hash-based lookup.

For unordered snapshots without a server-side filter for requested keys, an exact join must inspect every valid row and every snapshot entry, giving a lower bound of `Omega(R + sum(N_c))`. The sparse index reaches that bound in expected time while avoiding retention of irrelevant snapshot contexts. It reduces only Product Context work: each successful row still requires an Availability PUT and output-event POST.

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
