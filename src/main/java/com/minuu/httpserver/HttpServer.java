package com.minuu.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A concurrent HTTP/1.1 server built on plain sockets.
 *
 * <p>Design: one accept thread takes connections from a {@code ServerSocket}
 * and hands each one to a fixed-size thread pool. Each worker serves a
 * connection for as long as the client keeps it alive (HTTP/1.1
 * keep-alive), parsing and routing one request at a time on that socket.
 * A fixed pool bounds the thread count, so a flood of connections queues
 * instead of exhausting the process. Shutdown is graceful: the accept loop
 * stops, the socket closes, and the pool drains with a timeout.</p>
 */
public final class HttpServer {

    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int requestedPort;
    private final int threads;
    private final Router router;
    private final ServerStats stats;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private volatile boolean running;

    public HttpServer(int requestedPort, int threads, Router router) {
        this.requestedPort = requestedPort;
        this.threads = threads;
        this.router = router;
        this.stats = new ServerStats();
    }

    public ServerStats stats() {
        return stats;
    }

    /** The port actually bound (differs from the requested port when 0 was given). */
    public int getPort() {
        return serverSocket == null ? requestedPort : serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(requestedPort);
        pool = Executors.newFixedThreadPool(threads);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing the socket is what unblocks the accept loop
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        stats.connectionOpened();
        try {
            // Disable Nagle's algorithm: request/response traffic is made of
            // small writes, and Nagle plus the peer's delayed ACK would add
            // a ~40 ms stall to every exchange. This is the single biggest
            // latency win in the server; see README.md.
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while (running) {
                HttpRequest request;
                try {
                    request = HttpRequest.parse(in);
                } catch (HttpRequest.BadRequestException e) {
                    HttpResponse.badRequest(e.getMessage()).writeTo(out, false);
                    break;
                } catch (SocketTimeoutException e) {
                    break; // idle connection: close it quietly
                }
                if (request == null) {
                    break; // peer closed the connection
                }
                long started = System.nanoTime();
                HttpResponse response;
                try {
                    response = router.handle(request);
                } catch (Exception e) {
                    response = HttpResponse.internalError("handler failed: " + e.getMessage());
                }
                boolean keepAlive = request.keepAlive();
                response.writeTo(out, keepAlive);
                stats.requestServed();
                log(request, response, started);
                if (!keepAlive) {
                    break;
                }
            }
        } catch (IOException e) {
            // peer went away mid-request; nothing to report
        } finally {
            stats.connectionClosed();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void log(HttpRequest request, HttpResponse response, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000;
        System.out.printf("[%s] \"%s %s\" %d %dms%n",
                LocalDateTime.now().format(LOG_TIME),
                request.method(), request.path(), response.status(), millis);
    }
}
