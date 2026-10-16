package dev.kamil.fabric.session;

import dev.kamil.fabric.terminal.TerminalTab;
import dev.kamil.fabric.terminal.TerminalTab.Activity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/** Right panel: one row per open Claude tab with its state, sorted by what needs attention first. */
public final class AgentsPanel extends BorderPane {

    /** What one row shows; {@code activity} is null before the first hook event. */
    public record Agent(String name, String project, String account, String context, Activity activity,
            boolean exited, boolean unseen) {
    }

    /** What the status line shows; any part may be null or blank when unknown. */
    public record Line(boolean claude, String state, String context, String branch, String account) {
    }

    /** Everything a row displays, plus its tab and selection; equal inputs mean no repaint is needed. */
    private record RowInput(Agent agent, TerminalTab tab, boolean selected) {
    }

    private List<RowInput> lastInput;
    private final TabPane tabs;
    private final Function<String, String> contexts;
    private final VBox list = new VBox(4);
    private final Label header = new Label();

    public AgentsPanel(TabPane tabs, Function<String, String> contexts) {
        this.tabs = tabs;
        this.contexts = contexts;
        getStyleClass().add("agents-panel");
        setPrefWidth(260);
        setMinWidth(260);
        setMaxWidth(260);
        header.getStyleClass().add("agents-header");
        header.setPadding(new Insets(12, 12, 8, 12));
        list.getStyleClass().add("agents-list");
        list.setPadding(new Insets(4, 8, 8, 8));
        var scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        setTop(header);
        setCenter(scroll);
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) c -> refresh());
        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> refresh());
        refresh();
    }

    /** Redraws the rows (FX thread). */
    public void refresh() {
        var rows = new ArrayList<TerminalTab>();
        for (var t : tabs.getTabs()) {
            if (t instanceof TerminalTab tab && isClaude(tab)) {
                rows.add(tab);
            }
        }
        var agents = new ArrayList<Agent>();
        var byAgent = new java.util.IdentityHashMap<Agent, TerminalTab>();
        for (var tab : rows) {
            var agent = toAgent(tab, contexts.apply(tab.sessionId()));
            agents.add(agent);
            byAgent.put(agent, tab);
        }
        var input = new ArrayList<RowInput>();
        for (var agent : agents) {
            var tab = byAgent.get(agent);
            input.add(new RowInput(agent, tab, tab.isSelected()));
        }
        if (input.equals(lastInput)) {
            return;
        }
        lastInput = input;
        header.setText(header(agents));
        list.getChildren().clear();
        for (var agent : sorted(agents)) {
            list.getChildren().add(row(agent, byAgent.get(agent)));
        }
    }

    private static boolean isClaude(TerminalTab tab) {
        return "claude".equals(tab.tool().name());
    }

    static Agent toAgent(TerminalTab tab, String context) {
        var dir = tab.directory().getFileName();
        var name = tab.name().isBlank() ? (dir == null ? "/" : dir.toString()) : tab.name();
        return new Agent(name, dir == null ? "/" : dir.toString(), accountLabel(tab.account().name()), context,
                tab.activity(), tab.cachedStatus() == TerminalTab.Status.EXITED, tab.unseen());
    }

    private Region row(Agent agent, TerminalTab tab) {
        var dot = new Circle(4, agent.exited()
                ? javafx.scene.paint.Color.web("#d96b75") : SessionSidebar.runningColor(agent.activity()));
        var name = new Label(agent.name());
        name.getStyleClass().add("agent-name");
        if (agent.unseen()) {
            name.getStyleClass().add("agent-unseen");
        }
        var top = new HBox(8, dot, name);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        if (agent.unseen()) {
            var unread = new Circle(3, javafx.scene.paint.Color.web("#ff7a3d"));
            var spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            top.getChildren().addAll(spacer, unread);
        }
        var box = new VBox(2, top);
        var detail = detail(agent);
        if (!detail.isBlank()) {
            var label = new Label(detail);
            label.getStyleClass().add("agent-detail");
            box.getChildren().add(label);
        }
        if (agent.context() != null && !agent.context().isBlank()) {
            var ctx = new Label(agent.context());
            ctx.getStyleClass().add("agent-detail");
            box.getChildren().add(ctx);
        }
        box.getStyleClass().add("agent-row");
        if (tab.isSelected()) {
            box.getStyleClass().add("agent-row-selected");
        }
        box.setOnMouseClicked(e -> {
            tabs.getSelectionModel().select(tab);
            tab.focus();
        });
        return box;
    }

    // ---- pure logic ----

    /** "2 working · 1 needs input", zero parts hidden; "No agents" when there are no Claude tabs. */
    public static String header(List<Agent> agents) {
        if (agents.isEmpty()) {
            return "No agents";
        }
        long working = agents.stream().filter(a -> !a.exited() && a.activity() == Activity.WORKING).count();
        long waiting = agents.stream().filter(a -> !a.exited() && a.activity() == Activity.WAITING).count();
        var parts = new ArrayList<String>();
        if (working > 0) {
            parts.add(working + " working");
        }
        if (waiting > 0) {
            parts.add(waiting + " needs input");
        }
        return parts.isEmpty() ? agents.size() + (agents.size() == 1 ? " agent" : " agents") : String.join(" · ", parts);
    }

    /** WAITING, ERROR, WORKING, then the rest; ties by name. */
    public static List<Agent> sorted(List<Agent> agents) {
        return agents.stream()
                .sorted(Comparator.comparingInt(AgentsPanel::rank)
                        .thenComparing(Agent::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static int rank(Agent a) {
        if (a.exited()) {
            return 4;
        }
        if (a.activity() == null) {
            return 3;
        }
        return switch (a.activity()) {
            case WAITING -> 0;
            case ERROR -> 1;
            case WORKING -> 2;
            case DONE -> 3;
        };
    }

    /** Account label (or name) for display; blank for the default personal account without a label. */
    static String accountLabel(String account) {
        if (account == null) {
            return "";
        }
        var label = dev.kamil.fabric.settings.AppSettings.shared().getString(Account.labelKey(account), "").trim();
        if (!label.isEmpty()) {
            return label;
        }
        return Account.PERSONAL.equals(account) ? "" : account;
    }

    /** Project folder and account (when not personal), e.g. "my-app · work". */
    static String detail(Agent a) {
        var parts = new ArrayList<String>();
        if (a.project() != null && !a.project().isBlank() && !a.project().equals(a.name())) {
            parts.add(a.project());
        }
        if (a.account() != null && !a.account().isBlank()) {
            parts.add(a.account());
        }
        return String.join(" · ", parts);
    }

    /** "working · ctx 34% · ⎇ main · work" for Claude; "shell · ⎇ main" otherwise. Unknown parts are left out. */
    public static String statusLine(Line line) {
        var parts = new ArrayList<String>();
        parts.add(line.claude() ? orEmpty(line.state()) : "shell");
        if (line.claude()) {
            parts.add(percent(line.context()));
        }
        if (line.branch() != null && !line.branch().isBlank()) {
            parts.add("⎇ " + line.branch());
        }
        if (line.claude()) {
            parts.add(orEmpty(line.account()));
        }
        return parts.stream().filter(s -> !s.isBlank()).reduce((a, b) -> a + " · " + b).orElse("");
    }

    /** "ctx 34k / 200k · 17%" becomes "ctx 17%"; blank when there is no context text. */
    static String percent(String context) {
        if (context == null || context.isBlank()) {
            return "";
        }
        int at = context.lastIndexOf(" · ");
        return at < 0 ? context : "ctx " + context.substring(at + 3);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** State word for the status line. */
    public static String stateName(Activity activity, boolean exited) {
        if (exited) {
            return "exited";
        }
        if (activity == null) {
            return "idle";
        }
        return switch (activity) {
            case WORKING -> "working";
            case WAITING -> "needs input";
            case DONE -> "done";
            case ERROR -> "error";
        };
    }

    /** Status line for one tab given its context text. */
    public static String statusLine(TerminalTab tab, String context) {
        boolean claude = isClaude(tab);
        return statusLine(new Line(claude,
                stateName(tab.activity(), tab.cachedStatus() == TerminalTab.Status.EXITED), context,
                tab.cachedBranch(), accountLabel(tab.account().name())));
    }
}
