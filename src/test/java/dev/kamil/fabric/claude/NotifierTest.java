package dev.kamil.fabric.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.terminal.TerminalTab.Activity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotifierTest {

    private final List<List<String>> commands = new ArrayList<>();
    private final List<List<String>> toasts = new ArrayList<>();
    private final Notifier notifier = new Notifier(commands::add, Runnable::run, (t, m) -> toasts.add(List.of(t, m)));

    @Test
    void notifiesWhenWindowNotFocused() {
        assertTrue(withToast().onState("s", "app", Activity.DONE, false, true));
        assertEquals(List.of(List.of("app", "Claude finished")), toasts);
        assertEquals(List.of(List.of("canberra-gtk-play", "-i", "complete")), commands);
    }

    @Test
    void notifiesWhenTabNotSelected() {
        assertTrue(withToast().onState("s", "app", Activity.WAITING, true, false));
        assertEquals("Claude is waiting for you", toasts.get(0).get(1));
        assertEquals(List.of("canberra-gtk-play", "-i", "message"), commands.get(0));
    }

    @Test
    void errorUsesErrorText() {
        assertTrue(withToast().onState("s", "app", Activity.ERROR, false, false));
        assertEquals("error", toasts.get(0).get(1));
    }

    @Test
    void silentWhenUserIsLookingOrWorking() {
        assertFalse(notifier.onState("s", "app", Activity.DONE, true, true));
        assertFalse(notifier.onState("s", "app", Activity.WORKING, false, false));
        assertTrue(commands.isEmpty());
        assertTrue(toasts.isEmpty());
    }

    @Test
    void sameStateNotifiesOnceUntilItChanges() {
        assertTrue(notifier.onState("s", "app", Activity.DONE, false, false));
        assertFalse(notifier.onState("s", "app", Activity.DONE, false, false));
        assertTrue(notifier.onState("t", "other", Activity.DONE, false, false));
        assertTrue(notifier.onState("s", "app", Activity.WAITING, false, false));
        assertFalse(notifier.onState("s", "app", Activity.WORKING, false, false));
        assertTrue(notifier.onState("s", "app", Activity.WAITING, false, false));
    }

    private Notifier with(dev.kamil.fabric.settings.AppSettings settings) {
        return new Notifier(commands::add, Runnable::run, (t, m) -> toasts.add(List.of(t, m)), settings);
    }

    private Notifier withToast() {
        var settings = dev.kamil.fabric.settings.AppSettings.inMemory();
        settings.setBoolean(Notifier.DESKTOP, true);
        return with(settings);
    }

    @Test
    void defaultsShowNoToastButStillPlayTheSound() {
        assertTrue(with(dev.kamil.fabric.settings.AppSettings.inMemory())
                .onState("s", "app", Activity.DONE, false, false));
        assertTrue(toasts.isEmpty());
        assertEquals(List.of(List.of("canberra-gtk-play", "-i", "complete")), commands);
    }

    @Test
    void soundOffShowsOnlyTheToast() {
        var settings = dev.kamil.fabric.settings.AppSettings.inMemory();
        settings.setBoolean(Notifier.DESKTOP, true);
        settings.setBoolean("notify.sound", false);
        assertTrue(with(settings).onState("s", "app", Activity.DONE, false, false));
        assertEquals(1, toasts.size());
        assertTrue(commands.isEmpty());
    }

    @Test
    void toastOffPlaysOnlyTheSound() {
        var settings = dev.kamil.fabric.settings.AppSettings.inMemory();
        settings.setBoolean("notify.desktop", false);
        assertTrue(with(settings).onState("s", "app", Activity.DONE, false, false));
        assertEquals(1, commands.size());
        assertEquals("canberra-gtk-play", commands.get(0).get(0));
        assertTrue(toasts.isEmpty());
    }

    @Test
    void bothOffIsSilent() {
        var settings = dev.kamil.fabric.settings.AppSettings.inMemory();
        settings.setBoolean("notify.desktop", false);
        settings.setBoolean("notify.sound", false);
        assertFalse(with(settings).onState("s", "app", Activity.DONE, false, false));
        assertTrue(commands.isEmpty());
        assertTrue(toasts.isEmpty());
    }

    @Test
    void finishedAndNeedsInputCanBeSwitchedOffSeparately() {
        var settings = dev.kamil.fabric.settings.AppSettings.inMemory();
        settings.setBoolean("notify.finished", false);
        var notifier = with(settings);
        assertFalse(notifier.onState("s", "app", Activity.DONE, false, false));
        assertTrue(notifier.onState("s", "app", Activity.WAITING, false, false));
        settings.setBoolean("notify.needsInput", false);
        assertFalse(notifier.onState("t", "app", Activity.WAITING, false, false));
    }
}
