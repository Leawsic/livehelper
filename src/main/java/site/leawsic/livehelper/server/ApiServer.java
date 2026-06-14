package site.leawsic.livehelper.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.templates.MotionTemplates;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.render.StreamManager;
import site.leawsic.livehelper.storage.StorageManager;
import site.leawsic.livehelper.util.AngleConvert;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class ApiServer {
    private static final int PORT = 23512;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HttpServer server;

    private ApiServer() {}

    public static void start() throws IOException {
        if (server != null) return;

        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "LiveHelper-API");
            thread.setDaemon(true);
            return thread;
        }));

        server.createContext("/api/clips", new ClipsHandler());
        server.createContext("/api/managers", new ManagersHandler());
        server.createContext("/api/manager/", new ManagerDetailHandler("/api/manager/"));
        server.createContext("/api/pose", new PoseHandler());
        server.createContext("/api/templates", new TemplatesHandler());
        server.createContext("/", new StaticFileHandler());

        server.start();
        LiveHelper.LOGGER.info("API server started on port {}", PORT);
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("error", message == null ? "Unknown error" : message);
        sendJson(exchange, code, error.toString());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int extractId(String path, String prefix) {
        String rest = path.substring(prefix.length());
        if (rest.contains("/")) rest = rest.substring(0, rest.indexOf('/'));
        return Integer.parseInt(rest);
    }

    static abstract class BaseRestHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public final void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            handleInternal(exchange);
        }

        protected abstract void handleInternal(HttpExchange exchange) throws IOException;
    }

    static class TemplatesHandler extends BaseRestHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET required");
                return;
            }
            sendJson(exchange, 200, GSON.toJson(MotionTemplates.getAvailable()));
        }
    }

    static class ClipsHandler extends BaseRestHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(method)) {
                    sendJson(exchange, 200, GSON.toJson(StorageManager.getInstance().getAllClips()));
                } else if ("POST".equals(method)) {
                    Clip created = StorageManager.getInstance().createClip(GSON.fromJson(readBody(exchange), Clip.class));
                    JsonObject res = new JsonObject();
                    res.addProperty("id", created.id());
                    sendJson(exchange, 201, res.toString());
                } else if ("PUT".equals(method)) {
                    int id = extractId(path, "/api/clips/");
                    StorageManager.getInstance().updateClip(id, GSON.fromJson(readBody(exchange), Clip.class));
                    sendJson(exchange, 200, "{}");
                } else if ("DELETE".equals(method)) {
                    int id = extractId(path, "/api/clips/");
                    StorageManager.getInstance().deleteClip(id);
                    sendJson(exchange, 200, "{}");
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            } catch (Exception e) {
                sendError(exchange, 400, e.getMessage());
            }
        }
    }

    static class ManagersHandler extends BaseRestHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                if (path.startsWith("/api/managers/") && path.length() > "/api/managers/".length()) {
                    handleManagerDetail(exchange, "/api/managers/");
                    return;
                }

                if ("GET".equals(method)) {
                    sendJson(exchange, 200, GSON.toJson(StorageManager.getInstance().getAllManagers()));
                } else if ("POST".equals(method)) {
                    Manager created = StorageManager.getInstance().createManager(GSON.fromJson(readBody(exchange), Manager.class));
                    JsonObject res = new JsonObject();
                    res.addProperty("id", created.id());
                    sendJson(exchange, 201, res.toString());
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            } catch (Exception e) {
                sendError(exchange, 400, e.getMessage());
            }
        }
    }

    static class ManagerDetailHandler extends BaseRestHandler {
        private final String prefix;

        ManagerDetailHandler(String prefix) {
            this.prefix = prefix;
        }

        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            try {
                handleManagerDetail(exchange, prefix);
            } catch (Exception e) {
                sendError(exchange, 400, e.getMessage());
            }
        }
    }

    private static void handleManagerDetail(HttpExchange exchange, String prefix) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        int id = extractId(path, prefix);
        String action = path.substring((prefix + id).length());

        switch (action) {
            case "" -> {
                if ("PUT".equals(method)) {
                    StorageManager.getInstance().updateManager(id, GSON.fromJson(readBody(exchange), Manager.class));
                    sendJson(exchange, 200, "{}");
                } else if ("DELETE".equals(method)) {
                    StreamManager.INSTANCE.stop(id);
                    StorageManager.getInstance().deleteManager(id);
                    sendJson(exchange, 200, "{}");
                } else {
                    sendError(exchange, 405, "PUT or DELETE required");
                }
            }
            case "/start" -> {
                if (!"POST".equals(method)) {
                    sendError(exchange, 405, "POST required");
                    return;
                }
                StreamManager.INSTANCE.start(id);
                sendJson(exchange, 200, "{}");
            }
            case "/stop" -> {
                if (!"POST".equals(method)) {
                    sendError(exchange, 405, "POST required");
                    return;
                }
                StreamManager.INSTANCE.stop(id);
                sendJson(exchange, 200, "{}");
            }
            case "/status" -> {
                if (!"GET".equals(method)) {
                    sendError(exchange, 405, "GET required");
                    return;
                }
                JsonObject res = new JsonObject();
                res.addProperty("status", StreamManager.INSTANCE.getStatus(id).name().toLowerCase());
                sendJson(exchange, 200, res.toString());
            }
            default -> sendError(exchange, 404, "Unknown action: " + action);
        }
    }

    static class PoseHandler extends BaseRestHandler {
        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET required");
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                sendError(exchange, 503, "Player not ready");
                return;
            }

            JsonObject pose = new JsonObject();
            pose.addProperty("x", mc.player.getX());
            pose.addProperty("y", mc.player.getEyeY());
            pose.addProperty("z", mc.player.getZ());
            var q = AngleConvert.toQuaternion(mc.player.getXRot(), mc.player.getYRot(), 0f);
            pose.addProperty("qx", q.x);
            pose.addProperty("qy", q.y);
            pose.addProperty("qz", q.z);
            pose.addProperty("qw", q.w);
            sendJson(exchange, 200, pose.toString());
        }
    }

    static class StaticFileHandler extends BaseRestHandler {
        private static final String RESOURCE_BASE = "/assets/livehelper/web";

        @Override
        protected void handleInternal(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            if (path.contains("..")) {
                sendError(exchange, 403, "Forbidden");
                return;
            }

            InputStream is = getClass().getResourceAsStream(RESOURCE_BASE + path);
            if (is == null) is = getClass().getResourceAsStream(RESOURCE_BASE + "/index.html");
            if (is == null) {
                sendError(exchange, 404, "Not found");
                return;
            }

            String contentType = "application/octet-stream";
            if (path.endsWith(".html")) contentType = "text/html; charset=utf-8";
            else if (path.endsWith(".js")) contentType = "application/javascript; charset=utf-8";
            else if (path.endsWith(".css")) contentType = "text/css; charset=utf-8";
            else if (path.endsWith(".png")) contentType = "image/png";
            else if (path.endsWith(".svg")) contentType = "image/svg+xml";

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            is.transferTo(exchange.getResponseBody());
            is.close();
            exchange.getResponseBody().close();
        }
    }
}
