package dev.kamil.fabric.claude.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageViewTest {

    @TempDir
    Path dir;

    @Test
    void contextLabelFormat() {
        assertEquals("ctx 121k / 200k · 60%", UsageView.contextLabel(121_000, 200_000));
        assertEquals("ctx 0 / 1M · 0%", UsageView.contextLabel(0, 1_000_000));
    }

    @Test
    void summaryJoinsTheFirstTwoRows() {
        var quota = new QuotaClient.Quota(java.util.List.of(
                new QuotaClient.Row("5h", 2.2, null, ""),
                new QuotaClient.Row("Week", 43.4, null, ""),
                new QuotaClient.Row("Week · Fable", 19, null, "")), null, false);
        assertEquals("5h 2% · Week 43%", UsageView.summary(quota));
        assertEquals("", UsageView.summary(null));
        assertEquals("", UsageView.summary(new QuotaClient.Quota(java.util.List.of(), "boom", false)));
    }

    @Test
    void compactNumbers() {
        assertEquals("1.2M", UsageView.compact(1_200_000));
        assertEquals("640k", UsageView.compact(640_000));
        assertEquals("1.5k", UsageView.compact(1_500));
        assertEquals("42", UsageView.compact(42));
    }

    @Test
    void projectDirNameReplacesSlashAndDot() {
        assertEquals("-home-kamil--claude", SessionTranscripts.projectDirName("/home/kamil/.claude"));
    }

    @Test
    void transcriptFallsBackToNewestJsonl() throws Exception {
        var project = Files.createDirectories(dir.resolve("projects/-home-x"));
        Files.writeString(project.resolve("old.jsonl"), "");
        Files.setLastModifiedTime(project.resolve("old.jsonl"), java.nio.file.attribute.FileTime.fromMillis(1000));
        Files.writeString(project.resolve("new.jsonl"), "");
        var found = SessionTranscripts.find(dir.resolve("events"), "s1", Path.of("/home/x"), dir.resolve("projects"));
        assertEquals(project.resolve("new.jsonl"), found.orElseThrow());
    }

    @Test
    void transcriptPrefersHookReportedPath() throws Exception {
        var events = Files.createDirectories(dir.resolve("events"));
        var file = Files.writeString(dir.resolve("t.jsonl"), "");
        Files.writeString(events.resolve("s1.transcript"), file + "\n");
        assertEquals(file, SessionTranscripts.find(events, "s1", Path.of("/nowhere"), dir.resolve("projects")).orElseThrow());
    }

    private static final String SAMPLE = """
        {"five_hour":{"utilization":1.0,"resets_at":"2026-10-15T08:59:59.976817+00:00"},
         "seven_day":{"utilization":43.0,"resets_at":"2026-10-16T14:59:59.976836+00:00"},
         "seven_day_opus":null,"unknown":{"x":1},
         "limits":[{"kind":"session","percent":1,"severity":"normal","resets_at":"2026-10-15T08:59:59.976817+00:00","scope":null},
           {"kind":"weekly_all","percent":43,"severity":"normal","resets_at":"2026-10-16T14:59:59.976836+00:00","scope":null},
           {"kind":"weekly_scoped","percent":19,"severity":"normal","resets_at":"2026-10-16T14:59:59+00:00",
            "scope":{"model":{"id":null,"display_name":"Fable"}}}]}""";

    @Test
    void parsesLimits() throws Exception {
        var rows = QuotaClient.parse(SAMPLE);
        assertEquals(3, rows.size());
        assertEquals("5h", rows.get(0).label());
        assertEquals(1, rows.get(0).percent());
        assertEquals("Week", rows.get(1).label());
        assertEquals(43, rows.get(1).percent());
        assertEquals("Week · Fable", rows.get(2).label());
        assertEquals(19, rows.get(2).percent());
        assertEquals("normal", rows.get(0).severity());
        assertEquals(Instant.parse("2026-10-16T14:59:59.976836Z"), rows.get(1).resetsAt());
    }

    @Test
    void fallsBackWithoutLimits() throws Exception {
        var rows = QuotaClient.parse("""
            {"five_hour":{"utilization":5.0,"resets_at":"2026-10-15T08:59:59+00:00"},
             "seven_day":{"utilization":50.0,"resets_at":null},"seven_day_opus":null}""");
        assertEquals(2, rows.size());
        assertEquals("5h", rows.get(0).label());
        assertEquals("Week", rows.get(1).label());
        assertEquals(50, rows.get(1).percent());
    }

    @Test
    void rowLabelFormat() {
        var now = Instant.parse("2026-10-15T00:00:00Z");
        var soon = new QuotaClient.Row("5h", 1, Instant.parse("2026-10-15T08:59:59Z"), "normal");
        var later = new QuotaClient.Row("Week", 43, Instant.parse("2026-10-16T14:59:59Z"), "normal");
        assertEquals("5h  1% · resets 08:59", UsageView.rowLabel(soon, ZoneOffset.UTC, now));
        assertEquals("Week  43% · resets Fri 14:59", UsageView.rowLabel(later, ZoneOffset.UTC, now));
        var evening = Instant.parse("2026-10-15T18:08:00Z");
        var tomorrow = new QuotaClient.Row("Week", 47, Instant.parse("2026-10-16T17:00:00Z"), "normal");
        assertEquals("Week  47% · resets Fri 17:00", UsageView.rowLabel(tomorrow, ZoneOffset.UTC, evening));
    }

    @Test
    void colorThresholds() {
        assertEquals("", UsageView.colorClass(69.9));
        assertEquals("quota-warn", UsageView.colorClass(70));
        assertEquals("quota-warn", UsageView.colorClass(89));
        assertEquals("quota-crit", UsageView.colorClass(90));
    }

    @Test
    void ageShownOnlyWhenOlderThanFiveMinutes() {
        var base = "5h 2% · Week 43%";
        assertEquals(base, UsageView.withAge(base, java.time.Duration.ofMinutes(5)));
        assertEquals(base + " · 12 min ago", UsageView.withAge(base, java.time.Duration.ofMinutes(12)));
        assertEquals(base + " · 3 h ago", UsageView.withAge(base, java.time.Duration.ofHours(3)));
        assertEquals("", UsageView.withAge("", java.time.Duration.ofHours(3)));
    }
}
