package com.guanghe.backend.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class FfprobeVideoInspector {
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private final AssetIngestionProperties properties;
    private final ObjectMapper json;

    FfprobeVideoInspector(AssetIngestionProperties properties) {
        this(properties, new ObjectMapper());
    }

    FfprobeVideoInspector(AssetIngestionProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    VideoMetadata inspect(Path path) {
        Process process = null;
        try {
            process = new ProcessBuilder(command(path)).redirectErrorStream(true).start();
            if (!process.waitFor(properties.getFfprobeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AssetIngestionException("VIDEO_FFPROBE_TIMEOUT", true);
            }
            byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
            if (process.exitValue() != 0 || output.length > MAX_OUTPUT_BYTES) {
                throw new AssetIngestionException("VIDEO_FFPROBE_FAILED", false);
            }
            JsonNode root = json.readTree(output);
            JsonNode format = root.path("format");
            JsonNode streams = root.path("streams");
            if (!streams.isArray() || streams.isEmpty() || !format.isObject()) {
                throw new AssetIngestionException("VIDEO_METADATA_INVALID", false);
            }
            JsonNode stream = null;
            for (JsonNode candidate : streams) {
                if ("video".equals(candidate.path("codec_type").textValue())) {
                    stream = candidate;
                    break;
                }
            }
            if (stream == null) throw new AssetIngestionException("VIDEO_STREAM_MISSING", false);
            String formatName = format.path("format_name").textValue();
            String codec = stream.path("codec_name").textValue();
            int width = stream.path("width").intValue();
            int height = stream.path("height").intValue();
            BigDecimal duration = new BigDecimal(format.path("duration").textValue());
            long size = Long.parseLong(format.path("size").textValue());
            Set<String> allowed = properties.getAllowedVideoCodecs().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
            if (formatName == null || java.util.Arrays.stream(formatName.split(","))
                    .noneMatch(name -> "mp4".equals(name) || "mov".equals(name))
                    || codec == null || !allowed.contains(codec.toLowerCase(Locale.ROOT))
                    || width < 8 || width > 8192 || height < 8 || height > 8192
                    || duration.signum() <= 0 || size <= 0 || size != Files.size(path)) {
                throw new AssetIngestionException("VIDEO_METADATA_INVALID", false);
            }
            return new VideoMetadata(width, height, duration.stripTrailingZeros().toPlainString(), codec);
        } catch (AssetIngestionException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssetIngestionException("VIDEO_FFPROBE_INTERRUPTED", true, exception);
        } catch (Exception exception) {
            throw new AssetIngestionException("VIDEO_FFPROBE_FAILED", false, exception);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    List<String> command(Path path) {
        List<String> command = new ArrayList<>();
        command.add(properties.getFfprobePath());
        command.add("-v");
        command.add("error");
        command.add("-select_streams");
        command.add("v:0");
        command.add("-show_entries");
        command.add("stream=codec_type,codec_name,width,height:format=format_name,duration,size");
        command.add("-of");
        command.add("json");
        command.add(path.toAbsolutePath().normalize().toString());
        return List.copyOf(command);
    }

    record VideoMetadata(int width, int height, String durationSeconds, String codec) { }
}
