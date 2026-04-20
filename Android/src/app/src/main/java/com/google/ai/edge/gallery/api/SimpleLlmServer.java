package com.google.ai.edge.gallery.api;

import android.util.Log;
import com.google.ai.edge.gallery.data.Model;
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kotlin.Unit;

/**
 * A minimal raw-socket HTTP/1.1 server that exposes the on-device LLM via a REST-like API.
 *
 * Endpoints:
 *   GET  /health           → {"status":"ok","serverRunning":true}
 *   GET  /models           → {"models":[...]} – list of loaded models from ModelRegistry
 *   POST /chat             → {"prompt":"…","modelName":"…"} → {"reply":"…","error":null}
 *   OPTIONS *              → CORS pre-flight (allows any origin)
 */
public class SimpleLlmServer {
    private static final String TAG = "SimpleLlmServer";
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static ServerSocket serverSocket;
    private static volatile boolean isStarted = false;
    private static final List<String> logs = new CopyOnWriteArrayList<>();
    private static final Gson gson = new Gson();

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public static void start(int port) {
        if (isStarted) return;
        isStarted = true;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                addLog("Server started on port " + port);
                while (isStarted) {
                    Socket client = serverSocket.accept();
                    executor.execute(() -> handleClient(client));
                }
            } catch (Exception e) {
                if (isStarted) {
                    addLog("Server error: " + e.getMessage());
                    isStarted = false;
                }
            }
        }).start();
    }

    public static void stop() {
        isStarted = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        addLog("Server stopped");
    }

    public static List<String> getLogs() {
        List<String> list = new ArrayList<>(logs);
        Collections.reverse(list);
        return list;
    }

    public static boolean isRunning() { return isStarted; }

    // ── Request handling ──────────────────────────────────────────────────────

    private static void handleClient(Socket socket) {
        try (socket) {
            java.io.InputStream in = socket.getInputStream();
            java.io.OutputStream out = socket.getOutputStream();

            // Read headers byte by byte
            StringBuilder headerBuilder = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                headerBuilder.append((char) b);
                if (headerBuilder.toString().endsWith("\r\n\r\n")) {
                    break;
                }
            }

            String fullHeaders = headerBuilder.toString();
            if (fullHeaders.isEmpty()) return;

            String[] lines = fullHeaders.split("\r\n");
            String requestLine = lines[0];
            addLog("← " + requestLine);

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path   = parts.length > 1 ? parts[1] : "/";

            // Read headers
            int contentLength = 0;
            for (String header : lines) {
                if (header.toLowerCase(Locale.US).startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(header.substring(15).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }

            // ── CORS pre-flight ────────────────────────────────────────────────
            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendResponse(out, 204, "No Content", "", "");
                return;
            }

            // ── Route ─────────────────────────────────────────────────────────
            if ("GET".equalsIgnoreCase(method) && path.equals("/health")) {
                handleHealth(out);

            } else if ("GET".equalsIgnoreCase(method) && path.equals("/models")) {
                handleModels(out);

            } else if ("POST".equalsIgnoreCase(method) && path.equals("/chat")) {
                byte[] bodyBytes = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = in.read(bodyBytes, read, contentLength - read);
                    if (r == -1) break;
                    read += r;
                }
                String rawBody = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
                handleChat(out, rawBody);

            } else {
                sendResponse(out, 404, "Not Found",
                        "application/json",
                        "{\"error\":\"Not found: " + method + " " + path + "\"}");
            }

        } catch (Exception e) {
            addLog("Client error: " + e.getMessage());
        }
    }

    // ── Endpoint handlers ─────────────────────────────────────────────────────

    private static void handleHealth(java.io.OutputStream out) {
        String body = gson.toJson(new HealthResponse(true));
        sendResponse(out, 200, "OK", "application/json", body);
    }

    private static void handleModels(java.io.OutputStream out) {
        List<String> names = new ArrayList<>();
        for (Model m : ModelRegistry.models) {
            names.add(m.getName());
        }
        String body = "{\"models\":" + gson.toJson(names) + "}";
        sendResponse(out, 200, "OK", "application/json", body);
    }

    private static void handleChat(java.io.OutputStream out, String rawBody) {
        ChatRequest request;
        try {
            request = gson.fromJson(rawBody, ChatRequest.class);
        } catch (Exception e) {
            sendResponse(out, 400, "Bad Request", "application/json",
                    "{\"error\":\"Invalid JSON body\"}");
            return;
        }

        if (request == null || request.prompt == null || request.prompt.isEmpty()) {
            sendResponse(out, 400, "Bad Request", "application/json",
                    "{\"error\":\"Missing 'prompt' field\"}");
            return;
        }
        addLog("Prompt: " + request.prompt);

        // Find model
        Model foundModel = null;
        for (Model m : ModelRegistry.models) {
            if (request.modelName != null) {
                if (request.modelName.equals(m.getName())) { foundModel = m; break; }
            } else if (m.getInstance() != null) {
                foundModel = m; break;
            }
        }

        String responseJson;
        if (foundModel == null) {
            addLog("Error: No active model");
            responseJson = gson.toJson(new ChatResponse("", "No active model loaded. Please load a model in the UI first."));
        } else {
            String reply = runInferenceBlocking(request.prompt, foundModel);
            addLog("Reply generated (" + (reply != null ? reply.length() : 0) + " chars)");
            responseJson = gson.toJson(new ChatResponse(reply != null ? reply : "", null));
        }

        sendResponse(out, 200, "OK", "application/json", responseJson);
    }

    // ── Inference (blocking) ──────────────────────────────────────────────────

    private static String runInferenceBlocking(String prompt, Model model) {
        final StringBuilder result = new StringBuilder();
        final boolean[] done = {false};
        final String[] errorMsg = {null};

        LlmChatModelHelper.INSTANCE.runInference(
            model,
            prompt,
            /* resultListener */ (partial, isDone, thinkingResult) -> {
                if (partial != null && !((String) partial).startsWith("<ctrl")) {
                    result.append((String) partial);
                }
                if ((Boolean) isDone) {
                    synchronized (done) {
                        done[0] = true;
                        done.notifyAll();
                    }
                }
                return Unit.INSTANCE;
            },
            /* cleanUpListener */ () -> Unit.INSTANCE,
            /* onError */ (err) -> {
                errorMsg[0] = (String) err;
                synchronized (done) {
                    done[0] = true;
                    done.notifyAll();
                }
                return Unit.INSTANCE;
            },
            /* images */ Collections.emptyList(),
            /* audioClips */ Collections.emptyList(),
            /* coroutineScope */ null,
            /* extraContext */ null
        );

        synchronized (done) {
            long deadline = System.currentTimeMillis() + 60_000; // 60s timeout
            while (!done[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try { done.wait(remaining); } catch (InterruptedException e) { break; }
            }
        }

        if (errorMsg[0] != null) return "Error: " + errorMsg[0];
        return result.toString();
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static void sendResponse(java.io.OutputStream out, int code, String reason,
                                     String contentType, String bodyStr) {
        try {
            byte[] bodyBytes = bodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n");
            sb.append("Access-Control-Allow-Origin: *\r\n");
            sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            sb.append("Access-Control-Allow-Headers: Content-Type\r\n");
            if (!contentType.isEmpty()) {
                sb.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n");
            }
            sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            sb.append("Connection: close\r\n");
            sb.append("\r\n");
            
            out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
            addLog("→ " + code + " " + reason + (bodyStr.length() > 80 ? " [" + bodyBytes.length + " bytes]" : " " + bodyStr));
        } catch (Exception e) {
            addLog("Error sending response: " + e.getMessage());
        }
    }

    private static void addLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logs.add("[" + time + "] " + msg);
        if (logs.size() > 200) logs.remove(0);
        Log.d(TAG, msg);
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    public static class ChatRequest {
        public String prompt;
        public String modelName;
    }

    public static class ChatResponse {
        public String reply;
        public String error;
        public ChatResponse(String r, String e) { this.reply = r; this.error = e; }
    }

    public static class HealthResponse {
        public String status = "ok";
        public boolean serverRunning;
        public HealthResponse(boolean running) { this.serverRunning = running; }
    }
}
