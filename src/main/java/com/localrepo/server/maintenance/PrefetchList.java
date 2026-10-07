package com.localrepo.server.maintenance;

import com.localrepo.server.artifact.ArtifactPath;
import com.localrepo.server.artifact.InvalidArtifactPathException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What to download ahead of time. Either Gradle's {@code gradle/verification-metadata.xml}, or lines of
 * {@code group:artifact:version[:classifier][@extension]} coordinates (the POM, Gradle module metadata and the
 * artifact, a jar unless another extension is given) and plain repository paths. {@code #} starts a comment.
 */
final class PrefetchList {

    private PrefetchList() {
    }

    static List<ArtifactPath> parse(String text) {
        String trimmed = text.strip();
        Set<ArtifactPath> paths = new LinkedHashSet<>();
        if (trimmed.startsWith("<")) {
            readVerificationMetadata(trimmed, paths);
        } else {
            for (String line : trimmed.split("\\R")) {
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                if (entry.contains(":") && !entry.contains("/")) {
                    readCoordinate(entry, paths);
                } else {
                    paths.add(path(entry));
                }
            }
        }
        return List.copyOf(paths);
    }

    private static void readCoordinate(String coordinate, Set<ArtifactPath> paths) {
        String extension = "jar";
        String spec = coordinate;
        int at = spec.indexOf('@');
        if (at >= 0) {
            extension = spec.substring(at + 1);
            spec = spec.substring(0, at);
        }
        String[] parts = spec.split(":");
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException("Expected group:artifact:version[:classifier][@extension]: " + coordinate);
        }
        String base = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2];
        String classifier = parts.length == 4 ? "-" + parts[3] : "";
        paths.add(path(base + ".pom"));
        paths.add(path(base + ".module"));
        paths.add(path(base + classifier + "." + extension));
    }

    private static void readVerificationMetadata(String xml, Set<ArtifactPath> paths) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            NodeList components = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
                    .getElementsByTagNameNS("*", "component");
            for (int i = 0; i < components.getLength(); i++) {
                Element component = (Element) components.item(i);
                String directory = component.getAttribute("group").replace('.', '/') + "/"
                        + component.getAttribute("name") + "/" + component.getAttribute("version") + "/";
                NodeList artifacts = component.getElementsByTagNameNS("*", "artifact");
                for (int j = 0; j < artifacts.getLength(); j++) {
                    paths.add(path(directory + ((Element) artifacts.item(j)).getAttribute("name")));
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Not readable as Gradle verification metadata: " + e.getMessage(), e);
        }
    }

    private static ArtifactPath path(String value) {
        try {
            return ArtifactPath.of(value);
        } catch (InvalidArtifactPathException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
