package dev.local.oneuifolders.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import java.io.StringReader;

/** Standalone, dependency-free regression tests; run from the repository root. */
public final class ExmlEditorTest {
    private static int assertions;

    private ExmlEditorTest() { }

    public static void main(String[] args) throws Exception {
        testRepoFixtureCoverageAndProtectedBytes();
        testGeneratedScreenCapacitiesAndMetadata();
        testFailClosedInputs();
        testInvalidAssignments();
        System.out.println("PASS ExmlEditorTest: " + assertions + " assertions");
    }

    private static void testRepoFixtureCoverageAndProtectedBytes() throws Exception {
        byte[] source = Files.readAllBytes(Paths.get("examples/synthetic.exml"));
        ExmlEditor.Snapshot snapshot = ExmlEditor.parse(source);
        equal(6, snapshot.apps.size(), "synthetic fixture app count");
        equal(2, snapshot.folders.size(), "existing folder and unfiled group count");
        equal("가상 기존 폴더", new ArrayList<String>(snapshot.folders.keySet()).get(0),
                "source folder order");
        equal("미분류", new ArrayList<String>(snapshot.folders.keySet()).get(1),
                "unfiled group name");
        equal(2, snapshot.folders.get("가상 기존 폴더").size(), "existing folder member count");
        equal("example.fixture.alpha\texample.fixture.Alpha",
                snapshot.folders.get("가상 기존 폴더").get(0), "first source folder member order");
        equal("example.fixture.beta\texample.fixture.Beta",
                snapshot.folders.get("가상 기존 폴더").get(1), "second source folder member order");
        equal(4, snapshot.folders.get("미분류").size(), "unfiled member count");
        equal("example.fixture.gamma\texample.fixture.Gamma",
                snapshot.folders.get("미분류").get(0), "first unfiled member order");
        for (ExmlEditor.App app : snapshot.apps) {
            equal(app.packageName + "\t" + app.activity, app.key, "exact component key");
        }

        LinkedHashMap<String, List<String>> assignments = new LinkedHashMap<String, List<String>>();
        assignments.put("가상 테스트 폴더 A", keys(snapshot.apps, 0, 2));
        assignments.put("가상 테스트 폴더 B", keys(snapshot.apps, 2, 4));
        assignments.put("가상 테스트 폴더 C", keys(snapshot.apps, 4, 6));
        ExmlEditor.Result result = ExmlEditor.build(snapshot, assignments);
        equal(6, result.appCount, "built app count");
        equal(3, result.folderCount, "built folder count");
        equal(snapshot.sourceSha256, result.sourceSha256, "source hash");
        equal(ExmlEditor.sha256(result.bytes), result.outputSha256, "output hash matches bytes");
        equal(64, result.outputSha256.length(), "output SHA-256 length");
        check(result.protectedBytesIdentical, "protected bytes flag");

        String original = new String(source, StandardCharsets.UTF_8);
        String output = new String(result.bytes, StandardCharsets.UTF_8);
        int start = original.indexOf("<appOrder>");
        int end = original.indexOf("</appOrder>", start) + "</appOrder>".length();
        int outputStart = output.indexOf("<appOrder");
        int outputEnd = output.indexOf("</appOrder>", outputStart) + "</appOrder>".length();
        check(start >= 0 && end > start && outputStart >= 0 && outputEnd > outputStart,
                "source and output appOrder boundaries found");
        equal(original.substring(0, start), output.substring(0, outputStart), "protected prefix exact");
        equal(original.substring(end), output.substring(outputEnd), "protected suffix exact");
        equal(Integer.valueOf(1), folderScreenMap(output).get("가상 테스트 폴더 C"),
                "fixture appOrder grid gives third folder page one");
    }

