package dev.kamil.fabric.claude.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Subscription quota (5 hour session and weekly limits) from the Claude OAuth usage endpoint. Reads the access
 * token of Claude Code from ~/.claude/.credentials.json on every poll; never refreshes or logs it.
 */
public final class QuotaClient {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/api/oauth/usage");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NOT_LOGGED_IN = "Not logged in (run claude /login)";
    public static final String LOGIN_EXPIRED = "Login expired — open claude to refresh";

    /** One limit: label, percent used, reset time (may be null) and severity (may be empty). */
    public record Row(String label, double percent, Instant resetsAt, String severity) {
    }

    /** Rows of the last good poll, an error (null when fine) and whether the rows are older than the last poll. */
    public record Quota(List<Row> rows, String error, boolean stale) {

        public static Quota error(String message) {
            return new Quota(List.of(), message, false);
        }
    }

    private final Path credentials;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private Quota last;

    public QuotaClient(Path credentials) {
        this.credentials = credentials;
    }

    public QuotaClient() {
        this(Path.of(System.getProperty("user.home"), ".claude/.credentials.json"));
    }

    /** Polls the endpoint. Blocking: call off the FX thread. */
    public synchronized Quota fetch() {
        String token;
        try {
            if (!Files.isRegularFile(credentials)) {
                return remember(Quota.error(NOT_LOGGED_IN));
            }
            var oauth = JSON.readTree(Files.readString(credentials)).path("claudeAiOauth");
            token = oauth.path("accessToken").asText("");
            if (token.isEmpty()) {
                return remember(Quota.error(NOT_LOGGED_IN));
            }
            if (oauth.path("expiresAt").asLong(Long.MAX_VALUE) < System.currentTimeMillis()) {
                return remember(Quota.error(LOGIN_EXPIRED));
            }
        } catch (IOException | RuntimeException e) {
            return remember(Quota.error(NOT_LOGGED_IN));
        }
        try {
            var request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token).header("anthropic-beta", "oauth-2025-04-20").GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                return remember(Quota.error(LOGIN_EXPIRED));
            }
            if (response.statusCode() == 200) {
                var rows = parse(response.body());
                return remember(new Quota(rows, null, false));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // fall through: keep the last good snapshot
        }
        return staleOrEmpty();
    }

    private Quota remember(Quota quota) {
        last = quota.error() == null ? quota : null;
        return quota;
    }

    private Quota staleOrEmpty() {
        return last == null ? Quota.error("Usage unavailable") : new Quota(last.rows(), null, true);
    }

    /** Pure parse of the endpoint body; prefers limits[], falls back to five_hour / seven_day. */
    public static List<Row> parse(String body) throws IOException {
        var root = JSON.readTree(body);
        var rows = new ArrayList<Row>();
        var limits = root.path("limits");
        if (limits.isArray() && !limits.isEmpty()) {
            for (var limit : limits) {
                var label = switch (limit.path("kind").asText("")) {
                    case "session" -> "5h";
                    case "weekly_all" -> "Week";
                    case "weekly_scoped" -> {
                        var name = limit.path("scope").path("model").path("display_name").asText("");
                        yield name.isEmpty() ? "Week" : "Week · " + name;
                    }
                    default -> null;
                };
                if (label != null && limit.path("percent").isNumber()) {
                    rows.add(new Row(label, limit.path("percent").asDouble(), instant(limit.path("resets_at")),
                            limit.path("severity").asText("")));
                }
            }
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        window(rows, "5h", root.path("five_hour"));
        window(rows, "Week", root.path("seven_day"));
        return rows;
    }

    private static void window(List<Row> rows, String label, JsonNode node) {
        if (node.path("utilization").isNumber()) {
            rows.add(new Row(label, node.path("utilization").asDouble(), instant(node.path("resets_at")), ""));
        }
    }

    private static Instant instant(JsonNode node) {
        try {
            return node.isTextual() ? OffsetDateTime.parse(node.asText()).toInstant() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
