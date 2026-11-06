package dev.local.homeupio;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Permission-free manual file handoff probe; never calls or restores the launcher. */
public final class MainActivity extends Activity {
    private static final int OPEN = 10, SAVE = 11, TREE = 12;
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final String BACKUP = "Android/data/com.samsung.android.app.homestar/files/.AutoBackup";
    private final JSONObject report = new JSONObject();
    private TextView status;
    private Button save;
    private byte[] selected;
    private final StringBuilder log = new StringBuilder();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(22); content.setPadding(p, p, p, p);
        content.setBackgroundColor(Color.rgb(248, 249, 252));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content);
        setContentView(scroll);
        text(content, "Home Up 파일 경로 확인", 24, true);
        text(content, "백업·복원은 Home Up에서 직접 합니다.\n이 시험 앱은 파일 선택과 동일한 사본 저장만 확인합니다.\n폴더·위젯·런처 설정은 변경하지 않습니다.", 16, false);
        button(content, "1. Home Up 백업 파일 선택", v -> openFile());
        save = button(content, "2. 선택한 파일의 사본 저장", v -> saveFile()); save.setEnabled(false);
        button(content, "3. Home Up 백업 폴더 선택", v -> openTree());
        button(content, "시험용 샘플 불러오기", v -> loadSample());
        text(content, "앱 권한: 저장소 전체 접근 없음 · 네트워크 없음 · Shizuku 없음", 13, false);
        status = text(content, "", 14, false); status.setTextIsSelectable(true);
        put("package", getPackageName()); put("uid", android.os.Process.myUid());
        put("target_sdk", getApplicationInfo().targetSdkVersion);
        put("has_all_files_access", Environment.isExternalStorageManager());
        try {
            String[] permissions = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
            JSONArray list = new JSONArray(); if (permissions != null) for (String permission : permissions) list.put(permission);
            put("requested_permissions", list);
        } catch (Exception e) { put("permission_inspection_error", e.getClass().getSimpleName()); }
        File folder = new File(Environment.getExternalStorageDirectory(), BACKUP);
        String[] entries = folder.list();
        put("direct_backup_directory_readable", folder.canRead());
        put("direct_backup_listing_available", entries != null);
        note(entries == null ? "일반 앱의 Home Up 백업 폴더 직접 조회: 차단됨" : "일반 앱의 Home Up 백업 폴더 직접 조회: 가능 (" + entries.length + "개)");
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(LinearLayout parent, String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(27, 35, 49)); view.setPadding(0, dp(10), 0, dp(12));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String label, View.OnClickListener action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setMinHeight(dp(54));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(10);
        parent.addView(view, params); view.setOnClickListener(action); return view;
    }
    private Uri initialDocument(String relative) {
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + relative);
    }
    private void openFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialDocument(BACKUP));
        put("last_action", "open_homeup_backup"); launch(intent, OPEN);
    }
    private void openTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialDocument(BACKUP));
        put("last_action", "choose_homeup_directory"); launch(intent, TREE);
    }
    private void saveFile() {
        if (selected == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "homeup-io-check-" + System.currentTimeMillis() + ".exml");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialDocument("Download"));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        put("last_action", "save_exact_copy"); launch(intent, SAVE);
    }
    private void launch(Intent intent, int request) {
        try { startActivityForResult(intent, request); }
        catch (Exception e) { put("launch_error", e.getClass().getSimpleName() + ": " + e.getMessage()); note("파일 선택기를 열지 못했습니다: " + e.getMessage()); }
        persist();
    }
    private void loadSample() {
        selected = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<sample>" +
                "<notice>FICTIONAL FILE IO FIXTURE - NOT A HOME UP BACKUP - DO NOT RESTORE</notice>" +
                "<favorite packageName=\"example.fixture.app\" className=\"example.fixture.MainActivity\"/>" +
                "</sample>\n").getBytes(StandardCharsets.UTF_8);
        put("selected_source", "synthetic_fixture_not_restorable"); put("selected_bytes", selected.length); put("selected_sha256", sha(selected));
        save.setEnabled(true); note("가상 샘플 준비됨. 실제 백업이 아니며 Home Up에서 복원하면 안 됩니다.");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            put("last_picker_result", "cancelled"); note("파일 선택을 취소했습니다."); return;
        }
        Uri uri = data.getData();
        try {
            if (request == OPEN) {
                byte[] bytes = read(uri); selected = bytes; put("selected_source", "user_selected_document");
                put("selected_name", name(uri)); put("selected_uri", uri.toString()); put("selected_bytes", bytes.length); put("selected_sha256", sha(bytes));
                save.setEnabled(true); note("읽기 성공: " + name(uri) + "\n크기 " + bytes.length + " bytes\nSHA-256 " + sha(bytes));
            } else if (request == SAVE) {
                if (selected == null) throw new IllegalStateException("원본 파일을 먼저 선택하세요.");
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("저장 스트림 없음"); output.write(selected);
                }
                byte[] saved = read(uri); boolean equal = java.util.Arrays.equals(selected, saved);
                put("export_uri", uri.toString()); put("export_name", name(uri)); put("export_bytes", saved.length); put("export_sha256", sha(saved)); put("export_roundtrip_bytes_identical", equal);
                if (!equal) throw new IllegalStateException("저장 후 내용이 원본과 다릅니다.");
                note("저장·재읽기 성공. 원본과 바이트 단위로 동일합니다.\n" + name(uri));
            } else if (request == TREE) {
                put("selected_tree_uri", uri.toString());
                String documentId = DocumentsContract.getTreeDocumentId(uri);
                boolean exact = documentId.equals("primary:" + BACKUP);
                put("selected_tree_is_homeup_backup", exact);
                note(exact ? "Home Up 백업 폴더가 선택되었습니다." : "선택된 위치는 Home Up 백업 폴더가 아닙니다.\n" + documentId);
            }
        } catch (Exception e) {
            put("operation_error", e.getClass().getSimpleName() + ": " + e.getMessage()); note("실패: " + e.getMessage());
        }
        persist();
    }
    private String name(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "선택한 파일";
    }
    private byte[] read(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("읽기 스트림 없음");
            byte[] buf = new byte[8192]; int n;
            while ((n = input.read(buf)) != -1) {
                if (output.size() + n > LIMIT) throw new IllegalArgumentException("시험 앱의 4 MiB 제한을 초과합니다."); output.write(buf, 0, n);
            }
            return output.toByteArray();
        }
    }
    private String sha(byte[] value) {
        try { StringBuilder s = new StringBuilder(); for (byte x : MessageDigest.getInstance("SHA-256").digest(value)) s.append(String.format(java.util.Locale.ROOT, "%02x", x & 255)); return s.toString(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void put(String key, Object value) { try { report.put(key, value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private void note(String message) { log.append(message).append("\n\n"); if (status != null) status.setText(log.toString()); persist(); }
    private void persist() {
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "io-report.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { if (status != null) status.append("\n시험 결과 저장 실패: " + e.getMessage()); }
    }
}
