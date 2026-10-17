package dev.kamil.fabric.english;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/** Microphone capture to a WAV file and WAV playback; no UI. */
final class Recorder {

    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private static final int MAX_SECONDS = 20;

    /** Tests set this: no microphone, no sound; recording yields a short silent WAV and calls are logged. */
    static volatile boolean fake;
    /** What the fake recorder was asked to do: {@code play:<file name>}. */
    static final java.util.List<String> LOG = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private boolean fakeRecording;
    private TargetDataLine line;
    private ByteArrayOutputStream captured;
    private Thread thread;
    private volatile Clip clip;

    synchronized boolean recording() {
        return line != null || fakeRecording;
    }

    synchronized void start() throws IOException {
        if (line != null || fakeRecording) {
            return;
        }
        if (fake) {
            fakeRecording = true;
            return;
        }
        try {
            var info = new DataLine.Info(TargetDataLine.class, FORMAT);
            var l = (TargetDataLine) AudioSystem.getLine(info);
            l.open(FORMAT);
            l.start();
            line = l;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            throw new IOException("Cannot open the microphone: " + e.getMessage(), e);
        }
        var data = new ByteArrayOutputStream();
        captured = data;
        var l = line;
        thread = new Thread(() -> capture(l, data), "recorder");
        thread.setDaemon(true);
        thread.start();
    }

    private void capture(TargetDataLine l, ByteArrayOutputStream data) {
        var buffer = new byte[4096];
        long limit = (long) MAX_SECONDS * (long) FORMAT.getFrameRate() * FORMAT.getFrameSize();
        while (l.isOpen() && data.size() < limit) {
            int n = l.read(buffer, 0, buffer.length);
            if (n <= 0) {
                break;
            }
            synchronized (data) {
                data.write(buffer, 0, n);
            }
        }
    }

    /** Stops recording and writes the WAV to {@code target}; returns it, or null when nothing was captured. */
    Path stop(Path target) throws IOException {
        if (fake) {
            synchronized (this) {
                if (!fakeRecording) {
                    return null;
                }
                fakeRecording = false;
            }
            var bytes = new byte[3200];
            try (var in = new AudioInputStream(new ByteArrayInputStream(bytes), FORMAT, bytes.length / FORMAT.getFrameSize())) {
                AudioSystem.write(in, AudioFileFormat.Type.WAVE, target.toFile());
            }
            return target;
        }
        TargetDataLine l;
        ByteArrayOutputStream data;
        Thread t;
        synchronized (this) {
            l = line;
            data = captured;
            t = thread;
            line = null;
            captured = null;
            thread = null;
        }
        if (l == null) {
            return null;
        }
        l.stop();
        l.close();
        try {
            t.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes;
        synchronized (data) {
            bytes = data.toByteArray();
        }
        if (bytes.length == 0) {
            return null;
        }
        try (var in = new AudioInputStream(new ByteArrayInputStream(bytes), FORMAT, bytes.length / FORMAT.getFrameSize())) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, target.toFile());
        }
        return target;
    }

    /** Plays the WAV on a background thread; {@code onDone} (may be null) runs when it finishes. */
    void play(Path wav, Runnable onDone) {
        stopPlayback();
        if (fake) {
            LOG.add("play:" + wav.getFileName());
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        var thread = new Thread(() -> {
            try (var in = AudioSystem.getAudioInputStream(wav.toFile())) {
                var c = AudioSystem.getClip();
                var finished = new java.util.concurrent.CountDownLatch(1);
                c.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP || ev.getType() == LineEvent.Type.CLOSE) {
                        finished.countDown();
                    }
                });
                c.open(in);
                clip = c;
                c.start();
                finished.await();
                c.close();
            } catch (Exception e) {
                // playback is best effort
            } finally {
                clip = null;
                if (onDone != null) {
                    onDone.run();
                }
            }
        }, "recorder-play");
        thread.setDaemon(true);
        thread.start();
    }

    void stopPlayback() {
        var c = clip;
        if (c != null) {
            c.stop();
        }
    }
}
