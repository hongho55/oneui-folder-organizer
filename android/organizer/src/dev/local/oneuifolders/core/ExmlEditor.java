package dev.local.oneuifolders.core;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Offline, fail-closed editor for the appOrder section of a Home Up EXML backup. */
public final class ExmlEditor {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String UNFILED = "미분류";
    private static final Pattern DTD_OR_ENTITY = Pattern.compile("<!\\s*(?:DOCTYPE|ENTITY)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_DECLARATION = Pattern.compile(
            "\\A<\\?xml\\s+version\\s*=\\s*(['\"])1\\.0\\1"
                    + "(?:\\s+encoding\\s*=\\s*(['\"])([^'\"]+)\\2)?"
                    + "(?:\\s+standalone\\s*=\\s*(['\"])(yes|no)\\4)?\\s*\\?>\\z");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");
    private static final Pattern FOLDER_GRID = Pattern.compile("([1-9][0-9]*)X([1-9][0-9]*)");

    private ExmlEditor() { }

    /** Parsed source model. The private source snapshot is immutable from callers. */
    public static final class Snapshot {
        public final List<App> apps;
        public final LinkedHashMap<String, List<String>> folders;
        public final String sourceSha256;

        private final byte[] sourceBytes;
        private final int appOrderStart;
        private final int appOrderEnd;
        private final int appCapacity;
        private final int folderCapacity;
        private final List<SourceApp> sourceApps;
        private final LinkedHashMap<String, String> appOrderAttributes;

