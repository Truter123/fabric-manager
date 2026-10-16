package dev.kamil.fabric.claude;

import dev.kamil.fabric.settings.AppSettings;
import dev.kamil.fabric.terminal.TerminalTab.Activity;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Desktop notification and sound when a session needs the user while they look elsewhere. The decision and the
 * debounce live here; the commands run through an injected runner, off the calling thread.
 */
public final class Notifier {

    public static final String DESKTOP = "notify.desktop";
    public static final String SOUND = "notify.sound";
    public static final String NEEDS_INPUT = "notify.needsInput";
    public static final String FINISHED = "notify.finished";

    private final AppSettings settings;
    private final Consumer<List<String>> runner;
    private final Executor executor;
    private final Map<String, Activity> notified = new HashMap<>();

    public Notifier(Consumer<List<String>> runner, Executor executor) {
        this(runner, executor, AppSettings.shared());
    }

    public Notifier(Consumer<List<String>> runner, Executor executor, AppSettings settings) {
        this.settings = settings;
        this.runner = runner;
        this.executor = executor;
    }

    /** Runs the commands with ProcessBuilder on a daemon thread; a missing binary is logged once. */
    public static Notifier system() {
        var executor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "notifier");
            thread.setDaemon(true);
            return thread;
        });
        var logged = new java.util.concurrent.atomic.AtomicBoolean();
        return new Notifier(command -> {
            try {
                new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) {
                if (logged.compareAndSet(false, true)) {
                    System.err.println("Desktop notifications need " + command.get(0) + ": " + e.getMessage());
                }
            }
        }, executor);
    }

    /** True for a state that needs the user while the session is not in front of them. */
    static boolean shouldNotify(Activity activity, boolean windowFocused, boolean tabSelected) {
        return activity != null && activity != Activity.WORKING && !(windowFocused && tabSelected);
    }

    static String message(Activity activity) {
        return switch (activity) {
            case WAITING -> "Claude is waiting for you";
            case DONE -> "Claude finished";
            case ERROR -> "error";
            case WORKING -> null;
        };
    }

    /** Notifies when due and not already done for this state; returns whether it did. */
    public synchronized boolean onState(String sessionId, String name, Activity activity, boolean windowFocused,
            boolean tabSelected) {
        if (notified.get(sessionId) != activity) {
            notified.remove(sessionId);
        }
        if (!shouldNotify(activity, windowFocused, tabSelected) || notified.containsKey(sessionId)) {
            return false;
        }
        var kind = activity == Activity.DONE ? FINISHED : NEEDS_INPUT;
        var desktop = settings.getBoolean(DESKTOP, true);
        var sound = settings.getBoolean(SOUND, true);
        if (!settings.getBoolean(kind, true) || !(desktop || sound)) {
            return false;
        }
        notified.put(sessionId, activity);
        var soundName = activity == Activity.WAITING ? "message" : "complete";
        executor.execute(() -> {
            if (desktop) {
                runner.accept(List.of("notify-send", "-a", "Fabric Manager", name, message(activity)));
            }
            if (sound) {
                runner.accept(List.of("canberra-gtk-play", "-i", soundName));
            }
        });
        return true;
    }

    /** Forgets a closed session. */
    public synchronized void forget(String sessionId) {
        notified.remove(sessionId);
    }
}
