package gg.vape.service.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gg.vape.service.store.AccountRecord;
import gg.vape.service.store.AuthChallengeRecord;
import gg.vape.service.store.FileStore;
import gg.vape.service.store.PublicProfileRecord;
import gg.vape.service.store.PublicProfileReportRecord;
import gg.vape.service.store.PublicProfileReviewRecord;
import gg.vape.service.store.PublicProfileReviewResponseRecord;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

public final class LegacyHttpServer implements AutoCloseable {
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final FileStore store;
    private final HttpServer server;

    public LegacyHttpServer(String bindAddress, int port, FileStore store) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 64);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "vape-http");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/health".equals(path)) {
                sendJson(exchange, 200, object("status", "UP", "time", Instant.now().toString()));
                return;
            }
            if ("/loader/login".equals(path)) {
                handleLoaderLogin(exchange);
                return;
            }
            if ("/api/v1/app-auth/generate".equals(path)) {
                handleGenerate(exchange);
                return;
            }
            if ("/api/v1/app-auth/status".equals(path)) {
                handleAuthStatus(exchange);
                return;
            }
            if (path.startsWith("/app-auth/proceed/")) {
                handleProceed(exchange, path.substring("/app-auth/proceed/".length()));
                return;
            }
            if (path.startsWith("/admin/")) {
                handleAdmin(exchange, path);
                return;
            }
            if (path.startsWith("/api/v1/")) {
                handleLegacyApi(exchange, path.substring("/api/v1/".length()));
                return;
            }
            sendError(exchange, 404, "Unknown endpoint");
        } catch (IllegalArgumentException exception) {
            sendEnvelope(exchange, false, JsonNull.INSTANCE, exception.getMessage());
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            sendError(exchange, 500, "Internal service error");
        } finally {
            exchange.close();
        }
    }

    private void handleGenerate(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        Map<String, String> form = parseForm(readBody(exchange));
        AuthChallengeRecord challenge = store.createChallenge(
                form.getOrDefault("edition", "v4"), form.getOrDefault("hwid", "unknown"));
        sendText(exchange, 200, "text/plain; charset=utf-8", challenge.challenge);
    }

    private void handleLoaderLogin(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        JsonObject request = readJsonObject(exchange);
        String username = request.has("username") ? request.get("username").getAsString() : "";
        FileStore.LoaderLoginResult login = store.loginByUsername(username);
        JsonObject response = new JsonObject();
        response.addProperty("successful", true);
        response.addProperty("token", login.token());
        response.addProperty("username", login.account().username);
        sendJson(exchange, 200, response);
    }

    private void handleProceed(HttpExchange exchange, String challenge) throws IOException {
        requireMethod(exchange, "GET");
        if (!store.approveChallenge(challenge)) {
            sendText(exchange, 404, "text/html; charset=utf-8",
                    "<!doctype html><title>Vape Auth</title><h1>Invalid or expired challenge</h1>");
            return;
        }
        sendText(exchange, 200, "text/html; charset=utf-8",
                "<!doctype html><title>Vape Auth</title><h1>Authentication complete</h1>");
    }

    private void handleAuthStatus(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        String challengeValue = parseForm(readBody(exchange)).get("token");
        Optional<AuthChallengeRecord> result = store.challenge(challengeValue);
        JsonObject response = new JsonObject();
        if (result.isEmpty() || result.get().expiresAt < System.currentTimeMillis()) {
            response.addProperty("status", "timed out");
        } else if (!result.get().approved) {
            response.addProperty("status", "pending");
        } else {
            response.addProperty("status", "success");
            response.addProperty("token", result.get().accessToken);
        }
        sendJson(exchange, 200, response);
    }

    private void handleLegacyApi(HttpExchange exchange, String relativePath) throws IOException {
        List<String> parts = new ArrayList<>(Arrays.asList(relativePath.split("/")));
        parts.removeIf(String::isEmpty);
        if (parts.size() < 2) {
            sendError(exchange, 404, "Missing token or operation");
            return;
        }
        String token = parts.remove(0);
        AccountRecord account = store.account(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid access token"));
        String operation = String.join("/", parts);

        // ==================== 认证 ====================
        if ("authenticated".equals(operation)) {
            requireMethod(exchange, "GET");
            sendEnvelope(exchange, true, account.accountJson(), null);
        } else if (operation.startsWith("register/")) {
            requireMethod(exchange, "GET");
            String code = operation.substring("register/".length());
            store.register(token, code);
            sendEnvelope(exchange, true, gson.toJsonTree(true), null);
        } else if ("settings/load/global".equals(operation)) {
            requireMethod(exchange, "GET");
            sendEnvelope(exchange, true, store.globalSettings(token), null);
        } else if ("settings/save/global".equals(operation)) {
            requireMethod(exchange, "POST");
            JsonObject settings = readJsonObject(exchange);
            store.saveGlobalSettings(token, settings);
            sendJson(exchange, 200, settings);
        } else if ("settings/load/online".equals(operation)) {
            requireMethod(exchange, "GET");
            sendEnvelope(exchange, true, store.onlineSettings(token), null);
        } else if ("settings/save/online".equals(operation)) {
            requireMethod(exchange, "POST");
            JsonObject settings = readJsonObject(exchange);
            store.saveOnlineSettings(token, settings);
            sendJson(exchange, 200, settings);
        } else if ("profile/private/all".equals(operation)) {
            requireMethod(exchange, "GET");
            sendEnvelope(exchange, true, store.privateData(token), null);
        } else if ("profile/private/save/user".equals(operation)) {
            requireMethod(exchange, "POST");
            store.savePrivateUserData(token, readJson(exchange));
            sendEnvelope(exchange, true, gson.toJsonTree(true), null);
        } else if ("profile/private/save/profile".equals(operation)) {
            requireMethod(exchange, "POST");
            sendEnvelope(exchange, true, store.savePrivateProfiles(token, readJsonObject(exchange)), null);
        } else if ("profile/private/reserve".equals(operation)) {
            requireMethod(exchange, "POST");
            sendEnvelope(exchange, true, gson.toJsonTree(store.reserveProfileId(token)), null);
        }

        // ==================== 云配市场 API ====================
        else if ("profile/public/tags".equals(operation)) {
            requireMethod(exchange, "GET");
            int limit = parseQueryParam(exchange, "limit", 20);
            sendEnvelope(exchange, true, store.getPopularTags(limit), null);
        } else if ("profile/public/list".equals(operation)) {
            requireMethod(exchange, "POST");
            JsonObject request = readJsonObject(exchange);
            int page = request.has("page") ? request.get("page").getAsInt() : 0;
            int size = request.has("size") ? request.get("size").getAsInt() : 20;
            String sortBy = request.has("mode") ? request.get("mode").getAsString() : "updatedDate";
            if (request.has("sortBy")) {
                sortBy = request.get("sortBy").getAsString();
            }
            String searchQuery = request.has("search") ? request.get("search").getAsString() : null;
            List<String> tags = new ArrayList<>();
            if (request.has("tags")) {
                JsonArray tagsArray = request.getAsJsonArray("tags");
                for (JsonElement tag : tagsArray) {
                    tags.add(tag.getAsString());
                }
            }
            sendEnvelope(exchange, true, store.listPublicProfiles(account.userId, page, size, sortBy, searchQuery, tags.isEmpty() ? null : tags), null);
        } else if ("profile/public/create".equals(operation)) {
            requireMethod(exchange, "POST");
            PublicProfileRecord created = store.createPublicProfile(token, readJsonObject(exchange));
            sendEnvelope(exchange, true, created.toJson(), null);
        } else if ("profile/public/edit".equals(operation)) {
            requireMethod(exchange, "POST");
            JsonObject request = readJsonObject(exchange);
            if (!request.has("profileId")) {
                throw new IllegalArgumentException("Missing profileId");
            }
            long profileId = request.get("profileId").getAsLong();
            PublicProfileRecord updated = store.updatePublicProfile(token, profileId, request);
            sendEnvelope(exchange, true, updated.toJson(), null);
        } else if (operation.startsWith("profile/public/") && operation.endsWith("/delete")) {
            requireMethod(exchange, "DELETE");
            String id = operation.substring("profile/public/".length(), operation.length() - "/delete".length());
            long profileId = Long.parseLong(id);
            boolean deleted = store.deletePublicProfileById(token, profileId);
            sendEnvelope(exchange, true, gson.toJsonTree(deleted), null);
        } else if (operation.startsWith("profile/public/") && operation.endsWith("/view")) {
            requireMethod(exchange, "GET");
            String id = operation.substring("profile/public/".length(), operation.length() - "/view".length());
            long profileId = Long.parseLong(id);
            JsonObject full = store.getProfileWithFullDetails(profileId, account.userId);
            sendEnvelope(exchange, full != null, full != null ? full : JsonNull.INSTANCE, full != null ? null : "Profile not found");
        } else if (operation.startsWith("profile/public/") && operation.endsWith("/download")) {
            requireMethod(exchange, "GET");
            String id = operation.substring("profile/public/".length(), operation.length() - "/download".length());
            long profileId = Long.parseLong(id);
            Optional<PublicProfileRecord> profile = store.getPublicProfile(profileId);
            if (profile.isEmpty() || !profile.get().listedPublicly) {
                sendEnvelope(exchange, false, JsonNull.INSTANCE, "Profile not found or not public");
            } else {
                store.incrementDownloads(profileId);
                sendEnvelope(exchange, true, profile.get().toJson(), null);
            }
        } else if (operation.startsWith("profile/public/") && operation.endsWith("/update")) {
            requireMethod(exchange, "GET");
            String id = operation.substring("profile/public/".length(), operation.length() - "/update".length());
            long profileId = Long.parseLong(id);
            JsonObject updateData = store.getProfileForUpdate(profileId);
            if (updateData == null) {
                sendEnvelope(exchange, false, JsonNull.INSTANCE, "Profile not found");
            } else {
                sendEnvelope(exchange, true, updateData, null);
            }
        } else if ("profile/public/regenerate/sharecode".equals(operation)) {
            requireMethod(exchange, "POST");
            JsonObject request = readJsonObject(exchange);
            if (!request.has("profileId")) {
                throw new IllegalArgumentException("Missing profileId");
            }
            long profileId = request.get("profileId").getAsLong();
            String newCode = store.regenerateShareCode(token, profileId);
            JsonObject response = new JsonObject();
            response.addProperty("shareCode", newCode);
            sendEnvelope(exchange, true, response, null);
        } else if (operation.startsWith("profile/public/sharecode/")) {
            requireMethod(exchange, "GET");
            String shareCode = operation.substring("profile/public/sharecode/".length());
            Optional<PublicProfileRecord> profile = store.getPublicProfileByShareCode(shareCode);
            if (profile.isEmpty() || !profile.get().listedPublicly) {
                sendEnvelope(exchange, false, JsonNull.INSTANCE, "Profile not found");
            } else {
                JsonObject full = store.getProfileWithFullDetails(profile.get().profileId, account.userId);
                sendEnvelope(exchange, true, full, null);
            }
        } else if (operation.startsWith("profile/public/") && operation.endsWith("/statistics")) {
            requireMethod(exchange, "GET");
            String id = operation.substring("profile/public/".length(), operation.length() - "/statistics".length());
            long profileId = Long.parseLong(id);
            sendEnvelope(exchange, true, store.getProfileStatistics(profileId), null);
        }

        // ==================== 评价 API ====================
        else if ("profile/public/review/create".equals(operation)) {
            requireMethod(exchange, "POST");
            JsonObject request = readJsonObject(exchange);
            if (!request.has("profileId") || !request.has("reason") || !request.has("liked")) {
                throw new IllegalArgumentException("Missing required fields: profileId, reason, liked");
            }
            long profileId = request.get("profileId").getAsLong();
            String message = request.get("reason").getAsString();
            boolean liked = request.get("liked").getAsBoolean();
            PublicProfileReviewRecord review = store.createReview(token, profileId, message, liked);
            sendEnvelope(exchange, true, review.toJson(), null);
        } else if (operation.startsWith("profile/public/review/delete/")) {
            requireMethod(exchange, "DELETE");
            String id = operation.substring("profile/public/review/delete/".length());
            long reviewId = Long.parseLong(id);
            boolean deleted = store.deleteReview(token, reviewId);
            sendEnvelope(exchange, true, gson.toJsonTree(deleted), null);
        } else if (operation.startsWith("profile/public/review/respond/")) {
            requireMethod(exchange, "POST");
            String id = operation.substring("profile/public/review/respond/".length());
            long reviewId = Long.parseLong(id);
            JsonObject request = readJsonObject(exchange);
            if (!request.has("message")) {
                throw new IllegalArgumentException("Missing message");
            }
            String message = request.get("message").getAsString();
            PublicProfileReviewResponseRecord responseRecord = store.createReviewResponse(token, reviewId, message);
            sendEnvelope(exchange, true, responseRecord.toJson(), null);
        } else if (operation.startsWith("profile/public/review/delete/response/")) {
            requireMethod(exchange, "DELETE");
            String id = operation.substring("profile/public/review/delete/response/".length());
            long responseId = Long.parseLong(id);
            boolean deleted = store.deleteReviewResponse(token, responseId);
            sendEnvelope(exchange, true, gson.toJsonTree(deleted), null);
        } else if (operation.startsWith("profile/public/review/view/")) {
            requireMethod(exchange, "GET");
            String rest = operation.substring("profile/public/review/view/".length());
            String[] parts1 = rest.split("/");
            if (parts1.length != 2) {
                throw new IllegalArgumentException("Invalid review view path");
            }
            long profileId = Long.parseLong(parts1[0]);
            long page = Long.parseLong(parts1[1]);
            sendEnvelope(exchange, true, store.getReviewPage(profileId, page), null);
        } else if ("profile/public/review/mark".equals(operation)) {
            requireMethod(exchange, "POST");
            JsonObject request = readJsonObject(exchange);
            if (!request.has("profileId") || !request.has("reviewIds")) {
                throw new IllegalArgumentException("Missing profileId or reviewIds");
            }
            JsonArray reviewIdsJson = request.getAsJsonArray("reviewIds");
            for (JsonElement element : reviewIdsJson) {
                long reviewId = element.getAsLong();
                store.markReviewRead(reviewId);
            }
            sendEnvelope(exchange, true, gson.toJsonTree(true), null);
        } else if ("profile/public/review/mark/all".equals(operation)) {
            requireMethod(exchange, "POST");
            JsonObject request = readJsonObject(exchange);
            if (!request.has("profileId")) {
                throw new IllegalArgumentException("Missing profileId");
            }
            long profileId = request.get("profileId").getAsLong();
            for (PublicProfileReviewRecord review : store.getReviewsForProfile(profileId)) {
                if (!review.read) {
                    store.markReviewRead(review.reviewId);
                }
            }
            sendEnvelope(exchange, true, gson.toJsonTree(true), null);
        }

        // ==================== 举报 API ====================
        else if (operation.startsWith("profile/public/reports/create/review/")) {
            requireMethod(exchange, "POST");
            String id = operation.substring("profile/public/reports/create/review/".length());
            long reviewId = Long.parseLong(id);
            JsonObject request = readJsonObject(exchange);
            if (!request.has("reason")) {
                throw new IllegalArgumentException("Missing reason");
            }
            String reason = request.get("reason").getAsString();
            PublicProfileReviewRecord review = store.getReview(reviewId);
            if (review == null) {
                sendEnvelope(exchange, false, JsonNull.INSTANCE, "Review not found");
            } else {
                store.createReport(token, review.profileId, reason, "");
                sendEnvelope(exchange, true, gson.toJsonTree(true), null);
            }
        } else if (operation.startsWith("profile/public/reports/create/response/")) {
            requireMethod(exchange, "POST");
            String id = operation.substring("profile/public/reports/create/response/".length());
            long responseId = Long.parseLong(id);
            JsonObject request = readJsonObject(exchange);
            if (!request.has("reason")) {
                throw new IllegalArgumentException("Missing reason");
            }
            String reason = request.get("reason").getAsString();
            Optional<PublicProfileReviewResponseRecord> response = store.getReviewResponse(responseId);
            if (response.isEmpty()) {
                sendEnvelope(exchange, false, JsonNull.INSTANCE, "Response not found");
            } else {
                // 查找关联的review获取profileId
                long profileId = 0;
                for (PublicProfileReviewRecord review : store.getReviewsForProfile(0)) {
                    if (review.responseId != null && review.responseId == responseId) {
                        profileId = review.profileId;
                        break;
                    }
                }
                if (profileId > 0) {
                    store.createReport(token, profileId, reason, "");
                }
                sendEnvelope(exchange, true, gson.toJsonTree(true), null);
            }
        }

        // ==================== 通知 API ====================
        else if ("profile/public/notifications/unread/count".equals(operation)) {
            requireMethod(exchange, "GET");
            long count = store.getUnreadNotificationCount(account.userId);
            JsonObject response = new JsonObject();
            response.addProperty("unreadCount", count);
            sendEnvelope(exchange, true, response, null);
        } else if ("profile/public/notifications/clear".equals(operation)) {
            requireMethod(exchange, "POST");
            store.clearUnreadNotifications(account.userId);
            sendEnvelope(exchange, true, gson.toJsonTree(true), null);
        }

        // ==================== 兼容旧API ====================
        else if ("profile/public/tags".equals(operation) || "profile/public/tags/popular".equals(operation)) {
            requireMethod(exchange, "GET");
            int limit = parseQueryParam(exchange, "limit", 20);
            sendEnvelope(exchange, true, store.getPopularTags(limit), null);
        } else {
            sendEnvelope(exchange, false, JsonNull.INSTANCE, "Unsupported operation: " + operation);
        }
    }

    private void handleAdmin(HttpExchange exchange, String path) throws IOException {
        requireMethod(exchange, "GET");
        if ("/admin/health".equals(path)) {
            sendJson(exchange, 200, object("status", "UP"));
        } else if ("/admin/public-profiles".equals(path)) {
            sendJson(exchange, 200, store.publicProfiles());
        } else {
            sendError(exchange, 404, "Unknown admin endpoint");
        }
    }

    private void sendEnvelope(HttpExchange exchange, boolean successful, JsonElement data, String error)
            throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("successful", successful);
        response.add("data", data == null ? JsonNull.INSTANCE : data);
        if (error == null) {
            response.add("error", JsonNull.INSTANCE);
        } else {
            response.addProperty("error", error);
        }
        sendJson(exchange, 200, response);
    }

    private static void requireMethod(HttpExchange exchange, String expected) {
        if (!expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("Expected " + expected + " request");
        }
    }

    private JsonElement readJson(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        return body.isBlank() ? JsonNull.INSTANCE : JsonParser.parseString(body);
    }

    private JsonObject readJsonObject(HttpExchange exchange) throws IOException {
        JsonElement body = readJson(exchange);
        if (!body.isJsonObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return body.getAsJsonObject();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String form) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : form.split("&")) {
            int separator = field.indexOf('=');
            if (separator >= 0) {
                values.put(decode(field.substring(0, separator)), decode(field.substring(separator + 1)));
            }
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, int status, JsonElement json) throws IOException {
        sendText(exchange, status, "application/json; charset=utf-8", gson.toJson(json));
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, object("error", message));
    }

    private static void sendText(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private JsonObject object(String... entries) {
        JsonObject result = new JsonObject();
        for (int index = 0; index < entries.length; index += 2) {
            result.addProperty(entries[index], entries[index + 1]);
        }
        return result;
    }

    private int parseQueryParam(HttpExchange exchange, String paramName, int defaultValue) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return defaultValue;
        try {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=");
                if (parts.length == 2 && parts[0].equals(paramName)) {
                    return Integer.parseInt(parts[1]);
                }
            }
        } catch (NumberFormatException ignored) {}
        return defaultValue;
    }
}