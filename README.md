# Invoice Processing System

A distributed invoice-processing pipeline built as a set of independent Java services that communicate over sockets, RMI, a message broker, and UDP multicast — each service owning exactly one stage of an invoice's lifecycle, from raw XML on disk to an approved, currency-converted, aggregated record exposed over REST. Built for the *Mrežno i distribuirano programiranje* course project (ETF Banja Luka, May 2026).

Every stage logs its actions so the pipeline can be followed in real time, and every hand-off between stages is driven by a network protocol rather than a shared process: TCP framing with a SHA-256 integrity footer into the parser, RMI for validation, RabbitMQ topics for everything downstream, and UDP multicast for live notifications — with REST reading only from Redis, never touching the pipeline directly.

## Architecture

```
/invoices/inbox
      │  new *.xml detected
      ▼
┌───────────────┐   TCP: [len|XML bytes|SHA-256]   ┌───────────────┐
│ Watcher        │ ───────────────────────────────▶ │ Parser         │
│ (folder watch, │ ◀─────────────────────────────── │ (socket server)│
│  SHA-256, move  │        ACK {filename}            │ integrity check │
│  to processed/) │                                  │ dup-check (Redis)│
└───────────────┘                                  └───────┬───────┘
                                                              │ RMI validate()
                                                              ▼
                                                     ┌───────────────┐
                                                     │ Validator      │
                                                     │ (RMI server)   │
                                                     │ JIB/date/items/│
                                                     │ currency/email │
                                                     └───────┬───────┘
                                      invalid → invoices.rejected (MQ)
                                      valid   │
                                              ▼
                                   ┌─────────────────────────┐
                                   │  RabbitMQ (fanout topics) │
                                   └─────────────────────────┘
                                              │ invoices.validated
                                              ▼
                                   ┌───────────────────────┐        no rate + no cache
                                   │ Enrichment              │ ─────────────────────────▶ invoices.retry
                                   │ Redis rate cache (1h)   │ ◀───────────── retry < 3, ScheduledExecutorService (30s)
                                   │ REST FX lookup (BAM)    │ ─────────────────────────▶ invoices.failed (retry >= 3)
                                   └──────────┬────────────┘
                                              │ invoices.enriched
                                              ▼
                                   ┌───────────────────────┐
                                   │ Approval                │  totalBAM <= 100 → invoices.approved
                                   │ auto-approve threshold  │  totalBAM >  100 → invoices.pending_approval
                                   └──────────┬────────────┘            │
                                              │                          ▼ poll
                                              │                 ┌───────────────┐
                                              │ approve/reject  │ Approval GUI    │
                                              │◀────────────────│ (JavaFX client) │
                                              │                 └───────────────┘
                                              ▼
                                   invoices.approved / invoices.rejected
                                              │
                                              ▼
                                   ┌───────────────────────┐
                                   │ Aggregator               │  invoice:{id}  → full JSON (Redis)
                                   │ consumes every topic     │  client:{jib}  → totalBAM, count, lastInvoiceId
                                   └──────────┬────────────┘
                                              │ UDP multicast 239.0.0.1:4446
                                              ▼
                                   ┌───────────────────────┐
                                   │ Monitor GUI (N clients)  │  live table, no REST polling
                                   └───────────────────────┘

                                   ┌───────────────────────┐
                                   │ REST API (Jersey)        │  reads Redis only
                                   │ /api/invoices, /stats…   │
                                   └───────────────────────┘
```

## Pipeline stages

