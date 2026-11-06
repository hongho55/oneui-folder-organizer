package dev.local.oneuifolders.planning;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic offline recommendations, not verified app purposes or AI inference. */
public final class FolderPlanner {
    private static final String UNFILED = "미분류";
    private static final String REVIEW = "검토 필요";
    private static final String[] CATEGORIES = {
        "게임", "음악·오디오", "동영상", "사진", "소셜", "뉴스", "지도·교통", "생산성", "접근성"
    };
    private static final String[][] RULES = {
        {"게임", "game", "games", "gaming", "게임"},
        {"음악·오디오", "music", "audio", "podcast", "음악", "오디오"},
        {"동영상", "video", "movie", "동영상", "영화"},
        {"사진", "photo", "photos", "camera", "gallery", "사진", "카메라", "갤러리"},
        {"소셜", "social", "chat", "messenger", "채팅", "메신저"},
        {"뉴스", "news", "뉴스"},
        {"지도·교통", "maps", "map", "navigation", "transit", "지도", "교통"},
        {"생산성", "calendar", "notes", "calculator", "office", "캘린더", "메모", "계산기"}
    };

    private FolderPlanner() { }

    public static final class AppInfo {
        public final String key;
        public final String packageName;
        public final String label;
        public final int category;

        public AppInfo(String key, String packageName, String label, int category) {
            if (blank(packageName) || label == null || key == null || category < -1) {
                throw new IllegalArgumentException("App metadata is required");
            }
            int tab = key.indexOf('\t');
            if (tab <= 0 || tab != key.lastIndexOf('\t')
                    || !key.substring(0, tab).equals(packageName) || blank(key.substring(tab + 1))) {
                throw new IllegalArgumentException("Expected exact package TAB activity key");
            }
            this.key = key;
            this.packageName = packageName;
            this.label = label;
            this.category = category;
        }
    }

    /** Independent draft plan. Singleton folders remain subject to EXML output validation. */
    public static final class Proposal {
        public final LinkedHashMap<String, List<String>> folders;
        public final Map<String, String> reasons;
        public final List<String> uncertainKeys;
        public final int movedCount;
        public final int singletonCount;

        private Proposal(LinkedHashMap<String, List<String>> folders,
                Map<String, String> reasons, List<String> uncertainKeys, int movedCount) {
            this.folders = copy(folders);
            this.reasons = Collections.unmodifiableMap(new LinkedHashMap<String, String>(reasons));
            this.uncertainKeys = Collections.unmodifiableList(new ArrayList<String>(uncertainKeys));
            this.movedCount = movedCount;
            int singletons = 0;
            for (List<String> members : folders.values()) {
                if (members.size() == 1) singletons++;
            }
            this.singletonCount = singletons;
        }
    }

    /** Category data wins over generic whole-token package rules, then label rules. */
    public static Proposal suggest(List<AppInfo> apps,
            LinkedHashMap<String, List<String>> current, boolean regroupAll) {
        validate(apps, current);
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        Map<String, String> original = destinations(current);
        Map<String, String> reasons = new LinkedHashMap<String, String>();
        Set<String> uncertain = new HashSet<String>();
        Set<String> eligible = new HashSet<String>();
        for (Map.Entry<String, List<String>> entry : current.entrySet()) {
            if (regroupAll || UNFILED.equals(entry.getKey())) {
                eligible.addAll(entry.getValue());
            } else {
                result.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
            }
        }
        LinkedHashMap<String, List<String>> generated = new LinkedHashMap<String, List<String>>();
        for (AppInfo app : apps) {
            if (!eligible.contains(app.key)) {
                reasons.put(app.key, "기존 폴더 유지 (미분류만 추천)");
                continue;
            }
            String group;
            String reason;
            if (app.category >= 0 && app.category < CATEGORIES.length) {
                group = CATEGORIES[app.category];
                reason = "Android 카테고리 기반 오프라인 추천: " + group;
            } else {
                group = rule(app.packageName);
                String basis = "패키지 토큰";
                if (group == null) {
                    group = rule(app.label);
                    basis = "이름 토큰";
                }
                uncertain.add(app.key);
                if (group == null) {
                    group = REVIEW;
                    reason = "일치하는 오프라인 규칙 없음; 직접 검토 필요";
                } else {
                    reason = basis + " 기반 일반 규칙 추천: " + group + "; 실제 용도 확인 필요";
                }
            }
            if (!generated.containsKey(group)) generated.put(group, new ArrayList<String>());
            generated.get(group).add(app.key);
            reasons.put(app.key, reason);
        }

        // Pool only generated tiny groups; never borrow members from a manual folder.
        Set<String> pooled = new HashSet<String>();
        int reviewSize = generated.containsKey(REVIEW) ? generated.get(REVIEW).size() : 0;
        int tinySize = 0;
        for (Map.Entry<String, List<String>> entry : generated.entrySet()) {
            if (!REVIEW.equals(entry.getKey()) && entry.getValue().size() == 1) tinySize++;
        }
        if (tinySize > 0 && tinySize + reviewSize >= 2) {
            for (String name : new ArrayList<String>(generated.keySet())) {
                if (REVIEW.equals(name) || generated.get(name).size() == 1) {
                    pooled.addAll(generated.remove(name));
                }
            }
            List<String> review = new ArrayList<String>();
            for (AppInfo app : apps) {
                if (pooled.contains(app.key)) {
                    review.add(app.key);
                    uncertain.add(app.key);
                    reasons.put(app.key, reasons.get(app.key)
                            + "; 작은 추천 그룹을 검토 폴더에 모음 (용도 혼합 가능)");
                }
            }
            generated.put(REVIEW, review);
        }
        for (Map.Entry<String, List<String>> entry : generated.entrySet()) {
            String name = availableName(entry.getKey(), result);
            result.put(name, entry.getValue());
            for (String key : entry.getValue()) {
                if (!name.equals(entry.getKey())) {
                    reasons.put(key, reasons.get(key) + "; 기존 수동 폴더와 구분: " + name);
                }
                if (entry.getValue().size() == 1) {
                    uncertain.add(key);
                    reasons.put(key, reasons.get(key) + "; 단독 앱: 출력 전 폴더 합치기 필요");
                }
            }
        }
        Map<String, String> after = destinations(result);
        int moved = 0;
        List<String> uncertainKeys = new ArrayList<String>();
        for (AppInfo app : apps) {
            if (!original.get(app.key).equals(after.get(app.key))) moved++;
            if (uncertain.contains(app.key)) uncertainKeys.add(app.key);
        }
        validate(apps, result);
        return new Proposal(result, reasons, uncertainKeys, moved);
    }

