package dev.local.oneuifolders.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import dev.local.oneuifolders.BackupService;
import dev.local.oneuifolders.IBackupService;
import dev.local.oneuifolders.core.ExmlEditor;
import dev.local.oneuifolders.planning.FolderPlanner;
import org.json.JSONArray;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Opt-in real-data integration without starting an Activity or editing the saved plan.
 * Creates only the explicitly named NEW test backup. Caller owns exact-file cleanup.
 * Never changes settings, launcher state, permissions or foreground UI.
 */
public final class HardwareWorkbenchTest extends Instrumentation {
    private Bundle options;
    private int assertions;
    private final IBackupService[] bridge=new IBackupService[1];
    private final CountDownLatch bound=new CountDownLatch(1);
    private Shizuku.UserServiceArgs serviceArgs;
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){bridge[0]=IBackupService.Stub.asInterface(binder);bound.countDown();}
        @Override public void onServiceDisconnected(ComponentName name){bridge[0]=null;}
    };
    @Override public void onCreate(Bundle args){options=args;start();}
    private void check(boolean ok,String what){assertions++;if(!ok)throw new AssertionError(what);}
    private interface Action{void run()throws Exception;}
    private void rejected(Action action)throws Exception{boolean refused=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){refused=true;}check(refused,"Invalid Binder request rejected");}
    private String stage(String source,String sourceHash,String target,byte[] payload)throws Exception{
        File file=File.createTempFile("hardware-workbench-",".exml",getTargetContext().getCacheDir());
        try{Files.write(file.toPath(),payload);try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){return bridge[0].stage(source,sourceHash,target,fd,ExmlEditor.sha256(payload));}}
        finally{file.delete();}
    }
    private byte[] open(String name)throws Exception{
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(bridge[0].openBackup(name));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>8*1024*1024)throw new IOException("Backup size limit");}return out.toByteArray();
        }
    }
    @Override public void onStart(){Bundle result=new Bundle();int finishCode=Activity.RESULT_CANCELED;try{
        if(options==null||!"yes".equals(options.getString("allowStage")))throw new IllegalArgumentException("Explicit allowStage=yes required; no Activity will start");
        final String target=options.getString("outputName","");
        if(!target.matches("[0-9]{13}\\.exml"))throw new IllegalArgumentException("Explicit new 13-digit outputName required");
        File dir=getTargetContext().getFilesDir(),planFile=new File(dir,"plan.json");
        final byte[] source=Files.readAllBytes(new File(dir,"source.exml").toPath());
        final byte[] planBytes=Files.readAllBytes(planFile.toPath());
        JSONObject meta=new JSONObject(new String(Files.readAllBytes(new File(dir,"source-meta.json").toPath()),StandardCharsets.UTF_8));
        final String sourceName=meta.getString("name");
        check(sourceName.matches("[0-9]{13}\\.exml")&&"shizuku".equals(meta.getString("transport")),"Previously user-imported native Shizuku source required");
        ExmlEditor.Snapshot snapshot=ExmlEditor.parse(source);
        JSONObject saved=new JSONObject(new String(planBytes,StandardCharsets.UTF_8));
        check(saved.getInt("version")==1&&snapshot.sourceSha256.equals(saved.getString("source_sha256")),"Saved plan matches exact original source");
        LinkedHashMap<String,List<String>> current=new LinkedHashMap<>();
        JSONArray folders=saved.getJSONArray("folders");
        for(int i=0;i<folders.length();i++){
            JSONObject folder=folders.getJSONObject(i);String name=folder.getString("name");
            check(!current.containsKey(name),"Saved plan folder names unique");
            List<String> keys=new ArrayList<>();JSONArray apps=folder.getJSONArray("apps");
            for(int j=0;j<apps.length();j++){JSONObject app=apps.getJSONObject(j);keys.add(app.getString("package")+"\t"+app.getString("activity"));}current.put(name,keys);
        }
        List<FolderPlanner.AppInfo> installed=new ArrayList<>();PackageManager pm=getTargetContext().getPackageManager();
        for(ExmlEditor.App app:snapshot.apps){String label=app.packageName;int category=-1;try{ApplicationInfo info=pm.getApplicationInfo(app.packageName,0);label=pm.getApplicationLabel(info).toString();category=info.category;}catch(PackageManager.NameNotFoundException ignored){}
            installed.add(new FolderPlanner.AppInfo(app.key,app.packageName,label,category));}
        FolderPlanner.validate(installed,current);
        FolderPlanner.Proposal preserve=FolderPlanner.suggest(installed,current,false),all=FolderPlanner.suggest(installed,current,true);
        for(Map.Entry<String,List<String>> group:current.entrySet())if(!"미분류".equals(group.getKey()))check(group.getValue().equals(preserve.folders.get(group.getKey())),"Manual folder preserved in default mode");
        check(all.folders.equals(FolderPlanner.suggest(installed,current,true).folders),"Real installed-metadata classification is deterministic");
        ExmlEditor.Result output=ExmlEditor.build(snapshot,all.folders);
        check(output.protectedBytesIdentical&&output.appCount==snapshot.apps.size(),"Real all-mode conversion preserves protected bytes and exact coverage");
        CountDownLatch received=new CountDownLatch(1);Shizuku.OnBinderReceivedListener listener=received::countDown;
        Shizuku.addBinderReceivedListenerSticky(listener);
        try{check(received.await(20,TimeUnit.SECONDS),"Shizuku binder delivered without opening UI");}finally{Shizuku.removeBinderReceivedListener(listener);}
        check(Shizuku.pingBinder()&&Shizuku.getUid()==2000&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED,"Existing user-granted shell-only Shizuku permission");
        serviceArgs=new Shizuku.UserServiceArgs(new ComponentName(getTargetContext(),BackupService.class)).daemon(false).processNameSuffix("homeup_files").debuggable(false).version(3);
        runOnMainSync(() -> Shizuku.bindUserService(serviceArgs,connection));
        check(bound.await(20,TimeUnit.SECONDS)&&bridge[0]!=null,"Real file service bound without Activity");
        check(new JSONObject(bridge[0].info()).getInt("uid")==2000,"Remote file service runs as shell, not root");
        check(!Arrays.asList(bridge[0].listBackups()).contains(target),"Explicit output must not already exist");
        check(Arrays.equals(source,open(sourceName)),"Device original still matches locally imported original");
        rejected(() -> bridge[0].openBackup("../"+sourceName));
        rejected(() -> stage(sourceName,snapshot.sourceSha256,sourceName,output.bytes));
        final String badHash=new String(new char[64]).replace('\0','0');
        rejected(() -> stage(sourceName,badHash,target,output.bytes));
        final byte[] protectedChange=Arrays.copyOf(output.bytes,output.bytes.length+1);protectedChange[protectedChange.length-1]='\n';
        rejected(() -> stage(sourceName,snapshot.sourceSha256,target,protectedChange));
        check(!Arrays.asList(bridge[0].listBackups()).contains(target),"Negative paths created no output");
        JSONObject staged=new JSONObject(stage(sourceName,snapshot.sourceSha256,target,output.bytes));
        check(staged.getString("sha256").equals(output.outputSha256),"Positive real Binder stage reports exact generated hash");
        check(Arrays.equals(open(target),output.bytes),"Staged automatic layout independently read back byte-identically");
        rejected(() -> stage(sourceName,snapshot.sourceSha256,target,output.bytes));
        check(Arrays.equals(source,open(sourceName)),"Device original unchanged after positive and negative writes");
        check(Arrays.equals(planBytes,Files.readAllBytes(planFile.toPath())),"Saved user plan never mutated");
        JSONObject report=new JSONObject();report.put("assertions",assertions);report.put("app_count",snapshot.apps.size());report.put("saved_folder_count",current.size());report.put("suggested_folder_count",all.folders.size());report.put("review_app_count",all.uncertainKeys.size());report.put("target_name",target);report.put("output_sha256",output.outputSha256);report.put("saved_plan_unchanged",true);report.put("original_unchanged",true);report.put("activity_started",false);report.put("native_launcher_restored",false);
        result.putString("result",report.toString());finishCode=Activity.RESULT_OK;
    }catch(Throwable t){StringWriter text=new StringWriter();t.printStackTrace(new PrintWriter(text));result.putString("error",text.toString());}
    finally{if(serviceArgs!=null)runOnMainSync(() -> {try{Shizuku.unbindUserService(serviceArgs,connection,true);}catch(Exception ignored){}});}
    finish(finishCode,result);}
}
