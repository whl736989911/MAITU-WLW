package com.fireplatform.video;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Optional operator-installed ffmpeg frame extractor. It never receives a URL, credential or file path:
 * media bytes are piped on stdin, output on stdout, child protocols are restricted to pipe, the environment is
 * cleared, and the process is bounded and killed on timeout. Missing binary is an explicit NOT_CONFIGURED.
 */
@Component
public class FfmpegExtractor {
    private static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;

    private final Path binary;
    private final int timeoutSeconds;

    public FfmpegExtractor(Environment environment) {
        String configured = environment.getProperty("app.video.ffmpeg-binary");
        if (configured == null || configured.isBlank()) configured = environment.getProperty("APP_VIDEO_FFMPEG_BINARY");
        this.binary = configured == null || configured.isBlank() ? null : Path.of(configured.trim());
        String seconds = environment.getProperty("app.video.ffmpeg-timeout-seconds");
        if (seconds == null || seconds.isBlank()) seconds = environment.getProperty("APP_VIDEO_FFMPEG_TIMEOUT_SECONDS");
        int parsed = 20;
        if (seconds != null && !seconds.isBlank()) {
            try { parsed = Integer.parseInt(seconds.trim()); } catch (NumberFormatException ignored) { parsed = 20; }
        }
        this.timeoutSeconds = Math.max(3, Math.min(parsed, 120));
    }

    public boolean available() {
        return binary != null && binary.isAbsolute() && Files.isRegularFile(binary) && Files.isExecutable(binary);
    }

    public String configuredPath() { return binary == null ? null : binary.toString(); }

    public byte[] extract(byte[] input, String format) {
        if (!available()) throw new IllegalStateException("FFMPEG_NOT_CONFIGURED");
        if (input == null || input.length == 0) throw new IllegalStateException("FFMPEG_EMPTY_INPUT");
        if (!"mp4".equals(format) && !"mpegts".equals(format))
            throw new IllegalArgumentException("Unsupported container");
        ProcessBuilder builder = new ProcessBuilder(List.of(binary.toString(),
            "-hide_banner", "-loglevel", "error", "-nostdin",
            "-protocol_whitelist", "pipe",
            "-threads", "1", "-f", format, "-i", "pipe:0",
            "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn",
            "-threads", "1", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1"));
        builder.environment().clear();
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new IllegalStateException("FFMPEG_LAUNCH_FAILED");
        }
        try {
            Thread writer = new Thread(() -> {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(input);
                    stdin.flush();
                } catch (IOException closedInput) {
                    // First-frame extraction may close stdin before the remaining container bytes are consumed.
                }
            }, "video-ffmpeg-stdin");
            writer.setDaemon(true);
            writer.start();
            var stdout = new ByteArrayOutputStream();
            var readFailure = new AtomicReference<IOException>();
            Thread stdoutReader = new Thread(() -> copyBounded(process.getInputStream(), stdout, MAX_OUTPUT_BYTES + 1, readFailure),
                "video-ffmpeg-stdout");
            stdoutReader.setDaemon(true);
            stdoutReader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFMPEG_TIMEOUT");
            }
            writer.join(1000);
            stdoutReader.join(1000);
            if (stdoutReader.isAlive() || writer.isAlive()) throw new IllegalStateException("FFMPEG_PIPE_TIMEOUT");
            if (readFailure.get() != null) throw new IllegalStateException("FFMPEG_IO_FAILED");
            if (stdout.size() > MAX_OUTPUT_BYTES) throw new IllegalStateException("FFMPEG_OUTPUT_TOO_LARGE");
            if (process.exitValue() != 0) throw new IllegalStateException("FFMPEG_EXTRACTION_FAILED");
            if (stdout.size() == 0) throw new IllegalStateException("FFMPEG_EXTRACTION_FAILED");
            byte[] jpeg = stdout.toByteArray();
            if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8
                || (jpeg[jpeg.length - 2] & 0xFF) != 0xFF || (jpeg[jpeg.length - 1] & 0xFF) != 0xD9)
                throw new IllegalStateException("FFMPEG_OUTPUT_NOT_JPEG");
            return jpeg;
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFMPEG_INTERRUPTED");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void copyBounded(InputStream in, ByteArrayOutputStream out, int maximum, AtomicReference<IOException> failure) {
        try (in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                int remaining = maximum - out.size();
                if (remaining > 0) out.write(buffer, 0, Math.min(read, remaining));
            }
        } catch (IOException error) { failure.set(error); }
    }
}
