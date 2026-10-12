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
 * 可以串流掃描的簡單 XML 路徑，例如 {@code //Order/Status}、{@code /Order/Status}、{@code Order/Status}、
 * {@code Status}、{@code //Item/@sku}、{@code //Status/text()}。
 * <p>
 * 元素與屬性一律以 local name 比對（不分 namespace 前綴）。{@code /} 開頭是從根元素起算的完整路徑；
 * 其他寫法（含 {@code //} 開頭與相對路徑）是「路徑結尾符合」。取值為元素的全部文字（含子元素）或屬性值，去除前後空白。
 * <p>
 * 串流掃描（{@link XmlStreamScan}）與 DOM 查找（{@link #select(Document)}）使用同一套規則，結果相同；
 * 含 predicate、函式、萬用字元、軸或帶前綴名稱的寫法不屬於這裡，交給 XPath 處理。
 *
 * @param elements  元素 local name，由外而內
 * @param absolute  是否從根元素起算
 * @param attribute 結尾屬性的 local name；取元素文字時為 null
 */
public record XmlPath(List<String> elements, boolean absolute, String attribute) {

    public XmlPath {
        elements = List.copyOf(elements);
        if (elements.isEmpty()) {
            throw new IllegalArgumentException("XML path needs at least one element");
        }
    }

    /** 不是簡單路徑時回傳 empty。 */
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

    /** 由外而內的元素 local name 是否符合此路徑。 */
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

    /** 路徑最後一個元素的 local name，串流掃描用來快速篩選。 */
    String lastElement() {
        return elements.get(elements.size() - 1);
    }

    /**
     * 在已解析的 DOM 上依文件順序取值，規則與串流掃描相同。DOM 不分 namespace，因此 local name 取 {@code :} 之後的部分；
     * namespace 宣告（{@code xmlns}、{@code xmlns:*}）不是屬性。
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
        // 與 JDK XPath 的 local-name() 相同：取第一個 : 之後的部分
        int colon = qualifiedName.indexOf(':');
        return colon < 0 ? qualifiedName : qualifiedName.substring(colon + 1);
    }
}