| # | Stage | What happens |
|---|-------|---------------|
| 1 | **Watcher** | Watches `/invoices/inbox` for new XML files. Reads each file as bytes, computes its SHA-256, and streams it to the Parser over TCP as `header(4B big-endian length) + payload(N bytes XML) + footer(32B SHA-256)`. Buffers/threads the detection loop so bursts of hundreds of files neither get dropped nor flood downstream services. Moves the file to `/invoices/processed` only after the Parser's ACK. |
| 2 | **Parser** | TCP socket server. Replies with an ACK containing the filename, recomputes SHA-256 over the received bytes and compares it to the footer (mismatch → reject + log), parses the XML, and checks `<id>` against Redis for duplicates. Non-duplicates are validated via RMI; the result decides whether the invoice is published to `invoices.validated` or `invoices.rejected` (with its error list). |
| 3 | **Validator** | RMI service (`ValidationResult validate(Invoice)`). Checks JIB (13 digits), date (not future, not older than 365 days), at least one item with positive quantity/unitPrice, currency in `BAM/EUR/USD/CHF/GBP`, total amount in `(0, 1_000_000)`, and email format. Returns `valid`, `errors`, `validatorTimestamp`. |
| 4 | **Enrichment** | Consumes `invoices.validated`. Looks up the BAM exchange rate (Redis cache `rate:{currency}:{date}`, 1h TTL, falling back to the REST FX API), computes `totalBAM`, and tags the invoice with `rateSource` (`CACHE`/`API`) before publishing to `invoices.enriched`. If the API is unreachable and there's no cache, the invoice goes to `invoices.retry` with a `retryCount`. |
| 4b | **Retry** | A separate listener inside Enrichment consumes `invoices.retry`, waits (`ScheduledExecutorService`, configurable delay) and retries the rate lookup: success → `invoices.enriched`; failure with `retryCount >= 3` → `invoices.failed` (`RATE_UNAVAILABLE`); otherwise back to `invoices.retry` with the counter incremented. |
| 5 | **Approval** | Consumes `invoices.enriched`. `totalBAM <= 100` is auto-approved straight to `invoices.approved`; anything larger goes to `invoices.pending_approval` and blocks until a human decides. |
| — | **Approval GUI** | JavaFX client polling for pending requests; shows the full invoice and lets the operator approve or reject (rejection requires a reason), routing the result back to `invoices.approved` / `invoices.rejected`. |
| 6 | **Aggregator** | Consumes every topic and persists state to Redis: the full invoice JSON under `invoice:{id}`, and for approvals a running client summary under `client:{jib}` (`totalBAM`, `count`, `lastInvoiceId`, `lastUpdated`). Every approval also fires a UDP multicast notification to `239.0.0.1:4446`. |
| — | **Monitor GUI** | Any number of independent instances join the multicast group, parse the JSON notifications, and render a live table — no REST polling involved. |
| — | **REST API** | Jersey/JDK-HttpServer service reading exclusively from Redis: `GET /api/invoices`, `/api/invoices/{id}`, `/api/invoices?status=`, `/api/clients/{jib}/summary`, `/api/stats`. |

## Tech stack

| Concern | Technology |
|---|---|
| Inter-service messaging | RabbitMQ (durable fanout exchanges as "topics") |
| Watcher → Parser transport | Raw TCP sockets, length-prefixed framing |
| Validation | Java RMI |
| State / cache / dedup | Redis (Jedis) |
| Retry scheduling | `ScheduledExecutorService` |
| Live notifications | UDP multicast |
| REST layer | Jersey (JAX-RS) on the JDK built-in HTTP server |
| Desktop GUIs | JavaFX (Approval GUI, Monitor GUI) |
| JSON | Jackson |
| Integrity | SHA-256 |

## Requirements

- **Java 25**
- **Maven 3.9+** (multi-module reactor build, `pom.xml` at the project root)
- **RabbitMQ** running locally (`amqp://guest:guest@localhost:5672`)
- **Redis** running locally (`localhost:6379`)
- **JavaFX 21.0.6** (resolved via Maven for the `linux` platform; adjust `javafx.platform` in the root `pom.xml` for other OSes)

## Running

```bash
# Build every module
mvn clean install

# Start the whole pipeline (validator, aggregator, REST, enrichment, approval,
# both GUIs, parser, then watcher — in that order, so nothing is published
# before a consumer exists)
./start_services.sh

# Generate sample invoices into /invoices/inbox (valid + intentionally broken
# ones, plus one duplicate <id>, to exercise every validation/dedup path)
mvn -pl tools exec:java -Dexec.mainClass=InvoiceGenerator

# Stop everything
./stop_services.sh
```

Each service also runs standalone via `mvn -pl <module> exec:java -Dexec.mainClass=<MainClass>`; see `start_services.sh` for the module → main-class mapping. All runtime parameters (ports, Redis/RabbitMQ URLs, folder paths, retry/caching settings, multicast group) live in `config.properties` at the project root.

## Project layout

```
.
├── common/          Invoice/ClientInfo/Item models, Config, RabbitMQ + Redis wrappers, logging, hashing
├── watcher/          Folder watcher → TCP sender
├── parser/           TCP socket server → RMI validation → MQ publish
├── validator/        RMI validation server
├── enrichment/       FX rate lookup, Redis caching, retry listener
├── approval/         Auto-approval / pending-approval MQ consumer
├── approval-gui/     JavaFX client for manual approve/reject decisions
├── aggregator/       Redis persistence + UDP multicast notifications
├── monitor-gui/      JavaFX multicast listener / live invoice table
├── restapi/          Jersey REST API reading from Redis
├── tools/            Test invoice generator (valid + deliberately invalid XML)
├── invoices/         inbox / processed / error folders
├── config.properties
├── start_services.sh / stop_services.sh
└── projekat.pdf      Assignment specification
```

## Status

✅ Watcher with buffered TCP framing + SHA-256 integrity ✅ Parser with integrity check, duplicate detection, RMI validation ✅ Validator (JIB/date/items/currency/amount/email rules) ✅ Enrichment with Redis-cached FX lookup and scheduled retry ✅ Approval with auto-approve threshold + GUI-driven manual approval ✅ Aggregator with Redis persistence + UDP multicast ✅ Monitor GUI (multicast, no polling) ✅ REST API over Redis ✅ Test-data generator (100+ invoices, valid and invalid)

## Contact

Elektrotehnički fakultet Banja Luka — Katedra za računarsku tehniku i informatiku
Project for the course: Mrežno i distribuirano programiranje
