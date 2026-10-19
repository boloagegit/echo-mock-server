package com.echo.core.match;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Simple XML paths that can be streamed and scanned, such as {@code //Order/Status}, {@code /Order/Status}, {@code Order/Status},
 * {@code Status}、{@code //Item/@sku}、{@code //Status/text()}。
 * <p>
 * Elements and attributes are always compared with local name (regardless of the namespace prefix). {@code /} starts with the complete path starting from the root element;
 * Other writing methods (including starting with {@code //} and relative paths) are "the end of the path matches". The value is the entire text (including sub-elements) or attribute values of the element, with the leading and trailing whitespace removed.
 * <p>
 * Streaming scan ({@link XmlStreamScan}) and DOM search ({@link #select(Document)}) use the same set of rules and the results are the same;
 * Writing methods containing predicate, functions, wildcards, axes or prefixed names do not belong here and are left to XPath for processing.
 *
 * @param elements  Element local name, outside in
 * @param absolute  Whether to count from the root element
 * @param attribute The ending attribute is local name; when taking the element text, it is null
 */
public record XmlPath(List<String> elements, boolean absolute, String attribute) {

    public XmlPath {
        elements = List.copyOf(elements);
        if (elements.isEmpty()) {
            throw new IllegalArgumentException("XML path needs at least one element");
        }
    }

    /** Return empty when it is not a simple path. */
    public static Optional<XmlPath> parse(String expression) {
        if (expression == null || expression.isBlank() || expression.startsWith("$.")) {
            return Optional.empty();
        }
        String path = expression.trim();
        if (path.endsWith("/text()")) {
            path = path.substring(0, path.length() - "/text()".length());
        }
        if (path.contains("[") || path.contains("]") || path.contains("(") || path.contains(")")
                || path.contains("*") || path.contains("|")) {
            return Optional.empty();
        }
        boolean absolute = path.startsWith("/") && !path.startsWith("//");
        if (path.startsWith("//")) {
            path = path.substring(2);
        } else if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = path.split("/", -1);
        List<String> elements = new ArrayList<>(parts.length);
        String attribute = null;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.contains(":")
                    || part.chars().anyMatch(Character::isWhitespace)) {
                return Optional.empty();
            }
            if (part.startsWith("@")) {
                if (i != parts.length - 1 || part.length() == 1) {
                    return Optional.empty();
                }
                attribute = part.substring(1);
            } else {
                if (attribute != null) {
                    return Optional.empty();
                }
                elements.add(part);
            }
        }
        return elements.isEmpty() ? Optional.empty() : Optional.of(new XmlPath(elements, absolute, attribute));
    }

    /** Does outside-in element local name match this path. */
    boolean matchesElementPath(List<String> currentPath) {
        if (absolute) {
            return currentPath.equals(elements);
        }
        int offset = currentPath.size() - elements.size();
        if (offset < 0) {
            return false;
        }
        for (int i = 0; i < elements.size(); i++) {
            if (!elements.get(i).equals(currentPath.get(offset + i))) {
                return false;
            }
        }
        return true;
    }

    /** local name of the last element of the path, stream scanning is used for quick screening. */
    String lastElement() {
        return elements.get(elements.size() - 1);
    }

    /**
     * Get the value in file order on the parsed DOM, the rules are the same as stream scanning. DOM does not distinguish namespace, so local name takes the part after {@code :};
     * The namespace declaration ({@code xmlns}, {@code xmlns:*}) is not an attribute.
     */
    public List<String> select(Document doc) {
        List<String> values = new ArrayList<>();
        NodeList all = doc.getElementsByTagName("*");
        List<String> ancestors = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (!lastElement().equals(localName(element))) {
                continue;
            }
            ancestors.clear();
            for (Node node = element; node instanceof Element; node = node.getParentNode()) {
                ancestors.add(0, localName(node));
            }
            if (!matchesElementPath(ancestors)) {
                continue;
            }
            if (attribute == null) {
                values.add(element.getTextContent().trim());
            } else {
                String value = attributeValue(element);
                if (value != null) {
                    values.add(value.trim());
                }
            }
        }
        return values;
    }

    private String attributeValue(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            String name = attr.getName();
            if ("xmlns".equals(name) || name.startsWith("xmlns:")) {
                continue;
            }
            if (attribute.equals(localName(attr))) {
                return attr.getValue();
            }
        }
        return null;
    }

    static String localName(Node node) {
        String localName = node.getLocalName();
        if (localName != null) {
            return localName;
        }
        String qualifiedName = node.getNodeName();
        // Same as local-name() of JDK XPath: take the part after the first :
        int colon = qualifiedName.indexOf(':');
        return colon < 0 ? qualifiedName : qualifiedName.substring(colon + 1);
    }
}
