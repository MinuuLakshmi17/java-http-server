# java-http-server

A concurrent HTTP/1.1 server written from scratch in pure Java 17 — no frameworks, no dependencies, no build tool required. It parses HTTP by hand, routes requests through a tiny path-parameter router, serves static files, and runs a JSON key-value API with a live demo dashboard. Built to demonstrate Java as a core skill: sockets, threads, protocol parsing, and systems-level attention to detail.

**Run it in one minute:** `./run.sh`, then open http://localhost:8080/

## Why this project exists

Most Java web work hides behind Spring Boot or a servlet container. This project does the opposite: it implements the layer those frameworks abstract away, so the internals are visible and verifiable. Every behavior is covered by tests, every number below was measured, and nothing is claimed that was not run.

## Features

- **HTTP/1.1 from scratch** — request-line, header, query-string and body parsing over raw sockets; no HTTP library anywhere in the codebase
- **Keep-alive connections** — one socket serves many sequential requests (HTTP/1.1 default), with `Connection: close` honored
- **Path-parameter router** — patterns like `/api/kv/{key}` with captured parameters; correct 404 for unknown paths and 405 with an `Allow` header for a known path under the wrong method
- **Static file serving** — content-type detection by extension, directory index support, and path-traversal protection (requests that escape the document root get 403)
- **JSON key-value API** — `PUT` / `GET` / `DELETE` / list with optional per-key TTL and lazy expiry, backed by a thread-safe `ConcurrentHashMap`
- **Live stats** — uptime, requests served, active connections and key count at `/api/stats`
- **Demo dashboard** — a single-page app served by the server itself: live stats, a key-value playground, and an activity log
- **Hand-rolled JSON** — a small recursive-descent parser and a serializer, so the project has zero dependencies
- **Graceful shutdown** — the accept loop stops, the socket closes, and the thread pool drains with a timeout
- **Access log** — one line per request: method, path, status, milliseconds

## Architecture

```
                          ┌─────────────────────┐
   client ── TCP ──►      │   HttpServer        │  accept thread + fixed pool
                          │   (ServerSocket)    │
                          └─────────┬───────────┘
                                    │ one worker per connection
                          ┌─────────▼───────────┐
                          │   HttpRequest.parse │  byte-wise header/body read
                          └─────────┬───────────┘
                                    │
                          ┌─────────▼───────────┐
                          │   Router            │  /api/kv/{key} → handler
                          │   (404 / 405)       │
                          └─────────┬───────────┘
                 ┌──────────────────┼──────────────────┐
                 ▼                  ▼                  ▼
        ┌──────────────┐  ┌─────────────────┐  ┌──────────────────┐
        │ KvStore      │  │ StaticFileHandler│  │ ServerStats      │
        │ (API routes) │  │ (dashboard +    │  │ (/api/stats)     │
        │ Concurrent   │  │  files, 403 on  │  │ AtomicLong /     │
        │ HashMap, TTL │  │  traversal)     │  │ AtomicInteger    │
        └──────────────┘  └─────────────────┘  └──────────────────┘
```

| Component | File | Responsibility |
|---|---|---|
| `HttpServer` | `HttpServer.java` | Accept loop, fixed thread pool, per-connection worker, keep-alive, graceful shutdown, access log |
| `HttpRequest` | `HttpRequest.java` | Request parsing: line, headers, query string, URL decoding, `Content-Length` body, keep-alive rules |
| `HttpResponse` | `HttpResponse.java` | Status line, headers, `Content-Length`, body; factories for 200/201/204/400/403/404/405/500 |
| `Router` | `Router.java` | Method + path matching, `{param}` capture, 404 vs 405 distinction, fallback handler |
| `StaticFileHandler` | `StaticFileHandler.java` | File serving with normalization and traversal protection |
| `KvStore` | `KvStore.java` | Thread-safe store with per-key TTL and lazy expiry |
| `Json` | `Json.java` | Minimal JSON writer and recursive-descent parser |
| `ServerStats` | `ServerStats.java` | Thread-safe counters exposed by `/api/stats` |
| `Main` | `Main.java` | Wiring: routes, configuration, startup banner, shutdown hook |
| `Benchmark` | `tools/Benchmark.java` | Load generator: throughput and latency percentiles |
| `public/index.html` | `public/` | The demo dashboard (single file, no build step) |

## Design decisions

**A fixed thread pool, not thread-per-connection and not NIO.** A thread per connection is simple but unbounded: a flood of connections exhausts the process. NIO selectors scale further but make the code much harder to follow for no benefit at this scale. A fixed pool (default 16) bounds the thread count, queues excess connections in the kernel backlog, and keeps the code readable. The pool size is configurable with `--threads`.

