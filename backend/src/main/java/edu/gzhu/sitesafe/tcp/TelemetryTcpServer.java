package edu.gzhu.sitesafe.tcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.service.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class TelemetryTcpServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(TelemetryTcpServer.class);
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private final TelemetryService telemetryService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int port;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public TelemetryTcpServer(TelemetryService telemetryService, ObjectMapper objectMapper,
                              @Value("${app.tcp.enabled:true}") boolean enabled,
                              @Value("${app.tcp.port:9100}") int port) {
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.port = port;
    }

    @Override
    public void start() {
        if (!enabled || running) return;
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            acceptThread = new Thread(this::acceptLoop, "telemetry-tcp-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("Telemetry TCP server listening on {}", port);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to start telemetry TCP server on port " + port, ex);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(30_000);
                clients.submit(() -> handle(socket));
            } catch (Exception ex) {
                if (running) log.warn("TCP accept failed: {}", ex.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket; var input = new DataInputStream(socket.getInputStream()); var output = new DataOutputStream(socket.getOutputStream())) {
            while (running && !socket.isClosed()) {
                int length = input.readInt();
                if (length <= 0 || length > MAX_FRAME_BYTES) throw new IllegalArgumentException("invalid frame length");
                byte[] payload = input.readNBytes(length);
                if (payload.length != length) throw new IllegalArgumentException("incomplete frame");
                Map<String, Object> ack;
                try {
                    var request = objectMapper.readValue(payload, TelemetryService.TelemetryRequest.class);
                    var result = telemetryService.ingest(request);
                    ack = Map.of("success", true, "messageId", result.messageId(), "insertedMetrics", result.insertedMetrics(), "duplicateMetrics", result.duplicateMetrics(), "receivedAt", Instant.now().toString());
                } catch (Exception ex) {
                    ack = Map.of("success", false, "message", ex.getMessage() == null ? "invalid payload" : ex.getMessage(), "receivedAt", Instant.now().toString());
                }
                byte[] response = objectMapper.writeValueAsString(ack).getBytes(StandardCharsets.UTF_8);
                output.writeInt(response.length);
                output.write(response);
                output.flush();
            }
        } catch (Exception ex) {
            log.debug("TCP client disconnected: {}", ex.getMessage());
        }
    }

    @Override
    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        clients.shutdownNow();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
