package dev.kamil.cterm;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Connects one JediTermFX widget to one pty4j process. */
final class PtyTtyConnector implements TtyConnector {

    private final PtyProcess process;
    private final SynchronizedOutputReader reader;
    private final OutputStream output;
    private final String name;

    PtyTtyConnector(PtyProcess process, String name) {
        this.process = process;
        this.reader = new SynchronizedOutputReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.output = process.getOutputStream();
        this.name = name;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        return reader.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        output.write(bytes);
        output.flush();
    }

    @Override
    public void write(String text) throws IOException {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return process.isAlive();
    }

    @Override
    public void resize(TermSize size) {
        if (process.isAlive()) {
            process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() {
        process.destroy();
    }
}