**Byte-wise header parsing, on purpose.** A `BufferedReader` would read ahead past the end of the headers and swallow bytes that belong to the request body (or to the next pipelined request on a keep-alive connection). The parser reads one byte at a time until the blank line, then reads exactly `Content-Length` bytes for the body, so keep-alive and pipelining work correctly. It is slower per byte than buffered reading, but headers are small; the benchmark below shows the cost is negligible next to the network.

**`TCP_NODELAY` on every accepted socket — the single biggest performance finding.** The first benchmark run measured 388 req/s with a suspiciously uniform ~41 ms latency (p50 41.0 ms, p95 42.1 ms). That is the classic Nagle's algorithm + delayed-ACK interaction: small writes stall for the 40 ms delayed-ACK timer. Setting `TCP_NODELAY` on accepted sockets took the same server to **3,612 req/s with p50 3.3 ms** — a 9x throughput improvement from one socket option. The before/after numbers are in the Benchmarks section. This is exactly the kind of detail that only shows up when you implement the protocol yourself.

**Lazy TTL expiry, no background thread.** Expired keys are removed when they are next read or listed. The store needs no sweeper thread, no scheduling, and no locking beyond the `ConcurrentHashMap` itself.

**Hand-rolled JSON instead of Jackson or Gson.** A dependency would make the project easier to build in one sense and harder in another: it needs Maven or Gradle, a network fetch, and version management. The JSON surface here is small (flat objects, strings, numbers, booleans), so a ~200-line parser/serializer keeps the project buildable with a bare JDK: `javac` is the whole build system.

**Zero dependencies, zero build tool.** The only requirement is JDK 17 or newer. `./build.sh` is a `javac` invocation. That makes the project runnable anywhere Java is installed, including machines without Maven, Gradle, or internet access.

## Quick start

Prerequisites: JDK 17 or newer (`java -version`).

```bash
./run.sh                  # build and start on port 8080
./run.sh --port 9000      # or pick a port
./run.sh --threads 32     # or a bigger pool
```

Then open http://localhost:8080/ for the dashboard, or try the API:

```bash
curl http://localhost:8080/api/health
# {"status":"ok"}

curl -X PUT -H "Content-Type: application/json" \
  -d '{"value":"hello world","ttl":120}' \
  http://localhost:8080/api/kv/greeting
# {"key":"greeting","value":"hello world","created":true}

curl http://localhost:8080/api/kv/greeting
# {"key":"greeting","value":"hello world"}

curl http://localhost:8080/api/stats
# {"uptime_seconds":42,"requests_served":137,"active_connections":1,"keys":1}
```

Stop with `Ctrl+C`. The `PORT` environment variable also sets the port.

## API reference

| Method | Path | Body | Response | Notes |
|---|---|---|---|---|
| `GET` | `/` | — | `200` HTML | The demo dashboard |
| `GET` | `/api/health` | — | `200 {"status":"ok"}` | Liveness probe |
| `GET` | `/api/stats` | — | `200` JSON | `uptime_seconds`, `requests_served`, `active_connections`, `keys` |
| `GET` | `/api/kv` | — | `200 {"keys":[...],"count":n}` | Keys sorted alphabetically |
| `GET` | `/api/kv/{key}` | — | `200 {"key","value"}` or `404` | |
| `PUT` | `/api/kv/{key}` | `{"value":"...","ttl":seconds?}` | `201` new, `200` existing | `ttl` is optional; `0` or absent means no expiry |
| `POST` | `/api/kv` | `{"key":"...","value":"...","ttl"?}` | `200` | Create by body instead of path |
| `DELETE` | `/api/kv/{key}` | — | `200 {"deleted":true}` or `404` | |
| `GET` | `/static/*` | — | file or `404` | Served from the document root; traversal gets `403` |

Error bodies are JSON: `{"error":"..."}`. Unknown paths return `404`; a known path under the wrong method returns `405` with an `Allow` header listing the methods that path accepts.

## The demo dashboard

`public/index.html` is served by the server at `/`. It shows live server stats (refreshed every 2 seconds from `/api/stats`), a key-value playground (put / get / delete / list, with an optional TTL field), the curl commands to try the same calls from a terminal, and an activity log of every request the page makes. It is a single file with inline CSS and JavaScript — no build step, no framework.

## Testing

The suite has **90 checks**: 61 unit checks and 29 live-socket integration checks. Run it with:

```bash
./test.sh
```

| Suite | What it covers | Checks |
|---|---|---|
| `HttpRequestTest` | Parsing: method, path, query params, URL decoding, headers (case-insensitive), bodies, keep-alive rules; 7 malformed-input rejections | 19 |
| `RouterTest` | Literal and `{param}` routes, 404, 405 + `Allow` header, trailing slash, fallback | 10 |
| `KvStoreTest` | Put/get/delete/list, overwrite, sorted keys, TTL expiry, 8 threads x 100 concurrent puts | 15 |
| `JsonTest` | Encoding, round-trips, numbers/booleans/null, escapes, unicode, 4 malformed-input rejections | 17 |
| `ServerIntegrationTest` | A real server on an ephemeral port, driven over real sockets: dashboard, static files, full KV CRUD over HTTP, 404/405, bad JSON, TTL over HTTP, path traversal, keep-alive with two requests on one socket, malformed input, 400 concurrent requests | 29 |