    private static void testGeneratedScreenCapacitiesAndMetadata() throws Exception {
        String sourceText = generatedSource(12, 1, 2, "2X2", "fixture&amp;value");
        ExmlEditor.Snapshot snapshot = ExmlEditor.parse(sourceText.getBytes(StandardCharsets.UTF_8));
        LinkedHashMap<String, List<String>> assignments = new LinkedHashMap<String, List<String>>();
        assignments.put("도구 & 장치", keys(snapshot.apps, 0, 5));
        assignments.put("가상 폴더 B", keys(snapshot.apps, 5, 10));
        assignments.put("가상 폴더 C", keys(snapshot.apps, 10, 12));
        ExmlEditor.Result result = ExmlEditor.build(snapshot, assignments);
        String output = new String(result.bytes, StandardCharsets.UTF_8);
        LinkedHashMap<String, Integer> screens = folderScreenMap(output);
        equal(Integer.valueOf(0), screens.get("도구 & 장치"), "first folder screen");
        equal(Integer.valueOf(0), screens.get("가상 폴더 B"), "second folder screen");
        equal(Integer.valueOf(1), screens.get("가상 폴더 C"), "folder page capacity");
        LinkedHashMap<String, Integer> favoriteScreens = favoriteScreenMap(output);
        equal(Integer.valueOf(0), favoriteScreens.get("example.fixture.app0"), "first folder page 0");
        equal(Integer.valueOf(0), favoriteScreens.get("example.fixture.app3"), "fourth item page 0");
        equal(Integer.valueOf(1), favoriteScreens.get("example.fixture.app4"), "fifth item page 1");
        equal(Integer.valueOf(1), favoriteScreens.get("example.fixture.app9"), "second folder fifth item page 1");
        equal(Integer.valueOf(0), favoriteScreens.get("example.fixture.app10"), "third folder starts page 0");

        Document document = parseFragments(output);
        Element order = firstElement(document.getDocumentElement(), "appOrder");
        int found = 0;
        for (Node node = order.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && "folder".equals(((Element) node).getTagName())) {
                Element folder = (Element) node;
                equal("0", folder.getAttribute("options"), "new folder default options");
                equal("0", folder.getAttribute("color"), "new folder default color");
                found++;
                for (Node child = folder.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        Element favorite = (Element) child;
                        equal("fixture&value", favorite.getAttribute("sourceToken"),
                                "non-page source attribute preserved");
                        equal("fictional-icon-reference", favorite.getAttribute("iconRef"),
                                "source icon reference preserved without target lookup");
                    }
                }
            }
        }
        equal(3, found, "all destination folders emitted");
    }

    private static void testFailClosedInputs() throws Exception {
        byte[] valid = generatedSource(4, 1, 2, "2X2", "safe").getBytes(StandardCharsets.UTF_8);
        expectFailure(new byte[0], "empty file rejected");
        expectFailure(new byte[] {(byte) 0xff}, "invalid UTF-8 rejected");
        expectFailure(new byte[8 * 1024 * 1024 + 1], "oversized file rejected");
        expectFailure("<broken".getBytes(StandardCharsets.UTF_8), "malformed XML rejected");
        expectFailure(concat("<!DOCTYPE x [<!ENTITY leak 'nope'>]>\n",
                new String(valid, StandardCharsets.UTF_8)), "DTD/entity declaration rejected");
        String duplicateComponent = replaceOnce(new String(valid, StandardCharsets.UTF_8),
                "packageName=\"example.fixture.app1\"",
                "packageName=\"example.fixture.app0\"");
        duplicateComponent = replaceOnce(duplicateComponent,
                "className=\"example.fixture.Activity1\"",
                "className=\"example.fixture.Activity0\"");
        expectFailure(duplicateComponent, "duplicate app component rejected");
        expectFailure(concat("<appOrder/>\n", new String(valid, StandardCharsets.UTF_8)),
                "duplicate appOrder marker rejected");
        expectFailure(replaceOnce(new String(valid, StandardCharsets.UTF_8),
                "<FolderGrid>2X2</FolderGrid>", "<FolderGrid>bad</FolderGrid>"),
                "malformed grid rejected");
        expectFailure(replaceOnce(new String(valid, StandardCharsets.UTF_8),
                "<FolderGrid>2X2</FolderGrid>", ""), "absent grid rejected");
        expectFailure(replaceOnce(new String(valid, StandardCharsets.UTF_8),
                "<viewType_appOrder>CUSTOM</viewType_appOrder>",
                "<viewType_appOrder>ALPHABETICAL</viewType_appOrder>"),
                "non-CUSTOM source rejected");
        expectFailure(new String(valid, StandardCharsets.UTF_8)
                .replace("hidden=\"0\"", "hidden=\"1\""), "hidden favorite rejected");
        expectFailure(replaceOnce(new String(valid, StandardCharsets.UTF_8),
                "<appOrder>\n", "<appOrder>\n  <widget />\n"), "unsupported item rejected");
        expectFailure(replaceOnce(new String(valid, StandardCharsets.UTF_8),
                "<appOrder>", "<appOrder data=\"1\"><appOrder>"),
                "nested duplicate appOrder rejected");
    }

    private static void testInvalidAssignments() throws Exception {
        ExmlEditor.Snapshot snapshot = ExmlEditor.parse(
                generatedSource(4, 1, 2, "2X2", "safe").getBytes(StandardCharsets.UTF_8));
        LinkedHashMap<String, List<String>> valid = new LinkedHashMap<String, List<String>>();
        valid.put("valid", keys(snapshot.apps, 0, 4));

        LinkedHashMap<String, List<String>> missing = new LinkedHashMap<String, List<String>>();
        missing.put("missing", keys(snapshot.apps, 0, 3));
        expectBuildFailure(snapshot, missing, "incomplete coverage rejected");

        LinkedHashMap<String, List<String>> repeated = new LinkedHashMap<String, List<String>>();
        repeated.put("repeated", Arrays.asList(snapshot.apps.get(0).key, snapshot.apps.get(1).key,
                snapshot.apps.get(1).key, snapshot.apps.get(3).key));
        expectBuildFailure(snapshot, repeated, "duplicate app assignment rejected");

        LinkedHashMap<String, List<String>> tooSmall = new LinkedHashMap<String, List<String>>();
        tooSmall.put("one app", Arrays.asList(snapshot.apps.get(0).key));
        expectBuildFailure(snapshot, tooSmall, "one-app folder rejected");

        LinkedHashMap<String, List<String>> unknown = new LinkedHashMap<String, List<String>>();
        unknown.put("unknown", Arrays.asList("not-a-source-key", snapshot.apps.get(0).key));
        unknown.put("rest", keys(snapshot.apps, 1, 4));
        expectBuildFailure(snapshot, unknown, "unknown app key rejected");

        LinkedHashMap<String, List<String>> emptyName = new LinkedHashMap<String, List<String>>();
        emptyName.put(" \t", keys(snapshot.apps, 0, 4));
        expectBuildFailure(snapshot, emptyName, "blank folder name rejected");

        equal(4, ExmlEditor.build(snapshot, valid).appCount, "valid complete assignment accepted");
    }

    private static String generatedSource(int count, int rows, int columns, String folderGrid,
            String sourceToken) {
        StringBuilder xml = new StringBuilder();
        xml.append("<viewType_appOrder>CUSTOM</viewType_appOrder>\n")
                .append("<Rows_appOrder>").append(rows).append("</Rows_appOrder>\n")
                .append("<Columns_appOrder>").append(columns).append("</Columns_appOrder>\n")
                .append("<FolderGrid>").append(folderGrid).append("</FolderGrid>\n")
                .append("<protected_fixture label=\"bytes outside appOrder\" />\n")
                .append("<appOrder>\n");
        for (int i = 0; i < count; i++) {
            xml.append("  <favorite screen=\"99\" packageName=\"example.fixture.app")
                    .append(i).append("\" className=\"example.fixture.Activity")
                    .append(i).append("\" hidden=\"0\" sourceToken=\"")
                    .append(sourceToken).append("\" iconRef=\"fictional-icon-reference\" />\n");
        }
        return xml.append("</appOrder>\n<protected_suffix value=\"unchanged\" />\n").toString();
    }

    private static List<String> keys(List<ExmlEditor.App> apps, int start, int end) {
        List<String> result = new ArrayList<String>();
        for (int i = start; i < end; i++) {
            result.add(apps.get(i).key);
        }
        return result;
    }

    private static LinkedHashMap<String, Integer> folderScreenMap(String fragments) throws Exception {
        Document document = parseFragments(fragments);
        Element order = firstElement(document.getDocumentElement(), "appOrder");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (Node node = order.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && "folder".equals(((Element) node).getTagName())) {
                Element folder = (Element) node;
                result.put(folder.getAttribute("title"), Integer.valueOf(folder.getAttribute("screen")));
            }
        }
        return result;
    }

    private static LinkedHashMap<String, Integer> favoriteScreenMap(String fragments) throws Exception {
        Document document = parseFragments(fragments);
        Element order = firstElement(document.getDocumentElement(), "appOrder");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (Node folderNode = order.getFirstChild(); folderNode != null; folderNode = folderNode.getNextSibling()) {
            if (folderNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element folder = (Element) folderNode;
            for (Node child = folder.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    Element favorite = (Element) child;
                    result.put(favorite.getAttribute("packageName"),
                            Integer.valueOf(favorite.getAttribute("screen")));
                }
            }
        }
        return result;
    }

    private static Document parseFragments(String fragments) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        String body = fragments.replace("<?xml version=\"1.0\" encoding=\"utf-8\"?>", "");
        return factory.newDocumentBuilder().parse(new InputSource(
                new StringReader("<test_root>" + body + "</test_root>")));
    }

    private static Element firstElement(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tag.equals(((Element) node).getTagName())) {
                return (Element) node;
            }
        }
        throw new AssertionError("Missing element " + tag);
    }

    private static void expectFailure(byte[] input, String message) throws Exception {
        try {
            ExmlEditor.parse(input);
            throw new AssertionError(message + " (accepted)");
        } catch (AssertionError error) {
            throw error;
        } catch (Exception expected) {
            assertions++;
        }
    }

    private static void expectFailure(String input, String message) throws Exception {
        expectFailure(input.getBytes(StandardCharsets.UTF_8), message);
    }

    private static void expectBuildFailure(ExmlEditor.Snapshot snapshot,
            LinkedHashMap<String, List<String>> folders, String message) throws Exception {
        try {
            ExmlEditor.build(snapshot, folders);
            throw new AssertionError(message + " (accepted)");
        } catch (AssertionError error) {
            throw error;
        } catch (Exception expected) {
            assertions++;
        }
    }

    private static String concat(String first, String second) {
        return first + second;
    }

    private static String replaceOnce(String value, String before, String after) {
        int index = value.indexOf(before);
        if (index < 0 || value.indexOf(before, index + before.length()) >= 0) {
            throw new AssertionError("Expected one occurrence of test fixture text: " + before);
        }
        return value.substring(0, index) + after + value.substring(index + before.length());
    }

    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
