package dev.kamil.fabric.claude;

import dev.kamil.fabric.settings.AppSettings;
import dev.kamil.fabric.terminal.TerminalTab.Activity;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * In-app toast and sound when a session needs the user while they look elsewhere. The decision and the
 * debounce live here; the toast goes to an injected sink (thread-safe) and the sound command runs through an injected runner, off the
 * calling thread. Nothing is ever sent to the desktop.
 */
public final class Notifier {

    /** Setting key for the in-app toast (the stored key predates the in-app toasts). */
    public static final String DESKTOP = "notify.desktop";
    public static final String SOUND = "notify.sound";
    public static final String NEEDS_INPUT = "notify.needsInput";
    public static final String FINISHED = "notify.finished";

    private final AppSettings settings;
    private final Consumer<List<String>> runner;
    private final Executor executor;
    private final BiConsumer<String, String> toast;
    private final Map<String, Activity> notified = new HashMap<>();

    public Notifier(Consumer<List<String>> runner, Executor executor, BiConsumer<String, String> toast) {
        this(runner, executor, toast, AppSettings.shared());
    }

    public Notifier(Consumer<List<String>> runner, Executor executor, BiConsumer<String, String> toast,
            AppSettings settings) {
        this.settings = settings;
        this.toast = toast;
        this.runner = runner;
        this.executor = executor;
    }

    /** Shows toasts through the sink; runs the sound command on a daemon thread, a missing binary logged once. */
    public static Notifier system(BiConsumer<String, String> toast) {
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
                    System.err.println("The notification sound needs " + command.get(0) + ": " + e.getMessage());
                }
            }
        }, executor, toast);
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
        var showToast = settings.getBoolean(DESKTOP, true);
        var sound = settings.getBoolean(SOUND, true);
        if (!settings.getBoolean(kind, true) || !(showToast || sound)) {
            return false;
        }
        notified.put(sessionId, activity);
        var soundName = activity == Activity.WAITING ? "message" : "complete";
        executor.execute(() -> {
            if (showToast) {
                toast.accept(name, message(activity));
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
