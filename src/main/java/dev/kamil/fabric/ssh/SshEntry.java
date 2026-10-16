package dev.kamil.fabric.ssh;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** One row of the picker: a config host (app == null) or an app host. */
public record SshEntry(String alias, String detail, AppHost app, boolean hidden) {

    public boolean fromApp() {
        return app != null;
    }

    /** App hosts first (by name), then the visible config hosts in file order; hidden config hosts only when asked. */
    public static List<SshEntry> merge(List<SshHost> config, List<AppHost> app, Set<String> hidden, boolean showHidden) {
        var result = new ArrayList<SshEntry>();
        app.stream().sorted(Comparator.comparing(AppHost::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(h -> result.add(new SshEntry(h.name(), h.detail(), h, false)));
        for (var host : config) {
            boolean isHidden = hidden.contains(host.alias());
            if (!isHidden || showHidden) {
                result.add(new SshEntry(host.alias(), host.detail(), null, isHidden));
            }
        }
        return result;
    }
}
