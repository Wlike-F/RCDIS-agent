package com.rcdis.agent.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anchors the Agent system prompt for the tuning loop: the content hash recorded in run
 * manifests, and section extraction keyed by {@code <!-- @tunable:<id> -->} markers placed
 * under each H1 heading of the prompt file.
 *
 * <p>Read-only by design: this class never writes the prompt file; humans apply the suggested
 * edits so prompt changes stay auditable.</p>
 */
public final class AgentEvalPromptAnchor {

    private static final Pattern ANCHOR = Pattern.compile("<!--\\s*@tunable:([a-z0-9-]+)\\s*-->");
    private static final Pattern H1 = Pattern.compile("^#\\s+(.*)$");

    /** One tunable prompt section delimited by an H1 heading carrying an anchor marker. */
    public record PromptSection(String tunableId, String title, String text) {
    }

    private final String content;

    private AgentEvalPromptAnchor(String content) {
        // Normalize CRLF so heading matching and section extraction are line-ending agnostic.
        this.content = content.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static AgentEvalPromptAnchor load(String classpathResource) {
        InputStream stream = AgentEvalPromptAnchor.class.getClassLoader().getResourceAsStream(classpathResource);
        if (stream == null) {
            throw new IllegalStateException("Agent system prompt resource not found: " + classpathResource);
        }
        try (InputStream in = stream) {
            return new AgentEvalPromptAnchor(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read Agent system prompt resource: " + classpathResource,
                    exception);
        }
    }

    /** SHA-256 hex digest of the prompt content; the version anchor recorded in run manifests. */
    public String sha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    /** Sections keyed by tunable id, in document order. Headings without an anchor are skipped. */
    public Map<String, PromptSection> sections() {
        Map<String, PromptSection> sections = new LinkedHashMap<>();
        String currentTitle = null;
        StringBuilder currentText = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            Matcher heading = H1.matcher(line);
            if (heading.matches()) {
                put(sections, currentTitle, currentText);
                currentTitle = heading.group(1).trim();
                currentText = new StringBuilder();
            } else if (currentTitle != null) {
                currentText.append(line).append('\n');
            }
        }
        put(sections, currentTitle, currentText);
        return Collections.unmodifiableMap(sections);
    }

    private static void put(Map<String, PromptSection> sections, String title, StringBuilder text) {
        if (title == null) {
            return;
        }
        String body = text.toString();
        Matcher anchor = ANCHOR.matcher(body);
        if (!anchor.find()) {
            return;
        }
        String tunableId = anchor.group(1);
        String cleanText = ANCHOR.matcher(body).replaceAll("").strip();
        sections.put(tunableId, new PromptSection(tunableId, title, cleanText));
    }
}
