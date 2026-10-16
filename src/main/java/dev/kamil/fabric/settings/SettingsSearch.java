package dev.kamil.fabric.settings;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The settings search: a field matches when every word of the query is in its label or help text. */
public final class SettingsSearch {

    /** One searchable field of a group. */
    public record Entry(String group, String label, String help) {
    }

    private SettingsSearch() {
    }

    public static boolean matches(Entry entry, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        var text = (entry.label() + " " + entry.help()).toLowerCase(Locale.ROOT);
        for (var word : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Names of the groups that have at least one matching field, in order of first appearance. */
    public static Set<String> groupsMatching(List<Entry> entries, String query) {
        var groups = new LinkedHashSet<String>();
        for (var entry : entries) {
            if (matches(entry, query)) {
                groups.add(entry.group());
            }
        }
        return groups;
    }
}