    public static LinkedHashMap<String, List<String>> copy(Map<String, List<String>> plan) {
        checkPlan(plan);
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : plan.entrySet()) {
            result.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
        return result;
    }

    /** Source traversal order wins over selection order; existing target members stay in place. */
    public static LinkedHashMap<String, List<String>> move(LinkedHashMap<String, List<String>> current,
            Collection<String> keys, String target) {
        checkName(target);
        LinkedHashMap<String, List<String>> result = copy(current);
        if (keys == null) throw new IllegalArgumentException("Selection is required");
        Set<String> selected = new LinkedHashSet<String>(keys);
        if (!destinations(current).keySet().containsAll(selected)) {
            throw new IllegalArgumentException("Selection contains an unknown key");
        }
        List<String> incoming = new ArrayList<String>();
        for (Map.Entry<String, List<String>> entry : result.entrySet()) {
            if (entry.getKey().equals(target)) continue;
            List<String> remaining = new ArrayList<String>();
            for (String key : entry.getValue()) {
                if (selected.contains(key)) incoming.add(key);
                else remaining.add(key);
            }
            entry.setValue(remaining);
        }
        if (!incoming.isEmpty()) {
            if (!result.containsKey(target)) result.put(target, new ArrayList<String>());
            result.get(target).addAll(incoming);
        }
        result.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return result;
    }

    public static LinkedHashMap<String, List<String>> rename(
            LinkedHashMap<String, List<String>> current, String from, String to) {
        checkPlan(current);
        checkName(from);
        checkName(to);
        if (!current.containsKey(from) || current.containsKey(to)) {
            throw new IllegalArgumentException("Source required; destination must be a new name");
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : current.entrySet()) {
            result.put(entry.getKey().equals(from) ? to : entry.getKey(),
                    new ArrayList<String>(entry.getValue()));
        }
        return result;
    }

    public static LinkedHashMap<String, List<String>> merge(
            LinkedHashMap<String, List<String>> current, String from, String into) {
        checkPlan(current);
        checkName(from);
        checkName(into);
        if (from.equals(into) || !current.containsKey(from) || !current.containsKey(into)) {
            throw new IllegalArgumentException("Two different existing folders are required");
        }
        return move(current, current.get(from), into);
    }

    public static void validate(List<AppInfo> apps, Map<String, List<String>> plan) {
        if (apps == null) throw new IllegalArgumentException("Apps are required");
        Set<String> expected = new HashSet<String>();
        for (AppInfo app : apps) {
            if (app == null || !expected.add(app.key)) {
                throw new IllegalArgumentException("Null or duplicate app component");
            }
        }
        checkPlan(plan);
        if (!expected.equals(destinations(plan).keySet())) {
            throw new IllegalArgumentException("Plan must cover every exact component once");
        }
    }

    private static void checkPlan(Map<String, List<String>> plan) {
        if (plan == null) throw new IllegalArgumentException("Plan is required");
        Set<String> seen = new HashSet<String>();
        for (Map.Entry<String, List<String>> entry : plan.entrySet()) {
            checkName(entry.getKey());
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                throw new IllegalArgumentException("Empty folders are not allowed");
            }
            for (String key : entry.getValue()) {
                if (blank(key) || !seen.add(key)) {
                    throw new IllegalArgumentException("Null, blank or duplicate component key");
                }
            }
        }
    }

    private static void checkName(String name) {
        if (blank(name) || "전체".equals(name.trim())) {
            throw new IllegalArgumentException("Nonblank, nonreserved folder name required");
        }
        for (int i = 0; i < name.length();) {
            int cp = name.codePointAt(i);
            if (!(cp == 9 || cp == 10 || cp == 13 || (cp >= 0x20 && cp <= 0xd7ff)
                    || (cp >= 0xe000 && cp <= 0xfffd) || (cp >= 0x10000 && cp <= 0x10ffff))) {
                throw new IllegalArgumentException("Folder name must be XML 1.0 text");
            }
            i += Character.charCount(cp);
        }
    }

    private static boolean blank(String value) {
        if (value == null || value.isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i)) && !Character.isSpaceChar(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> destinations(Map<String, List<String>> plan) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> entry : plan.entrySet()) {
            for (String key : entry.getValue()) result.put(key, entry.getKey());
        }
        return result;
    }

    private static String rule(String text) {
        Set<String> tokens = new HashSet<String>();
        Collections.addAll(tokens, text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"));
        for (String[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) {
                if (tokens.contains(rule[i])) return rule[0];
            }
        }
        return null;
    }

    private static String availableName(String base, Map<String, List<String>> plan) {
        String name = base;
        for (int suffix = 2; plan.containsKey(name); suffix++) name = base + " (" + suffix + ")";
        return name;
    }
}
