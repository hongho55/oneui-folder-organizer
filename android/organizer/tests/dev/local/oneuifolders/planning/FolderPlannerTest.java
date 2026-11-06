package dev.local.oneuifolders.planning;

import dev.local.oneuifolders.core.ExmlEditor;
import dev.local.oneuifolders.planning.FolderPlanner.AppInfo;
import dev.local.oneuifolders.planning.FolderPlanner.Proposal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Plain JDK main tests. Run from the repository root; uses synthetic data only. */
public final class FolderPlannerTest {
    private static int assertions;
    private FolderPlannerTest() { }

    public static void main(String[] args) throws Exception {
        invalidData();
        classification();
        genericPurposeRules();
        modesAndCopies();
        tinyGroups();
        editing();
        coverageSweep();
        exmlCompatibility();
        System.out.println("PASS FolderPlannerTest: " + assertions + " assertions");
    }

    private static void invalidData() throws Exception {
        fails(() -> new AppInfo(null, "example.a", "a", -1));
        fails(() -> new AppInfo("example.a\tA", null, "a", -1));
        fails(() -> new AppInfo("example.a\tA", "example.a", null, -1));
        fails(() -> new AppInfo("example.a\tA", "example.a", "a", -2));
        for (String key : Arrays.asList("example.a", "example.b\tA", "example.a\t", "example.a\t \tA")) {
            fails(() -> new AppInfo(key, "example.a", "a", -1));
        }
        AppInfo a = app("a", -1), b = app("b", -1);
        List<AppInfo> apps = Arrays.asList(a, b);
        LinkedHashMap<String, List<String>> valid = plan("미분류", a.key, b.key);
        FolderPlanner.validate(apps, valid);
        fails(() -> FolderPlanner.validate(null, valid));
        fails(() -> FolderPlanner.validate(Arrays.asList(a, null), valid));
        fails(() -> FolderPlanner.validate(Arrays.asList(a, a), valid));
        fails(() -> FolderPlanner.validate(apps, null));
        fails(() -> FolderPlanner.validate(apps, plan("x", a.key)));
        fails(() -> FolderPlanner.validate(apps, plan("x", a.key, b.key, "unknown")));
        fails(() -> FolderPlanner.validate(apps, plan("x", a.key, b.key, a.key)));
        LinkedHashMap<String, List<String>> across = plan("x", a.key, b.key);
        across.put("y", Arrays.asList(b.key));
        fails(() -> FolderPlanner.validate(apps, across));
        for (String name : Arrays.asList(null, "", " \t", "\u2003", "전체", " 전체 ", "bad\u0001", "bad\ud800")) {
            fails(() -> FolderPlanner.validate(apps, plan(name, a.key, b.key)));
        }
        fails(() -> FolderPlanner.copy(null));
        fails(() -> FolderPlanner.copy(plan("x", (String) null)));
        fails(() -> FolderPlanner.copy(plan("x", "")));
        fails(() -> FolderPlanner.copy(plan("x")));
        LinkedHashMap<String, List<String>> nullList = new LinkedHashMap<String, List<String>>();
        nullList.put("x", null);
        fails(() -> FolderPlanner.copy(nullList));
        FolderPlanner.validate(Collections.<AppInfo>emptyList(), new LinkedHashMap<String, List<String>>());
        equal(0, FolderPlanner.suggest(Collections.<AppInfo>emptyList(),
                new LinkedHashMap<String, List<String>>(), true).folders.size(), "empty inventory");
    }

