package dev.kamil.fabric.git;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.terminal.TerminalTab;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/** The checkout flow shared by the branch popup and the history window: warnings, blocked-by-changes choice, errors. */
public final class BranchCheckout {

    private BranchCheckout() {
    }

    /**
     * Switches to {@code branch}. With a {@code tab} whose tool is running the user is warned first; on success
     * {@code onDone} runs on the FX thread and the tab (if any) is focused.
     */
    public static void run(Window owner, GitRepo repo, GitRepo.Branch branch, TerminalTab tab, Runnable onDone) {
        if (tab != null && tab.status() == TerminalTab.Status.TOOL_RUNNING) {
            var name = tab.tool().name();
            if (!Dialogs.confirm(owner, "Switch branch while " + name + " is running?",
                    name + " in this tab will see the files change under it.", "Switch")) {
                return;
            }
        }
        runGit(owner, repo, branch, tab, onDone, () -> repo.checkout(branch));
    }

    /** Runs {@code action} off the FX thread and handles its result back on it. */
    private static void runGit(Window window, GitRepo repo, GitRepo.Branch branch, TerminalTab tab, Runnable onDone,
                               Supplier<GitRepo.CheckoutResult> action) {
        var thread = new Thread(() -> {
            var result = action.get();
            Platform.runLater(() -> handle(window, repo, branch, tab, onDone, result));
        }, "branch-checkout");
        thread.setDaemon(true);
        thread.start();
    }

    private static void handle(Window window, GitRepo repo, GitRepo.Branch branch, TerminalTab tab, Runnable onDone,
                               GitRepo.CheckoutResult result) {
        switch (result) {
            case GitRepo.Switched s -> {
                onDone.run();
                if (tab != null) {
                    tab.focus();
                }
            }
            case GitRepo.BlockedByChanges blocked -> resolveBlocked(window, repo, branch, tab, onDone, blocked);
            case GitRepo.Failed failed ->
                    Dialogs.error(window, "Cannot switch to " + branch.name(), failed.message());
        }
    }

    private static void resolveBlocked(Window window, GitRepo repo, GitRepo.Branch branch, TerminalTab tab,
                                       Runnable onDone, GitRepo.BlockedByChanges blocked) {
        var files = blocked.files();
        var text = new StringBuilder();
        files.stream().limit(10).forEach(f -> text.append(f).append('\n'));
        if (files.size() > 10) {
            text.append("and ").append(files.size() - 10).append(" more");
        }
        var stash = new ButtonType("Stash, switch, restore", ButtonBar.ButtonData.OK_DONE);
        var discard = new ButtonType("Discard changes and switch", ButtonBar.ButtonData.OTHER);
        var alert = new Alert(Alert.AlertType.NONE, text.toString().stripTrailing(), stash, discard, ButtonType.CANCEL);
        alert.setTitle("FabricManager");
        alert.setHeaderText("Uncommitted changes block the switch");
        Dialogs.style(alert.getDialogPane());
        alert.initOwner(window);
        var choice = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == stash) {
            runGit(window, repo, branch, tab, onDone, () -> repo.stashAndCheckout(branch));
        } else if (choice == discard && Dialogs.confirm(window, "Discard local changes?",
                "These changes will be lost. This cannot be undone.", "Discard")) {
            runGit(window, repo, branch, tab, onDone, () -> repo.forceCheckout(branch));
        }
    }
}
