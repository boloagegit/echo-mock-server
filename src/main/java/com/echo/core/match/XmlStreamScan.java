package com.echo.core.match;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scan XML once with StAX to evaluate all streamable conditions without building a DOM (section 4.5).
 * <p>
 * Extraction matches {@link XmlPath#select}: compare element and attribute local names; collect and trim all element text, including descendant text.
 * Ignore oversized values. Stop once all condition outcomes are known, without reading the rest of the document.
 */
public final class XmlStreamScan {

    private static final ThreadLocal<XMLInputFactory> INPUT_FACTORY = ThreadLocal.withInitial(() -> {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory;
    });

    private XmlStreamScan() {
    }

    /**
     * Scan {@code xml} and return each streamable condition's outcome, keyed by its temporary condition key.
     *
     * @param maxValueLength Maximum extracted-value length; ignore oversized values. Each condition also checks its own limit.
     * @throws XMLStreamException Invalid XML, including undeclared namespace prefixes; the caller falls back to normal parsing.
     */
    public static Map<String, Boolean> evaluate(String xml, BodyRequirements requirements, int maxValueLength)
            throws XMLStreamException {
        List<Active> active = new ArrayList<>(requirements.streamCheckCount());
        Map<String, List<Active>> byLastElement = new HashMap<>();
        for (BodyRequirements.StreamCheck check : requirements.streamChecks()) {
            Active entry = new Active(check, check.newProbe());
            active.add(entry);
            byLastElement.computeIfAbsent(check.path().lastElement(), name -> new ArrayList<>()).add(entry);
        }
        if (!active.isEmpty()) {
            scan(xml, byLastElement, active.size(), maxValueLength);
        }
        Map<String, Boolean> outcomes = new HashMap<>(active.size() * 2);
        for (Active entry : active) {
            outcomes.put(entry.check.cacheKey(), entry.probe.result());
        }
        return outcomes;
    }

    private static void scan(String xml, Map<String, List<Active>> byLastElement, int undecidedAtStart,
                             int maxValueLength) throws XMLStreamException {
        XMLStreamReader reader = INPUT_FACTORY.get().createXMLStreamReader(new StringReader(xml));
        List<String> path = new ArrayList<>();
        Deque<Capture> captures = new ArrayDeque<>();
        int undecided = undecidedAtStart;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    path.add(name);
                    List<Active> candidates = byLastElement.get(name);
                    if (candidates == null) {
                        continue;
                    }
                    List<Active> textChecks = null;
                    for (Active entry : candidates) {
                        if (entry.decided || !entry.check.path().matchesElementPath(path)) {
                            continue;
                        }
                        String attributeName = entry.check.path().attribute();
                        if (attributeName == null) {
                            if (textChecks == null) {
                                textChecks = new ArrayList<>();
                            }
                            textChecks.add(entry);
                        } else {
                            String value = attributeValue(reader, attributeName);
                            if (value != null && entry.offer(value.trim(), maxValueLength)) {
                                undecided--;
                            }
                        }
                    }
                    if (textChecks != null) {
                        captures.addLast(new Capture(path.size(), textChecks));
                    }
                    if (undecided == 0) {
                        return;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    for (Capture capture : captures) {
                        capture.append(reader.getTextCharacters(), reader.getTextStart(),
                                reader.getTextLength(), maxValueLength);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    int depth = path.size();
                    var iterator = captures.descendingIterator();
                    while (iterator.hasNext()) {
                        Capture capture = iterator.next();
                        if (capture.depth != depth) {
                            continue;
                        }
                        iterator.remove();
                        String value = capture.value == null ? null : capture.value.toString().trim();
                        for (Active entry : capture.checks) {
                            if (!entry.decided && entry.offer(value, maxValueLength)) {
                                undecided--;
                            }
                        }
                    }
                    path.remove(path.size() - 1);
                    if (undecided == 0) {
                        return;
                    }
                }
            }
        } finally {
            reader.close();
        }
    }

    private static String attributeValue(XMLStreamReader reader, String localName) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (localName.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    private static final class Active {

        private final BodyRequirements.StreamCheck check;
        private final BodyRequirements.Probe probe;
        private boolean decided;

        private Active(BodyRequirements.StreamCheck check, BodyRequirements.Probe probe) {
            this.check = check;
            this.probe = probe;
        }

        /** @param value Trimmed value, or null if discarded as oversized. Return true if this value makes the outcome conclusive. */
        private boolean offer(String value, int maxValueLength) {
            if (value == null || value.length() > maxValueLength) {
                probe.skipped();
                return false;
            }
            if (probe.offer(value)) {
                decided = true;
                return true;
            }
            return false;
        }
    }

    /** Collect element text, including descendants; discard it and ignore this value if the length limit is exceeded. */
    private static final class Capture {

        private final int depth;
        private final List<Active> checks;
        private StringBuilder value = new StringBuilder();

        private Capture(int depth, List<Active> checks) {
            this.depth = depth;
            this.checks = checks;
        }

        private void append(char[] chars, int start, int length, int maxValueLength) {
            if (value == null) {
                return;
            }
            if (value.length() > maxValueLength - length) {
                value = null;
                return;
            }
            value.append(chars, start, length);
        }
    }
}
