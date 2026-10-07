package com.localrepo.server.setup;

import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds and removes the LocalRepoServer mirror in a Maven {@code settings.xml} as text, so everything else in the
 * file, comments and formatting included, stays exactly as the user wrote it. The inserted block is fenced by marker
 * comments; the end marker records the original text it replaced, so uninstalling restores the file byte for byte.
 */
public class MavenSettingsEditor {

    static final String MIRROR_ID = "localrepo";

    private static final String BEGIN = "<!-- LocalRepoServer:begin -->";
    private static final String CREATED = "<!-- LocalRepoServer:created -->";
    private static final String INDENT = "\n    ";
    private static final Pattern BLOCK = Pattern.compile(
            "(?:\\n    )?" + Pattern.quote(BEGIN) + "(?s:.*?)<!-- LocalRepoServer:end restore=\"([A-Za-z0-9+/=]*)\" -->");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern MIRRORS_OPEN = Pattern.compile("<mirrors\\s*>");
    private static final Pattern MIRRORS_EMPTY = Pattern.compile("<mirrors\\s*/>");
    private static final Pattern SETTINGS_OPEN = Pattern.compile("<settings(?:\\s[^>]*)?>");

    /** Returns the settings with the mirror to {@code url} installed; {@code existing} may be null. */
    public String install(String existing, String url) {
        if (existing == null || existing.isBlank() || existing.contains(CREATED)) {
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    %s
                    <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                      <mirrors>%s
                      </mirrors>
                    </settings>
                    """.formatted(CREATED, block(mirror(url), ""));
        }
        String settings = uninstall(requireValid(existing));

        String result;
        Matcher open = firstOutsideComments(MIRRORS_OPEN, settings);
        Matcher empty = firstOutsideComments(MIRRORS_EMPTY, settings);
        if (open != null) {
            result = settings.substring(0, open.end()) + block(mirror(url), "") + settings.substring(open.end());
        } else if (empty != null) {
            String replacement = BEGIN + "<mirrors>" + INDENT + mirror(url) + "\n  </mirrors>" + end(empty.group());
            result = settings.substring(0, empty.start()) + replacement + settings.substring(empty.end());
        } else {
            Matcher root = firstOutsideComments(SETTINGS_OPEN, settings);
            if (root == null) {
                throw new IllegalArgumentException("No <settings> element found");
            }
            String mirrors = "<mirrors>" + INDENT + "  " + mirror(url).replace("\n", "\n  ") + INDENT + "</mirrors>";
            result = settings.substring(0, root.end()) + block(mirrors, "") + settings.substring(root.end());
        }
        return requireValid(result);
    }

    /** Returns the settings without the mirror, or null if LocalRepoServer created the whole file. */
    public String uninstall(String settings) {
        if (settings.contains(CREATED)) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        Matcher block = BLOCK.matcher(settings);
        int last = 0;
        while (block.find()) {
            result.append(settings, last, block.start())
                    .append(new String(Base64.getDecoder().decode(block.group(1)), StandardCharsets.UTF_8));
            last = block.end();
        }
        return result.append(settings.substring(last)).toString();
    }

    public boolean isInstalled(String settings, String url) {
        Matcher block = BLOCK.matcher(settings);
        return block.find() && block.group().contains("<url>" + url + "</url>");
    }

    private static String mirror(String url) {
        return """
                <mirror>
                      <id>%s</id>
                      <name>LocalRepoServer</name>
                      <mirrorOf>*</mirrorOf>
                      <url>%s</url>
                    </mirror>""".formatted(MIRROR_ID, url);
    }

    private static String block(String content, String replaced) {
        return INDENT + BEGIN + INDENT + content + INDENT + end(replaced);
    }

    private static String end(String replaced) {
        String encoded = Base64.getEncoder().encodeToString(replaced.getBytes(StandardCharsets.UTF_8));
        return "<!-- LocalRepoServer:end restore=\"" + encoded + "\" -->";
    }

    private static Matcher firstOutsideComments(Pattern pattern, String text) {
        List<int[]> comments = new ArrayList<>();
        Matcher comment = COMMENT.matcher(text);
        while (comment.find()) {
            comments.add(new int[]{comment.start(), comment.end()});
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int at = matcher.start();
            if (comments.stream().noneMatch(c -> at >= c[0] && at < c[1])) {
                return matcher;
            }
        }
        return null;
    }

    private static String requireValid(String xml) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.newSAXParser().parse(new InputSource(new StringReader(xml)), new DefaultHandler());
            return xml;
        } catch (Exception e) {
            throw new IllegalArgumentException("settings.xml is not valid XML: " + e.getMessage(), e);
        }
    }
}
