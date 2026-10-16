package dev.kamil.fabric.claude.usage;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

/** Compact quota panel: one line and a thin progress bar per subscription limit. */
public final class UsageView extends VBox {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);

    private static final Duration AGE_SHOWN_AFTER = Duration.ofMinutes(5);

    private final SimpleStringProperty summary = new SimpleStringProperty("");
    private String baseSummary = "";
    private Instant fetchedAt;

    public UsageView() {
        super(2);
        setPadding(new Insets(6));
        getStyleClass().add("usage-view");
        update(null, ZoneId.systemDefault(), Instant.now());
    }

    /** Shows a quota; call on the FX thread. */
    public void update(QuotaClient.Quota quota, ZoneId zone, Instant now) {
        var fresh = summary(quota);
        if (!fresh.isEmpty() && !quota.stale()) {
            fetchedAt = now;
        }
        if (!fresh.isEmpty() || fetchedAt == null) {
            baseSummary = fresh;
        }
        refreshAge(now);
        getChildren().clear();
        if (quota == null) {
            return;
        }
        if (quota.error() != null) {
            getChildren().add(muted(quota.error()));
            return;
        }
        for (var row : quota.rows()) {
            var label = new Label(rowLabel(row, zone, now));
            var bar = new ProgressBar(Math.max(0, Math.min(1, row.percent() / 100.0)));
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add("quota-bar");
            var style = colorClass(row.percent());
            if (!style.isEmpty()) {
                label.getStyleClass().add(style);
                bar.getStyleClass().add(style);
            }
            getChildren().addAll(label, bar);
        }
        if (quota.stale()) {
            getChildren().add(muted("stale — last poll failed"));
        }
    }

    /** Re-renders the header summary with the age of the last numbers; call on the FX thread. */
    public void refreshAge(Instant now) {
        summary.set(withAge(baseSummary, fetchedAt == null ? Duration.ZERO : Duration.between(fetchedAt, now)));
    }

    /** The summary plus "· 12 min ago" when the numbers are older than 5 minutes; empty stays empty. */
    public static String withAge(String summary, Duration age) {
        if (summary == null || summary.isBlank() || age.compareTo(AGE_SHOWN_AFTER) <= 0) {
            return summary == null ? "" : summary;
        }
        long minutes = age.toMinutes();
        var text = minutes >= 120 ? age.toHours() + " h ago" : minutes + " min ago";
        return summary + " · " + text;
    }

    /** One-line summary for a collapsed header, e.g. "5h 2% · Week 43%"; empty without data. */
    public ReadOnlyStringProperty summaryProperty() {
        return summary;
    }

    /** The first two rows as "5h 2% · Week 43%"; empty for no quota or an error. */
    public static String summary(QuotaClient.Quota quota) {
        if (quota == null || quota.error() != null) {
            return "";
        }
        return quota.rows().stream().limit(2)
                .map(row -> row.label() + " " + Math.round(row.percent()) + "%")
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    private static Label muted(String text) {
        var label = new Label(text);
        label.getStyleClass().add("quota-muted");
        label.setWrapText(true);
        return label;
    }

    /** "5h  1% · resets 10:59" when the reset is today, else with the day: "Week  43% · resets Fri 16:59". */
    static String rowLabel(QuotaClient.Row row, ZoneId zone, Instant now) {
        var text = row.label() + "  " + Math.round(row.percent()) + "%";
        if (row.resetsAt() == null) {
            return text;
        }
        var resets = row.resetsAt().atZone(zone);
        boolean today = resets.toLocalDate().equals(now.atZone(zone).toLocalDate());
        return text + " · resets " + (today ? TIME : DAY_TIME).format(resets);
    }

    /** Style class for a percent: quota-crit from 90, quota-warn from 70, else none. */
    static String colorClass(double percent) {
        return percent >= 90 ? "quota-crit" : percent >= 70 ? "quota-warn" : "";
    }

    /** 1234 -> "1.2k", 1_200_000 -> "1.2M". */
    static String compact(long n) {
        if (n >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        }
        if (n >= 1_000) {
            return n >= 100_000 ? (n / 1_000) + "k" : String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        }
        return Long.toString(n);
    }

    /** "ctx 121k / 200k · 60%". */
    public static String contextLabel(long tokens, long window) {
        long percent = window <= 0 ? 0 : (long) Math.floor(100.0 * tokens / window);
        return "ctx " + shortK(tokens) + " / " + shortK(window) + " · " + percent + "%";
    }

    private static String shortK(long n) {
        if (n >= 1_000_000 && n % 1_000_000 == 0) {
            return (n / 1_000_000) + "M";
        }
        return n >= 1_000 ? Math.round(n / 1_000.0) + "k" : Long.toString(n);
    }
}