    private static void classification() {
        List<AppInfo> apps = new ArrayList<AppInfo>();
        for (int category = 0; category <= 8; category++) {
            apps.add(new AppInfo("example.video.c" + category + "\t.A", "example.video.c" + category,
                    "Music", category));
            apps.add(new AppInfo("example.video.d" + category + "\t.A", "example.video.d" + category,
                    "Music", category));
        }
        apps.add(new AppInfo("example.camera.one\t.A", "example.camera.one", "Music", -1));
        apps.add(new AppInfo("example.camera.two\t.A", "example.camera.two", "Music", -1));
        apps.add(new AppInfo("example.opaque.one\t.A", "example.opaque.one", "Music Player", -1));
        apps.add(new AppInfo("example.opaque.two\t.A", "example.opaque.two", "음악", 99));
        apps.add(new AppInfo("example.newspaper\t.A", "example.newspaper", "Unknown", -1));
        apps.add(new AppInfo("example.xyz\t.A", "example.xyz", "Unknown", -1));
        Proposal p = FolderPlanner.suggest(apps, unfiled(apps), true);
        String[] names = {"게임", "음악·오디오", "동영상", "사진", "소셜", "뉴스", "지도·교통", "생산성", "접근성"};
        for (int i = 0; i < 18; i++) {
            check(p.folders.get(names[i / 2]).contains(apps.get(i).key), "category wins over package and label");
            check(!p.uncertainKeys.contains(apps.get(i).key), "known category avoids heuristic uncertainty");
            check(p.reasons.get(apps.get(i).key).contains("카테고리"), "category reason");
        }
        check(p.folders.get("사진").contains(apps.get(18).key), "package before label");
        check(p.reasons.get(apps.get(18).key).contains("패키지"), "package reason");
        check(p.folders.get("음악·오디오").contains(apps.get(20).key), "label fallback");
        check(p.reasons.get(apps.get(20).key).contains("이름"), "label reason");
        check(p.folders.get("음악·오디오").contains(apps.get(21).key), "future category uses rules");
        check(p.folders.get("검토 필요").contains(apps.get(22).key), "no substring false match");
        equal(6, p.uncertainKeys.size(), "heuristics and unknowns marked");
        equal(apps.size(), p.reasons.size(), "one honest explanation per app");
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            equal(p.folders, FolderPlanner.suggest(apps, unfiled(apps), true).folders, "locale independent");
        } finally {
            Locale.setDefault(before);
        }
    }

    private static void genericPurposeRules() {
        String[][] rules = {{"bank", "금융·결제"}, {"쇼핑", "쇼핑·배달"},
                {"fitness", "건강·운동"}, {"여행", "여행·예약"},
                {"education", "학습·독서"}, {"chatgpt", "AI·도구"}, {"vpn", "도구·설정"}};
        List<AppInfo> apps = new ArrayList<AppInfo>();
        for (int i=0;i<rules.length;i++) for(int n=0;n<2;n++) {
            String p="example.fixture.purpose"+i+".app"+n;
            apps.add(new AppInfo(p+"\t.Main",p,"가상 "+rules[i][0]+" "+n,-1));
        }
        Proposal result=FolderPlanner.suggest(apps,unfiled(apps),true);
        for(int i=0;i<rules.length;i++) for(int n=0;n<2;n++) {
            String key=apps.get(i*2+n).key;
            check(result.folders.get(rules[i][1]).contains(key),"generic purpose token: "+rules[i][0]);
            check(result.uncertainKeys.contains(key),"heuristic purpose remains explicitly uncertain");
        }
        equal(0,result.singletonCount,"paired generic-purpose fixture is exportable");
        List<AppInfo> unrelated=Arrays.asList(new AppInfo("example.fixture.bankruptcy\t.Main","example.fixture.bankruptcy","Airplane",-1),new AppInfo("example.fixture.shoppingcart\t.Main","example.fixture.shoppingcart","Plain",-1));
        Proposal unknown=FolderPlanner.suggest(unrelated,unfiled(unrelated),true);
        equal(2,unknown.folders.get("검토 필요").size(),"no substring overmatching for purpose rules");
    }

    private static void modesAndCopies() throws Exception {
        AppInfo a = app("a", 0), b = app("b", 0), c = app("c", 0), d = app("d", 0);
        List<AppInfo> apps = Collections.unmodifiableList(Arrays.asList(a, b, c, d));
        LinkedHashMap<String, List<String>> current = plan("게임", b.key, a.key);
        current.put("게임 (2)", Arrays.asList(d.key));
        current.put("미분류", Collections.singletonList(c.key));
        LinkedHashMap<String, List<String>> before = FolderPlanner.copy(current);
        Proposal preserve = FolderPlanner.suggest(apps, current, false);
        equal(Arrays.asList("게임", "게임 (2)", "게임 (3)"), names(preserve.folders), "manual name collision isolated");
        equal(Arrays.asList(b.key, a.key), preserve.folders.get("게임"), "manual order untouched");
        equal(Arrays.asList(d.key), preserve.folders.get("게임 (2)"), "manual singleton untouched");
        equal(1, preserve.movedCount, "preserve moves only unfiled");
        equal(2, preserve.singletonCount, "manual and generated singleton counted");
        equal(before, current, "suggest never changes input");
        Proposal all = FolderPlanner.suggest(apps, current, true);
        equal(Arrays.asList(a.key, b.key, c.key, d.key), all.folders.get("게임"), "all mode inventory order");
        equal(2, all.movedCount, "counts folder change, not member reorder");
        equal(0, all.singletonCount, "regroup removes singletons");
        preserve.folders.get("게임").clear();
        preserve.folders.clear();
        equal(before, current, "proposal mutation independent of input");
        equal(4, all.folders.get("게임").size(), "proposals independent");
        LinkedHashMap<String, List<String>> copied = FolderPlanner.copy(current);
        copied.get("게임").add("extra");
        copied.remove("미분류");
        equal(before, current, "copy deep and ordered");
        current.get("게임").clear();
        equal(4, all.folders.get("게임").size(), "later input mutation independent");
        fails(() -> all.reasons.clear());
        fails(() -> all.uncertainKeys.add("extra"));
        LinkedHashMap<String, List<String>> manual = plan("수동", a.key, b.key);
        Proposal noUnfiled = FolderPlanner.suggest(Arrays.asList(a, b), manual, false);
        equal(manual, noUnfiled.folders, "opening manual plan does not classify");
        equal(0, noUnfiled.movedCount, "no eligible apps means no moves");
        equal(0, noUnfiled.uncertainKeys.size(), "preserved apps not classified");
    }

    private static void tinyGroups() {
        List<AppInfo> apps = Arrays.asList(app("a", 0), app("b", 1), app("c", -1));
        Proposal p = FolderPlanner.suggest(apps, unfiled(apps), true);
        equal(Arrays.asList("검토 필요"), names(p.folders), "tiny generated groups pooled with unknowns");
        equal(0, p.singletonCount, "no avoidable singleton");
        equal(3, p.uncertainKeys.size(), "mixed tiny groups require review");
        equal(unfiled(apps).get("미분류"), p.folders.get("검토 필요"), "pool keeps inventory order");
        List<AppInfo> one = Arrays.asList(app("solo", 0));
        Proposal solo = FolderPlanner.suggest(one, unfiled(one), true);
        equal(1, solo.singletonCount, "unavoidable singleton visible");
        equal(one.get(0).key, solo.uncertainKeys.get(0), "singleton needs review");
        check(solo.reasons.get(one.get(0).key).contains("단독"), "singleton reason");
        List<AppInfo> different = Arrays.asList(app("x", 0), app("y", 0), app("z", 1));
        Proposal distinct = FolderPlanner.suggest(different, unfiled(different), true);
        equal(1, distinct.singletonCount, "do not misclassify lone audio as game just to pass output");
        equal(3, distinct.movedCount, "all components retained");
        Proposal two = FolderPlanner.suggest(apps.subList(0, 2), unfiled(apps.subList(0, 2)), true);
        equal(0, two.singletonCount, "two tiny known groups can form review group");
    }

    private static void editing() throws Exception {
        LinkedHashMap<String, List<String>> original = plan("A", "a", "b", "c");
        original.put("B", Arrays.asList("d", "e"));
        original.put("C", Arrays.asList("f", "g"));
        LinkedHashMap<String, List<String>> before = FolderPlanner.copy(original);
        LinkedHashMap<String, List<String>> moved = FolderPlanner.move(original, Arrays.asList("g", "b", "d", "b"), "B");
        equal(Arrays.asList("A", "B", "C"), names(moved), "existing target position");
        equal(Arrays.asList("a", "c"), moved.get("A"), "source survivors order");
        equal(Arrays.asList("d", "e", "b", "g"), moved.get("B"), "existing members then source traversal order");
        equal(Arrays.asList("f"), moved.get("C"), "unrelated member retained");
        LinkedHashMap<String, List<String>> noop = FolderPlanner.move(original, Arrays.asList("c", "a"), "A");
        equal(original, noop, "same target no-op preserves order");
        noop.get("A").clear();
        equal(before, original, "no-op still independent");
        equal(original, FolderPlanner.move(original, Collections.<String>emptyList(), "New"), "empty selection creates no folder");
        LinkedHashMap<String, List<String>> newFolder = FolderPlanner.move(original, Arrays.asList("c", "b", "a"), "New");
        equal(Arrays.asList("B", "C", "New"), names(newFolder), "remove empty source, append new target");
        equal(Arrays.asList("a", "b", "c"), newFolder.get("New"), "relative order on new target");
        fails(() -> FolderPlanner.move(original, Arrays.asList("a", "missing"), "B"));
        fails(() -> FolderPlanner.move(original, Arrays.asList((String) null), "B"));
        fails(() -> FolderPlanner.move(original, null, "B"));
        fails(() -> FolderPlanner.move(original, Arrays.asList("a"), "전체"));
        LinkedHashMap<String, List<String>> renamed = FolderPlanner.rename(original, "B", " 이름 & 😀 ");
        equal(Arrays.asList("A", " 이름 & 😀 ", "C"), names(renamed), "rename preserves position and exact name");
        equal(original.get("B"), renamed.get(" 이름 & 😀 "), "rename member order");
        for (String name : Arrays.asList("A", "B", "", "전체", "\u0001")) {
            fails(() -> FolderPlanner.rename(original, "B", name));
        }
        fails(() -> FolderPlanner.rename(original, "missing", "New"));
        LinkedHashMap<String, List<String>> merged = FolderPlanner.merge(original, "A", "C");
        equal(Arrays.asList("B", "C"), names(merged), "merge target retains position");
        equal(Arrays.asList("f", "g", "a", "b", "c"), merged.get("C"), "merge appends source");
        equal(Arrays.asList("a", "b", "c", "f", "g"), FolderPlanner.merge(original, "C", "A").get("A"), "reverse merge");
        fails(() -> FolderPlanner.merge(original, "A", "A"));
        fails(() -> FolderPlanner.merge(original, "A", "missing"));
        fails(() -> FolderPlanner.merge(original, "missing", "A"));
        fails(() -> FolderPlanner.merge(original, "A", "전체"));
        equal(before, original, "editing and rejected operations never mutate input");
        merged.get("B").clear();
        renamed.get("A").clear();
        equal(before, original, "unrelated lists also deep copied");
    }

    private static void coverageSweep() {
        for (int size = 0; size <= 48; size++) {
            List<AppInfo> apps = new ArrayList<AppInfo>();
            LinkedHashMap<String, List<String>> current = new LinkedHashMap<String, List<String>>();
            for (int i = 0; i < size; i++) {
                // Same package, distinct exact activity spelling; no normalization permitted.
                AppInfo app = new AppInfo("example.multi\t" + (i % 2 == 0 ? ".Activity" : "example.multi.Activity") + i,
                        "example.multi", "", i % 11 - 1);
                apps.add(app);
                String name = i % 3 == 0 ? "미분류" : "수동 " + i % 3;
                if (!current.containsKey(name)) current.put(name, new ArrayList<String>());
                current.get(name).add(app.key);
            }
            for (boolean all : Arrays.asList(false, true)) {
                Proposal first = FolderPlanner.suggest(apps, current, all);
                Proposal second = FolderPlanner.suggest(apps, current, all);
                FolderPlanner.validate(apps, first.folders);
                equal(first.folders, second.folders, "repeatable coverage sweep");
                equal(names(first.folders), names(second.folders), "repeatable folder order");
                equal(first.reasons, second.reasons, "repeatable explanations");
                equal(first.uncertainKeys, second.uncertainKeys, "repeatable uncertainty order");
                int count = 0, moved = 0, singles = 0;
                for (Map.Entry<String, List<String>> entry : first.folders.entrySet()) {
                    if (entry.getValue().size() == 1) singles++;
                    for (String key : entry.getValue()) {
                        count++;
                        if (!current.containsKey(entry.getKey()) || !current.get(entry.getKey()).contains(key)) moved++;
                    }
                }
                equal(size, count, "exact coverage including multiple activities");
                equal(moved, first.movedCount, "independently computed moved count");
                equal(singles, first.singletonCount, "independently computed singleton count");
            }
        }
    }

    private static void exmlCompatibility() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("examples/synthetic.exml"));
        byte[] before = bytes.clone();
        ExmlEditor.Snapshot source = ExmlEditor.parse(bytes);
        List<AppInfo> apps = new ArrayList<AppInfo>();
        for (ExmlEditor.App app : source.apps) apps.add(new AppInfo(app.key, app.packageName, "", -1));
        LinkedHashMap<String, List<String>> original = FolderPlanner.copy(source.folders);
        for (boolean all : Arrays.asList(false, true)) {
            Proposal p = FolderPlanner.suggest(apps, source.folders, all);
            equal(0, p.singletonCount, "fixture proposal ready for EXML");
            ExmlEditor.Result result = ExmlEditor.build(source, p.folders);
            check(result.protectedBytesIdentical, "protected EXML bytes unchanged");
            equal(source.sourceSha256, result.sourceSha256, "source digest preserved");
            ExmlEditor.Snapshot output = ExmlEditor.parse(result.bytes);
            equal(p.folders, output.folders, "EXML round trip exact order and keys");
            FolderPlanner.validate(apps, output.folders);
        }
        LinkedHashMap<String, List<String>> edited = FolderPlanner.move(original,
                original.get("미분류").subList(0, 2), "새 폴더 & 😀");
        edited = FolderPlanner.rename(edited, "새 폴더 & 😀", "이름 변경 & 😀");
        edited = FolderPlanner.merge(edited, "미분류", "이름 변경 & 😀");
        equal(edited, ExmlEditor.parse(ExmlEditor.build(source, edited).bytes).folders, "move rename merge EXML compatible");
        LinkedHashMap<String, List<String>> singleton = FolderPlanner.move(original,
                Arrays.asList(source.apps.get(0).key), "단독");
        FolderPlanner.validate(apps, singleton);
        fails(() -> ExmlEditor.build(source, singleton));
        equal(original, source.folders, "source plan unchanged");
        check(Arrays.equals(before, bytes), "source bytes unchanged");
    }

    private static AppInfo app(String id, int category) {
        return new AppInfo("example." + id + "\t.Activity", "example." + id, "", category);
    }

    private static LinkedHashMap<String, List<String>> unfiled(List<AppInfo> apps) {
        List<String> keys = new ArrayList<String>();
        for (AppInfo app : apps) keys.add(app.key);
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        if (!keys.isEmpty()) result.put("미분류", keys);
        return result;
    }

    private static LinkedHashMap<String, List<String>> plan(String name, String... keys) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        result.put(name, new ArrayList<String>(Arrays.asList(keys)));
        return result;
    }

    private static List<String> names(Map<String, List<String>> plan) {
        return new ArrayList<String>(plan.keySet());
    }

    private interface Action { void run() throws Exception; }
    private static void fails(Action action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException | UnsupportedOperationException expected) {
            assertions++;
            return;
        }
        throw new AssertionError("Expected rejected operation");
    }

    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError(message + ": " + expected + " != " + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
