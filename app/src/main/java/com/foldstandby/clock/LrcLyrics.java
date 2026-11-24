package com.foldstandby.clock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Timestamped lyrics with lookup by media position, including seeks and pauses. */
final class LrcLyrics {
    static final int MAX_BYTES = 256 * 1024;
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,6}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]\\s*");
    private static final Pattern WORD_TIME = Pattern.compile("<\\d{1,6}:[0-5]\\d(?:[.:]\\d{1,3})?>");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d{1,9})\\s*\\]", Pattern.CASE_INSENSITIVE);
    private static final LrcLyrics EMPTY = new LrcLyrics(new long[0], new String[0]);
    static final LrcLyrics PREVIEW = parse("[01:12]夜风吹过街角\n[01:19]留一束光等天亮\n[01:26]把回声交给远方\n[01:32]");

    private final long[] times;
    private final String[] lines;

    private LrcLyrics(long[] times, String[] lines) {
        this.times = times;
        this.lines = lines;
    }

    static String read(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (buffer.size() + count > MAX_BYTES) throw new IOException("LRC 文件超过 256 KB");
            buffer.write(chunk, 0, count);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            return new String(bytes, StandardCharsets.UTF_16);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    static LrcLyrics parse(String source) {
        if (source == null || source.isEmpty()) return EMPTY;
        TreeMap<Long, String> cues = new TreeMap<>();
        long offset = 0;
        Matcher timestamps = TIME.matcher("");
        for (String raw : source.split("\\r\\n|\\r|\\n")) {
            String line = raw.startsWith("\ufeff") ? raw.substring(1).trim() : raw.trim();
            Matcher shift = OFFSET.matcher(line);
            if (shift.matches()) {
                offset = Long.parseLong(shift.group(1));
                continue;
            }
            timestamps.reset(line);
            int end = 0;
            while (timestamps.region(end, line.length()).lookingAt()) end = timestamps.end();
            if (end == 0) continue;
            String text = WORD_TIME.matcher(line.substring(end)).replaceAll("").trim();
            for (int start = 0; start < end && timestamps.region(start, end).lookingAt(); start = timestamps.end()) {
                String fraction = timestamps.group(3);
                int millis = fraction == null ? 0
                        : Integer.parseInt(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
                long time = Long.parseLong(timestamps.group(1)) * 60000 + Integer.parseInt(timestamps.group(2)) * 1000L + millis;
                String current = cues.get(time);
                if (current == null || current.isEmpty()) cues.put(time, text);
            }
        }
        if (cues.values().stream().noneMatch(text -> !text.isEmpty())) return EMPTY;
        long[] times = new long[cues.size()];
        String[] lines = new String[times.length];
        int i = 0;
        for (Map.Entry<Long, String> cue : cues.entrySet()) {
            times[i] = cue.getKey() - offset;
            lines[i++] = cue.getValue();
        }
        return new LrcLyrics(times, lines);
    }

    boolean hasLines() { return times.length > 0; }

    int indexAt(long position) {
        int match = Arrays.binarySearch(times, position);
        return match >= 0 ? match : -match - 2;
    }

    String lineAt(int index) { return index < 0 || index >= lines.length ? "" : lines[index]; }

    long nextTime(int index) { return index + 1 < times.length ? times[index + 1] : Long.MAX_VALUE; }
}
