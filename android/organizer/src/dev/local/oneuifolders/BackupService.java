package dev.local.oneuifolders;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import dev.local.oneuifolders.core.ExmlEditor;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Shizuku service restricted to native Home Up backup files. No shell execution. */
public final class BackupService extends IBackupService.Stub {
    private static final File ROOT = new File("/sdcard/Android/data/com.samsung.android.app.homestar/files/.AutoBackup");
    private static final File PREVIEW = new File("/sdcard/Android/data/com.samsung.android.app.homestar/files/.AutoBackupPreview");
    private static final int LIMIT = 8 * 1024 * 1024;
    public BackupService() { }

    @Override public void destroy() { System.exit(0); }
    @Override public String info() throws RemoteException {
        try { JSONObject object = new JSONObject(); object.put("uid", Process.myUid()); object.put("pid", Process.myPid()); object.put("file_only", true); object.put("root", ROOT.getAbsolutePath()); return object.toString(); }
        catch (Exception e) { throw failure(e); }
    }
    @Override public synchronized String[] listBackups() throws RemoteException {
        try {
            directory(ROOT);
            File[] files = ROOT.listFiles();
            if (files == null) throw new IllegalStateException("Home Up 백업 폴더를 읽을 수 없습니다. ADB 대안을 사용하세요.");
            ArrayList<String> names = new ArrayList<>();
            for (File f : files) if (validName(f.getName()) && f.isFile() && !Files.isSymbolicLink(f.toPath()) && f.length() > 0 && f.length() <= LIMIT) names.add(f.getName());
            names.sort(Comparator.reverseOrder()); return names.toArray(new String[0]);
        } catch (Exception e) { throw failure(e); }
    }
    @Override public synchronized ParcelFileDescriptor openBackup(String name) throws RemoteException {
        try { return readDescriptor(backup(name)); }
        catch (Exception e) { throw failure(e); }
    }
    @Override public synchronized String stage(String sourceName, String sourceSha256, String outputName,
            ParcelFileDescriptor descriptor, String outputSha256) throws RemoteException {
        File target = null;
        boolean created = false;
        ArrayList<File> companions = new ArrayList<>();
        try {
            if (descriptor == null) throw new IllegalArgumentException("변환 파일이 없습니다.");
            File source = backup(sourceName); target = backup(outputName);
            if (sourceName.equals(outputName) || Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("원본 또는 기존 파일을 덮어쓸 수 없습니다.");
            byte[] original;
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(readDescriptor(source))) { original = read(input); }
            if (!ExmlEditor.sha256(original).equals(sourceSha256)) throw new IllegalStateException("원본 백업이 바뀌었습니다. 다시 불러오세요.");
            byte[] payload;
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { payload = read(input); }
            if (!ExmlEditor.sha256(payload).equals(outputSha256)) throw new IllegalStateException("전달한 파일의 해시가 다릅니다.");
            ExmlEditor.Snapshot before = ExmlEditor.parse(original), after = ExmlEditor.parse(payload);
            ExmlEditor.Result rebuilt = ExmlEditor.build(before, after.folders);
            if (!Arrays.equals(rebuilt.bytes, payload) || !rebuilt.protectedBytesIdentical) throw new IllegalArgumentException("앱스 외 영역을 바꾸는 파일은 저장할 수 없습니다.");
            writeExclusive(target, payload); created = true;
            byte[] readback;
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(readDescriptor(target))) { readback = read(input); }
            if (!Arrays.equals(payload, readback)) throw new IllegalStateException("기기 저장 후 검증에 실패했습니다.");
            String oldBase = sourceName.substring(0, sourceName.length() - 5), newBase = outputName.substring(0, outputName.length() - 5);
            boolean frontCopied = copyIfPresent(new File(ROOT, oldBase + "_front.exml"), new File(ROOT, newBase + "_front.exml"), companions);
            boolean previewCopied = copyIfPresent(new File(PREVIEW, oldBase + ".png"), new File(PREVIEW, newBase + ".png"), companions);
            JSONObject result = new JSONObject(); result.put("name", outputName); result.put("sha256", ExmlEditor.sha256(readback)); result.put("bytes", readback.length);
            result.put("apps", rebuilt.appCount); result.put("folders", rebuilt.folderCount); result.put("protected_bytes_identical", true);
            result.put("front_copied", frontCopied); result.put("preview_copied", previewCopied); result.put("launcher_restored", false); result.put("uid", Process.myUid());
            return result.toString();
        } catch (Exception e) {
            if (created && target != null) target.delete();
            for (File f : companions) f.delete();
            throw failure(e);
        } finally { if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { } }
    }
    private static boolean validName(String name) { return name != null && name.matches("[0-9]{13}\\.exml"); }
    private static void directory(File dir) throws Exception {
        if (!dir.isDirectory() || Files.isSymbolicLink(dir.toPath())) throw new IllegalStateException("Home Up에서 먼저 백업을 만들어 주세요.");
        // /sdcard itself is Android's standard symlink; the per-app directory must not be one.
        File app = new File("/sdcard/Android/data/com.samsung.android.app.homestar");
        if (Files.isSymbolicLink(app.toPath())) throw new IllegalStateException("앱 데이터 경로가 심볼릭 링크입니다.");
    }
    private static File backup(String name) throws Exception {
        if (!validName(name)) throw new IllegalArgumentException("지원하지 않는 백업 파일명입니다.");
        directory(ROOT); return new File(ROOT, name);
    }
    private static ParcelFileDescriptor readDescriptor(File file) throws Exception {
        FileDescriptor fd = Os.open(file.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
        try {
            if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) throw new IllegalArgumentException("일반 파일만 읽을 수 있습니다.");
            return ParcelFileDescriptor.dup(fd);
        } finally { Os.close(fd); }
    }
    private static byte[] read(InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = input.read(buf)) != -1) { if (output.size() + n > LIMIT) throw new IllegalArgumentException("백업 크기가 8 MiB를 초과합니다."); output.write(buf, 0, n); }
            if (output.size() == 0) throw new IllegalArgumentException("빈 파일입니다."); return output.toByteArray();
        }
    }
    private static void writeExclusive(File file, byte[] payload) throws Exception {
        FileDescriptor fd = Os.open(file.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
        boolean ok = false;
        try (FileOutputStream output = new FileOutputStream(fd)) { output.write(payload); output.getFD().sync(); ok = true; }
        finally { if (!ok) file.delete(); }
    }
    private static boolean copyIfPresent(File source, File target, ArrayList<File> created) throws Exception {
        if (!Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("동반 파일 이름이 이미 존재합니다.");
        byte[] bytes;
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(readDescriptor(source))) { bytes = read(input); }
        writeExclusive(target, bytes); created.add(target);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(readDescriptor(target))) {
            if (!Arrays.equals(bytes, read(input))) throw new IllegalStateException("동반 파일 검증 실패");
        }
        return true;
    }
    private static RuntimeException failure(Exception e) {
        // Binder marshals supported runtime exceptions, not an application-thrown
        // RemoteException. The latter can silently produce a null client result.
        if (e instanceof IllegalArgumentException) return (IllegalArgumentException)e;
        if (e instanceof IllegalStateException) return (IllegalStateException)e;
        return new IllegalStateException(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
