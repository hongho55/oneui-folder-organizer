package dev.local.oneuifolders;
import android.os.ParcelFileDescriptor;

/** File-only bridge: no launcher calls, generic paths, arbitrary commands or restore. */
interface IBackupService {
    void destroy() = 16777114;
    String info() = 1;
    String[] listBackups() = 2;
    ParcelFileDescriptor openBackup(String name) = 3;
    String stage(String sourceName, String sourceSha256, String outputName,
                 in ParcelFileDescriptor payload, String outputSha256) = 4;
}