        private Snapshot(byte[] sourceBytes, int appOrderStart, int appOrderEnd,
                int appCapacity, int folderCapacity, List<SourceApp> sourceApps,
                LinkedHashMap<String, List<String>> folders,
                LinkedHashMap<String, String> appOrderAttributes) {
            this.sourceBytes = sourceBytes.clone();
            this.appOrderStart = appOrderStart;
            this.appOrderEnd = appOrderEnd;
            this.appCapacity = appCapacity;
            this.folderCapacity = folderCapacity;
            this.sourceApps = Collections.unmodifiableList(new ArrayList<SourceApp>(sourceApps));
            List<App> appView = new ArrayList<App>();
            for (SourceApp app : sourceApps) {
                appView.add(app.app);
            }
            this.apps = Collections.unmodifiableList(appView);
            this.folders = new LinkedHashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> entry : folders.entrySet()) {
                this.folders.put(entry.getKey(), Collections.unmodifiableList(
                        new ArrayList<String>(entry.getValue())));
            }
            this.appOrderAttributes = new LinkedHashMap<String, String>(appOrderAttributes);
            this.sourceSha256 = sha256(sourceBytes);
        }
    }

    /** One exact Home Up package/activity component. */
    public static final class App {
        public final String key;
        public final String packageName;
        public final String activity;
        public final String folder;

        private App(String packageName, String activity, String folder) {
            this.packageName = packageName;
            this.activity = activity;
            this.folder = folder;
            this.key = packageName + "\t" + activity;
        }
    }

    /** Built EXML bytes and integrity metadata. */
    public static final class Result {
        public final byte[] bytes;
        public final String sourceSha256;
        public final String outputSha256;
        public final int appCount;
        public final int folderCount;
        public final boolean protectedBytesIdentical;

        private Result(byte[] bytes, String sourceSha256, int appCount, int folderCount,
                boolean protectedBytesIdentical) {
            this.bytes = bytes.clone();
            this.sourceSha256 = sourceSha256;
            this.outputSha256 = sha256(bytes);
            this.appCount = appCount;
            this.folderCount = folderCount;
            this.protectedBytesIdentical = protectedBytesIdentical;
        }
    }

    private static final class SourceApp {
        final App app;
        final LinkedHashMap<String, String> attributes;

        SourceApp(App app, LinkedHashMap<String, String> attributes) {
            this.app = app;
            this.attributes = attributes;
        }
    }

    private static final class Span {
        final int start;
        final int end;

        Span(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /** Parse a native EXML fragment without changing or normalizing its source bytes. */
    public static Snapshot parse(byte[] raw) throws Exception {
        if (raw == null || raw.length == 0 || raw.length > MAX_BYTES) {
            throw new IllegalArgumentException("Expected a nonempty EXML file no larger than 8 MiB");
        }
        String source = decodeUtf8(raw);
        if (DTD_OR_ENTITY.matcher(source).find()) {
            throw new IllegalArgumentException("DTD/entity declarations are not supported");
        }
        Span span = findAppOrderSpan(raw);
        String wrapped = "<__exml_root__>" + removeXmlDeclarations(source) + "</__exml_root__>";
        Document document = parseDocument(wrapped);
        Element documentRoot = document.getDocumentElement();
        Element appOrder = null;
        for (Node node = documentRoot.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && "appOrder".equals(((Element) node).getTagName())) {
                if (appOrder != null) {
                    throw new IllegalArgumentException("Expected exactly one top-level appOrder block");
                }
                appOrder = (Element) node;
            }
        }
        if (appOrder == null) {
            throw new IllegalArgumentException("Expected exactly one top-level appOrder block");
        }
        if (!"CUSTOM".equals(directChildText(documentRoot, "viewType_appOrder"))) {
            throw new IllegalArgumentException("Only CUSTOM Apps order is supported");
        }
        int folderCapacity = parseGridCapacity(directChildText(documentRoot, "FolderGrid"), "FolderGrid");
        int rows = parsePositive(directChildText(documentRoot, "Rows_appOrder"), "Rows_appOrder");
        int columns = parsePositive(directChildText(documentRoot, "Columns_appOrder"), "Columns_appOrder");
        long appCapacityLong = (long) rows * (long) columns;
        if (appCapacityLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Rows_appOrder x Columns_appOrder is too large");
        }
        int appCapacity = (int) appCapacityLong;

        LinkedHashMap<String, SourceApp> byKey = new LinkedHashMap<String, SourceApp>();
        LinkedHashMap<String, List<String>> folders = new LinkedHashMap<String, List<String>>();
        List<SourceApp> sourceApps = new ArrayList<SourceApp>();
        boolean sawUnfiled = false;
        for (Node node = appOrder.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (isWhitespaceText(node)) {
                continue;
            }
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                throw new IllegalArgumentException("Unsupported content in appOrder");
            }
            Element item = (Element) node;
            String tag = item.getTagName();
            if ("favorite".equals(tag)) {
                SourceApp app = readFavorite(item, "");
                if (!sawUnfiled) {
                    if (folders.containsKey(UNFILED)) {
                        throw new IllegalArgumentException("Folder name conflicts with the unfiled group");
                    }
                    folders.put(UNFILED, new ArrayList<String>());
                    sawUnfiled = true;
                }
                addSourceApp(app, sourceApps, byKey);
                folders.get(UNFILED).add(app.app.key);
            } else if ("folder".equals(tag)) {
                String title = item.hasAttribute("title") ? item.getAttribute("title") : null;
                if (!isNonWhitespace(title)) {
                    throw new IllegalArgumentException("Every source folder needs a nonempty title");
                }
                if (folders.containsKey(title)) {
                    throw new IllegalArgumentException("Duplicate source folder title");
                }
                List<String> members = new ArrayList<String>();
                folders.put(title, members);
                for (Node child = item.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (isWhitespaceText(child)) {
                        continue;
                    }
                    if (child.getNodeType() != Node.ELEMENT_NODE
                            || !"favorite".equals(((Element) child).getTagName())) {
                        throw new IllegalArgumentException("Unsupported nested item in folder");
                    }
                    SourceApp app = readFavorite((Element) child, title);
                    addSourceApp(app, sourceApps, byKey);
                    members.add(app.app.key);
                }
                if (members.isEmpty()) {
                    throw new IllegalArgumentException("Empty source folders are not supported");
                }
            } else {
                throw new IllegalArgumentException("Unsupported item in appOrder: " + tag);
            }
        }
        if (sourceApps.isEmpty()) {
            throw new IllegalArgumentException("Source must contain at least one favorite");
        }
        return new Snapshot(raw, span.start, span.end, appCapacity, folderCapacity,
                sourceApps, folders, attributesOf(appOrder));
    }

    /** Build a replacement appOrder from an exact one-time assignment of every source app. */
    public static Result build(Snapshot snapshot,
            LinkedHashMap<String, List<String>> folders) throws Exception {
        if (snapshot == null) {
            throw new IllegalArgumentException("Snapshot is required");
        }
        if (folders == null || folders.isEmpty()) {
            throw new IllegalArgumentException("At least one destination folder is required");
        }
        LinkedHashMap<String, SourceApp> sourceByKey = new LinkedHashMap<String, SourceApp>();
        for (SourceApp app : snapshot.sourceApps) {
            if (sourceByKey.put(app.app.key, app) != null) {
                throw new IllegalArgumentException("Ambiguous source app key");
            }
        }
        Set<String> assigned = new HashSet<String>();
        for (Map.Entry<String, List<String>> entry : folders.entrySet()) {
            String name = entry.getKey();
            List<String> members = entry.getValue();
            if (!isNonWhitespace(name) || !isXml10Text(name)) {
                throw new IllegalArgumentException("Folder names must be nonempty XML text");
            }
            if (members == null || members.size() < 2) {
                throw new IllegalArgumentException("Each folder must contain at least two apps");
            }
            for (String key : members) {
                if (key == null || !sourceByKey.containsKey(key)) {
                    throw new IllegalArgumentException("Assignment contains an unknown app key");
                }
                if (!assigned.add(key)) {
                    throw new IllegalArgumentException("Every source app must be assigned exactly once");
                }
            }
        }
        if (assigned.size() != sourceByKey.size()) {
            throw new IllegalArgumentException("Assignments must cover every source app exactly once");
        }

        StringBuilder replacement = new StringBuilder();
        replacement.append("<appOrder");
        appendAttributes(replacement, snapshot.appOrderAttributes, null, false);
        replacement.append(">\n");
        int folderIndex = 0;
        for (Map.Entry<String, List<String>> entry : folders.entrySet()) {
            replacement.append("  <folder screen=\"")
                    .append(folderIndex / snapshot.appCapacity)
                    .append("\" title=\"");
            appendEscapedAttribute(replacement, entry.getKey());
            replacement.append("\" options=\"0\" color=\"0\">\n");
            List<String> members = entry.getValue();
            for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
                SourceApp app = sourceByKey.get(members.get(memberIndex));
                replacement.append("    <favorite");
                appendAttributes(replacement, app.attributes,
                        Integer.toString(memberIndex / snapshot.folderCapacity), true);
                replacement.append(" />\n");
            }
            replacement.append("  </folder>\n");
            folderIndex++;
        }
        replacement.append("</appOrder>");

        byte[] replacementBytes = replacement.toString().getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[snapshot.appOrderStart + replacementBytes.length
                + snapshot.sourceBytes.length - snapshot.appOrderEnd];
        System.arraycopy(snapshot.sourceBytes, 0, output, 0, snapshot.appOrderStart);
        System.arraycopy(replacementBytes, 0, output, snapshot.appOrderStart, replacementBytes.length);
        System.arraycopy(snapshot.sourceBytes, snapshot.appOrderEnd, output,
                snapshot.appOrderStart + replacementBytes.length,
                snapshot.sourceBytes.length - snapshot.appOrderEnd);
        boolean protectedIdentical = protectedBytesIdentical(snapshot, output, replacementBytes.length);
        if (!protectedIdentical) {
            throw new IllegalStateException("Protected source bytes changed");
        }

        Snapshot verified = parse(output);
        if (verified.apps.size() != snapshot.sourceApps.size()
                || verified.folders.size() != folders.size()) {
            throw new IllegalStateException("Output structure verification failed");
        }
        verifyFavoriteMetadata(snapshot.sourceApps, verified);
        for (Map.Entry<String, List<String>> entry : folders.entrySet()) {
            List<String> actual = verified.folders.get(entry.getKey());
            if (actual == null || !actual.equals(entry.getValue())) {
                throw new IllegalStateException("Output folder membership verification failed");
            }
        }
        return new Result(output, snapshot.sourceSha256, snapshot.sourceApps.size(),
                folders.size(), protectedIdentical);
    }

    /** Return lowercase SHA-256 hex for the exact supplied bytes. */
    public static String sha256(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Bytes are required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            final char[] digits = "0123456789abcdef".toCharArray();
            for (byte value : digest) {
                int unsigned = value & 0xff;
                hex.append(digits[unsigned >>> 4]);
                hex.append(digits[unsigned & 0x0f]);
            }
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void verifyFavoriteMetadata(List<SourceApp> originals, Snapshot output) {
        LinkedHashMap<String, SourceApp> expected = new LinkedHashMap<String, SourceApp>();
        for (SourceApp app : originals) {
            expected.put(app.app.key, app);
        }
        for (SourceApp actual : output.sourceApps) {
            SourceApp before = expected.get(actual.app.key);
            if (before == null) {
                throw new IllegalStateException("Output introduced an unknown favorite");
            }
            LinkedHashMap<String, String> beforeAttrs = new LinkedHashMap<String, String>(before.attributes);
            LinkedHashMap<String, String> afterAttrs = new LinkedHashMap<String, String>(actual.attributes);
            beforeAttrs.remove("screen");
            afterAttrs.remove("screen");
            if (!beforeAttrs.equals(afterAttrs)) {
                throw new IllegalStateException("Favorite metadata other than screen changed");
            }
        }
    }

    private static boolean protectedBytesIdentical(Snapshot snapshot, byte[] output,
            int replacementLength) {
        int outputSuffixStart = snapshot.appOrderStart + replacementLength;
        if (outputSuffixStart + snapshot.sourceBytes.length - snapshot.appOrderEnd != output.length) {
            return false;
        }
        for (int i = 0; i < snapshot.appOrderStart; i++) {
            if (snapshot.sourceBytes[i] != output[i]) {
                return false;
            }
        }
        int suffixLength = snapshot.sourceBytes.length - snapshot.appOrderEnd;
        for (int i = 0; i < suffixLength; i++) {
            if (snapshot.sourceBytes[snapshot.appOrderEnd + i] != output[outputSuffixStart + i]) {
                return false;
            }
        }
        return true;
    }

    private static void addSourceApp(SourceApp app, List<SourceApp> all,
            LinkedHashMap<String, SourceApp> byKey) {
        if (byKey.containsKey(app.app.key)) {
            throw new IllegalArgumentException("Duplicate app component or ambiguous app key");
        }
        byKey.put(app.app.key, app);
        all.add(app);
    }

    private static SourceApp readFavorite(Element favorite, String folder) {
        validateNoNestedContent(favorite, "favorite");
        String packageName = favorite.hasAttribute("packageName")
                ? favorite.getAttribute("packageName") : "";
        String activity = favorite.hasAttribute("className")
                ? favorite.getAttribute("className") : "";
        if (packageName.length() == 0 || activity.length() == 0) {
            throw new IllegalArgumentException("Every favorite needs exact packageName and className");
        }
        if (!"0".equals(favorite.getAttribute("hidden")) && favorite.hasAttribute("hidden")) {
            throw new IllegalArgumentException("Hidden Apps entries need separate preservation review");
        }
        App app = new App(packageName, activity, folder);
        return new SourceApp(app, attributesOf(favorite));
    }

    private static void validateNoNestedContent(Element element, String label) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isWhitespaceText(child)) {
                continue;
            }
            throw new IllegalArgumentException("Unsupported nested content in " + label);
        }
    }

    private static LinkedHashMap<String, String> attributesOf(Element element) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<String, String>();
        NamedNodeMap nodes = element.getAttributes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Attr attr = (Attr) nodes.item(i);
            attributes.put(attr.getName(), attr.getValue());
        }
        return attributes;
    }

    private static String directChildText(Element parent, String tagName) {
        Element found = null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(((Element) node).getTagName())) {
                if (found != null) {
                    throw new IllegalArgumentException("Duplicate " + tagName);
                }
                found = (Element) node;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Missing " + tagName);
        }
        StringBuilder value = new StringBuilder();
        for (Node child = found.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                value.append(child.getNodeValue());
            } else {
                throw new IllegalArgumentException("Unsupported content in " + tagName);
            }
        }
        return value.toString();
    }

    private static int parseGridCapacity(String value, String label) {
        Matcher matcher = FOLDER_GRID.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid or absent " + label);
        }
        long rows = parsePositiveLong(matcher.group(1), label);
        long columns = parsePositiveLong(matcher.group(2), label);
        if (rows > Integer.MAX_VALUE || columns > Integer.MAX_VALUE
                || rows > Integer.MAX_VALUE / columns) {
            throw new IllegalArgumentException(label + " capacity is too large");
        }
        return (int) (rows * columns);
    }

    private static int parsePositive(String value, String label) {
        long parsed = parsePositiveLong(value, label);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " is too large");
        }
        return (int) parsed;
    }

    private static long parsePositiveLong(String value, String label) {
        if (value == null || !POSITIVE_INTEGER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid or absent " + label);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid or absent " + label, error);
        }
    }

    private static boolean isWhitespaceText(Node node) {
        if (node.getNodeType() != Node.TEXT_NODE && node.getNodeType() != Node.CDATA_SECTION_NODE) {
            return false;
        }
        String value = node.getNodeValue();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!(ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNonWhitespace(String value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String decodeUtf8(byte[] raw) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString();
    }

    private static Span findAppOrderSpan(byte[] bytes) {
        int openingCount = 0;
        int closingCount = 0;
        int openingStart = -1;
        int closingEnd = -1;
        int i = 0;
        while (i < bytes.length) {
            if (bytes[i] != '<') {
                i++;
                continue;
            }
            if (startsWith(bytes, i, "<!--")) {
                i = afterSequence(bytes, i + 4, "-->");
                continue;
            }
            if (startsWith(bytes, i, "<![CDATA[")) {
                i = afterSequence(bytes, i + 9, "]]>");
                continue;
            }
            if (startsWith(bytes, i, "<?")) {
                i = afterSequence(bytes, i + 2, "?>");
                continue;
            }
            if (startsWith(bytes, i, "<!")) {
                i = tagEnd(bytes, i + 2);
                continue;
            }
            int tagStart = i + 1;
            boolean closing = tagStart < bytes.length && bytes[tagStart] == '/';
            int nameStart = closing ? tagStart + 1 : tagStart;
            int nameEnd = nameStart;
            while (nameEnd < bytes.length && !isTagNameEnd(bytes[nameEnd])) {
                nameEnd++;
            }
            if (nameEnd == nameStart) {
                i++;
                continue;
            }
            int end = tagEnd(bytes, nameEnd);
            if (asciiEquals(bytes, nameStart, nameEnd, "appOrder")) {
                if (closing) {
                    closingCount++;
                    if (closingCount == 1) {
                        closingEnd = end;
                    }
                } else {
                    openingCount++;
                    if (openingCount == 1) {
                        openingStart = i;
                    }
                }
            }
            i = end;
        }
        if (openingCount != 1 || closingCount != 1 || openingStart < 0
                || closingEnd <= openingStart) {
            throw new IllegalArgumentException("Unsupported or ambiguous native appOrder byte boundary");
        }
        return new Span(openingStart, closingEnd);
    }

    private static boolean isTagNameEnd(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n'
                || value == '/' || value == '>' || value == '?';
    }

    private static int tagEnd(byte[] bytes, int from) {
        byte quote = 0;
        for (int i = from; i < bytes.length; i++) {
            byte value = bytes[i];
            if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                }
            } else if (value == '\'' || value == '"') {
                quote = value;
            } else if (value == '>') {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("Malformed XML tag boundary");
    }

    private static int afterSequence(byte[] bytes, int from, String sequence) {
        for (int i = from; i <= bytes.length - sequence.length(); i++) {
            if (startsWith(bytes, i, sequence)) {
                return i + sequence.length();
            }
        }
        throw new IllegalArgumentException("Malformed XML comment, CDATA, or processing instruction");
    }

    private static boolean startsWith(byte[] bytes, int offset, String ascii) {
        if (offset < 0 || offset + ascii.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if ((bytes[offset + i] & 0xff) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiEquals(byte[] bytes, int start, int end, String ascii) {
        if (end - start != ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if ((bytes[start + i] & 0xff) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String removeXmlDeclarations(String source) {
        StringBuilder output = new StringBuilder(source.length());
        int copiedThrough = 0;
        int i = 0;
        while (i < source.length()) {
            if (source.charAt(i) != '<') {
                i++;
                continue;
            }
            if (source.startsWith("<!--", i)) {
                int end = source.indexOf("-->", i + 4);
                if (end < 0) {
                    throw new IllegalArgumentException("Malformed XML comment");
                }
                i = end + 3;
                continue;
            }
            if (source.startsWith("<![CDATA[", i)) {
                int end = source.indexOf("]]>", i + 9);
                if (end < 0) {
                    throw new IllegalArgumentException("Malformed CDATA section");
                }
                i = end + 3;
                continue;
            }
            if (!source.startsWith("<?", i)) {
                i++;
                continue;
            }
            int end = source.indexOf("?>", i + 2);
            if (end < 0) {
                throw new IllegalArgumentException("Malformed processing instruction");
            }
            int targetStart = i + 2;
            int targetEnd = targetStart;
            while (targetEnd < end && !Character.isWhitespace(source.charAt(targetEnd))
                    && source.charAt(targetEnd) != '?') {
                targetEnd++;
            }
            String target = source.substring(targetStart, targetEnd);
            if ("xml".equals(target)) {
                String declaration = source.substring(i, end + 2);
                Matcher matcher = XML_DECLARATION.matcher(declaration);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("Unsupported or malformed XML declaration");
                }
                String encoding = matcher.group(3);
                if (encoding != null && !"UTF-8".equalsIgnoreCase(encoding)) {
                    throw new IllegalArgumentException("Only UTF-8 EXML is supported");
                }
                output.append(source, copiedThrough, i);
                copiedThrough = end + 2;
            }
            i = end + 2;
        }
        output.append(source, copiedThrough, source.length());
        return output.toString();
    }

    private static Document parseDocument(String wrapped) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
            // Some Android XML implementations do not expose XInclude controls.
        }
        setFeature(factory, "http://javax.xml.XMLConstants/feature/secure-processing", true);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setAttribute(factory, "http://javax.xml.XMLConstants/property/accessExternalDTD", "");
        setAttribute(factory, "http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver(new EntityResolver() {
            @Override
            public InputSource resolveEntity(String publicId, String systemId) {
                return new InputSource(new StringReader(""));
            }
        });
        builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        return builder.parse(new InputSource(new StringReader(wrapped)));
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
            // Best effort for Android DOM implementations; the declaration pre-scan still rejects DTDs.
        }
    }

    private static void setAttribute(DocumentBuilderFactory factory, String name, Object value) {
        try {
            factory.setAttribute(name, value);
        } catch (Exception ignored) {
            // Best effort for Android DOM implementations; the declaration pre-scan still rejects DTDs.
        }
    }

    private static void appendAttributes(StringBuilder output, LinkedHashMap<String, String> attributes,
            String screenValue, boolean replaceScreen) {
        boolean hasScreen = false;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (replaceScreen && "screen".equals(name)) {
                value = screenValue;
                hasScreen = true;
            }
            output.append(' ').append(name).append("=\"");
            appendEscapedAttribute(output, value);
            output.append('"');
        }
        if (replaceScreen && !hasScreen) {
            output.append(" screen=\"");
            appendEscapedAttribute(output, screenValue);
            output.append('"');
        }
    }

    private static void appendEscapedAttribute(StringBuilder output, String value) {
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (!isXml10CodePoint(codePoint)) {
                throw new IllegalArgumentException("Attribute contains a character not allowed in XML 1.0");
            }
            if (codePoint == '&') {
                output.append("&amp;");
            } else if (codePoint == '<') {
                output.append("&lt;");
            } else if (codePoint == '"') {
                output.append("&quot;");
            } else if (codePoint == '\t') {
                output.append("&#x9;");
            } else if (codePoint == '\n') {
                output.append("&#xA;");
            } else if (codePoint == '\r') {
                output.append("&#xD;");
            } else {
                output.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
    }

    private static boolean isXml10Text(String value) {
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (!isXml10CodePoint(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isXml10CodePoint(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xa || codePoint == 0xd
                || (codePoint >= 0x20 && codePoint <= 0xd7ff)
                || (codePoint >= 0xe000 && codePoint <= 0xfffd)
                || (codePoint >= 0x10000 && codePoint <= 0x10ffff);
    }
}
