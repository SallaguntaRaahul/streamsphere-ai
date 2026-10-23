package ai.streamsphere.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a command as an argv array (never through a shell) with a hard timeout. */
public final class Proc {
    public record Result(int exitCode, String stdout, String stderrTail) {}

    public static class ProcFailure extends RuntimeException {
        public final boolean retryable;
        ProcFailure(String msg, boolean retryable) { super(msg); this.retryable = retryable; }
    }

    private Proc() {}

    public static Result run(List<String> argv, Path workDir, Duration timeout) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).directory(workDir.toFile());
            pb.environment().clear();
            pb.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin");
            Process p = pb.start();
            var out = new StringBuilder();
            Deque<String> err = new ArrayDeque<>();
            Thread tOut = Thread.ofVirtual().start(() -> {
                try (var r = p.inputReader(StandardCharsets.UTF_8)) { r.lines().forEach(l -> { if (out.length() < 4_000_000) out.append(l).append('\n'); }); } catch (IOException ignored) { }
            });
            Thread tErr = Thread.ofVirtual().start(() -> {
                try (var r = p.errorReader(StandardCharsets.UTF_8)) {
                    r.lines().forEach(l -> { synchronized (err) { err.addLast(l); if (err.size() > 60) err.removeFirst(); } });
                } catch (IOException ignored) { }
            });
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new ProcFailure(argv.getFirst() + " timed out after " + timeout, true);
            }
            tOut.join(2000);
            tErr.join(2000);
            synchronized (err) { return new Result(p.exitValue(), out.toString(), String.join("\n", err)); }
        } catch (IOException e) {
            throw new ProcFailure("cannot start " + argv.getFirst() + ": " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcFailure("interrupted", true);
        }
    }
}