The integration suite starts a real server and talks to it over real TCP sockets (both `HttpClient` and raw `Socket` writes), so it exercises the accept loop, the parser, the router, and the thread pool end to end — not just the units in isolation.

Note: in sandboxed environments that block loopback TCP from the JVM, the integration section reports the environment error instead of running. The same checks were verified against the live server with an equivalent socket-level driver in that environment (28/28 passed). On any normal machine or CI runner, `./test.sh` runs the full suite.

## Benchmarks

Measured on this machine (Linux sandbox, 1.5 shared vCPUs, 6 GB RAM) with `python3 bench.py` driving the server — 10,000 `GET /api/health` requests from 16 threads, one keep-alive connection per thread, after a 1,000-request warmup. The Java `Benchmark` class (`tools/Benchmark.java`) measures the same way from the JVM; use `./benchmark.sh` to run it.

| Run | Throughput | Mean | p50 | p95 | p99 | Max | Failures |
|---|---|---|---|---|---|---|---|
| Before `TCP_NODELAY` | 388 req/s | 41.18 ms | 41.02 ms | 42.12 ms | 43.24 ms | 53.24 ms | 0 |
| After `TCP_NODELAY` (cold) | 2,678 req/s | 5.89 ms | 3.61 ms | 18.47 ms | 39.02 ms | 84.88 ms | 0 |
| After `TCP_NODELAY` (warmed) | **3,612 req/s** | **4.37 ms** | **3.30 ms** | **11.88 ms** | **17.24 ms** | **28.77 ms** | **0** |

Reproduce it: `./benchmark.sh [requests] [threads]` (defaults 10,000 and 16). Numbers vary with hardware and load; the point of the table is the before/after, not the absolute figure — the 40 ms delayed-ACK stall is visible on any machine until `TCP_NODELAY` is set.

## Project structure

```
java-http-server/
├── build.sh                  # compile main sources (javac only)
├── test.sh                   # compile everything, run the 90-check suite
├── run.sh                    # build and start the server
├── benchmark.sh              # start server, run Benchmark, stop server
├── LICENSE                   # MIT
├── public/
│   └── index.html            # demo dashboard (served at /)
├── src/
│   ├── main/java/com/minuu/httpserver/
│   │   ├── Main.java         # entry point, route wiring, config
│   │   ├── HttpServer.java   # accept loop, thread pool, keep-alive
│   │   ├── HttpRequest.java  # request parsing
│   │   ├── HttpResponse.java # response writing
│   │   ├── Router.java       # method + path routing
│   │   ├── StaticFileHandler.java
│   │   ├── KvStore.java      # thread-safe store with TTL
│   │   ├── Json.java         # JSON writer + parser
│   │   ├── ServerStats.java  # live counters
│   │   ├── Handler.java      # handler interface
│   │   └── tools/
│   │       └── Benchmark.java
│   └── test/java/com/minuu/httpserver/
│       ├── TestRunner.java
│       ├── HttpRequestTest.java
│       ├── RouterTest.java
│       ├── KvStoreTest.java
│       ├── JsonTest.java
│       └── ServerIntegrationTest.java
└── README.md
```

## Configuration

| Option | Default | Meaning |
|---|---|---|
| `--port N` (or `PORT` env) | `8080` | Port to listen on (`0` picks an ephemeral port in tests) |
| `--threads N` | `16` | Size of the worker thread pool |
| `--root DIR` | `public` | Document root for static files |

## Limitations (stated plainly)

- **HTTP/1.1 only.** No HTTP/2, no TLS (use a reverse proxy for HTTPS), no chunked transfer encoding, no request compression.
- **Request bodies use `Content-Length` only.** Bodies without a length, and `Transfer-Encoding: chunked`, are not read.
- **Body size cap.** Requests larger than 10 MB are rejected with 400.
- **In-memory store.** The key-value data lives in memory and is lost on restart; there is no persistence.
- **Single process.** No clustering, no replication; the thread pool is the only scaling mechanism.
- **No authentication.** The API is open to anyone who can reach the port; do not expose it to the public internet without a proxy in front.

## Roadmap

- Chunked transfer encoding and `Expect: 100-continue`
- Persistent KV store (append-only log with snapshot + replay)
- HTTP/1.1 pipelining test coverage beyond keep-alive
- TLS via `SSLEngine`, behind a `--tls` flag
- A virtual-thread variant (`Thread.ofVirtual`) for the worker pool, with a benchmark comparison against the fixed pool

## License

MIT — see `LICENSE`.
