package dev.local.oneuifolders.tests;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.ListView;
import android.widget.Spinner;
import dev.local.oneuifolders.MainActivity;
import dev.local.oneuifolders.IBackupService;
import dev.local.oneuifolders.core.ExmlEditor;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Opt-in, same-signature integration test. Requires an already approved Shizuku client.
 * Does not restore the launcher or change its database. Adds one native backup file.
 * Uses the app's current local source/plan and restores that plan after temporary edits.
 */
public final class EditorDeviceTest extends Instrumentation {
    private MainActivity activity;
    private LinkedHashMap<String,List<String>> originalPlan;
    private int assertions;
    private Bundle options;
    @Override public void onCreate(Bundle args) { options=args; start(); }
    private void check(boolean ok,String what) { assertions++; if(!ok)throw new AssertionError(what); }
    private Object field(String name)throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity); }
    private void call(String name,Class<?>[] types,Object...args)throws Exception { Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args); }
    private void set(String name,Object value)throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value); }
    private interface Action { void run()throws Exception; }
    private void ui(Action action)throws Exception { final Throwable[] problem=new Throwable[1];runOnMainSync(() -> {try{action.run();}catch(Throwable t){problem[0]=t;}});if(problem[0]!=null)throw new Exception("UI test failed",problem[0]); }
    @SuppressWarnings("unchecked") private LinkedHashMap<String,List<String>> plan()throws Exception { return (LinkedHashMap<String,List<String>>)field("folders"); }
    private static LinkedHashMap<String,List<String>> copy(LinkedHashMap<String,List<String>> source){LinkedHashMap<String,List<String>> result=new LinkedHashMap<>();for(Map.Entry<String,List<String>> e:source.entrySet())result.put(e.getKey(),new ArrayList<>(e.getValue()));return result;}
    private void reset()throws Exception { if(activity!=null&&originalPlan!=null)ui(() -> {plan().clear();plan().putAll(copy(originalPlan));call("savePlan",new Class<?>[0]);call("refresh",new Class<?>[0]);}); }
    private byte[] bytes(ParcelFileDescriptor fd)throws Exception {try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>8*1024*1024)throw new IOException("8MiB limit");out.write(buffer,0,n);}return out.toByteArray();}}
    private void reject(Action action,String message)throws Exception {boolean denied=false;try{action.run();}catch(Exception expected){denied=true;}check(denied,message);}
    private String invokeStage(IBackupService bridge,String source,String sourceHash,String name,byte[] payload)throws Exception {File f=new File(getTargetContext().getCacheDir(),"test-output.exml");Files.write(f.toPath(),payload);try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY)){return bridge.stage(source,sourceHash,name,fd,ExmlEditor.sha256(payload));}finally{f.delete();}}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if(options==null||!"yes".equals(options.getString("allowStage")))throw new IllegalArgumentException("Explicit -e allowStage yes is required; this test adds one backup file.");
            File dir=getTargetContext().getFilesDir();
            check(new File(dir,"source.exml").isFile(),"First import a backup in the app; no automatic permission grant");
            Intent intent=new Intent().setClassName(getTargetContext(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=(MainActivity)startActivitySync(intent);waitForIdleSync();
            ui(() -> {check(field("snapshot")!=null,"Android XML parser loaded the source");originalPlan=copy(plan());});
            final ExmlEditor.Snapshot snapshot=(ExmlEditor.Snapshot)field("snapshot");
            final byte[] source=Files.readAllBytes(new File(dir,"source.exml").toPath());
            final String sourceName=new JSONObject(new String(Files.readAllBytes(new File(dir,"source-meta.json").toPath()),StandardCharsets.UTF_8)).getString("name");
            check(snapshot.sourceSha256.equals(ExmlEditor.sha256(source)),"Local source hash");
            ui(() -> {
                ListView list=(ListView)field("list");
                check(list.getAdapter().getCount()==originalPlan.size(),"Folder-first UI covers every saved folder");
                call("showFolder",new Class<?>[]{String.class},originalPlan.keySet().iterator().next());
            });waitForIdleSync();
            ui(() -> {check(((ListView)field("list")).getAdapter().getCount()==originalPlan.values().iterator().next().size(),"Folder detail actually filters the UI list");call("showFolders",new Class<?>[0]);});
            String from=null,to=null,key=null;
            for(Map.Entry<String,List<String>> e:originalPlan.entrySet())if(e.getValue().size()>2){from=e.getKey();key=e.getValue().get(0);break;}
            for(String name:originalPlan.keySet())if(!name.equals(from)){to=name;break;}
            check(key!=null&&to!=null,"Move test needs two folders and >=3 apps in one");
            final String moved=key,target=to;
            ui(() -> call("move",new Class<?>[]{String.class,String.class},moved,target));
            check(plan().get(target).contains(moved),"Move handler changes selected app folder");
            ExmlEditor.Result changed=ExmlEditor.build(snapshot,copy(plan()));
            check(changed.protectedBytesIdentical&&changed.appCount==snapshot.apps.size(),"Moved plan preserves protected bytes and coverage");
            ui(() -> call("restoreLocalSession",new Class<?>[0]));
            check(plan().get(target).contains(moved),"Edited plan survives local save/reload");
            reset();
            check(plan().equals(originalPlan),"Temporary test movement reverted, only local plan affected");
            ui(() -> call("connect",new Class<?>[0]));
            IBackupService bridge=null;
            long deadline=System.currentTimeMillis()+20000;
            while(System.currentTimeMillis()<deadline){bridge=(IBackupService)field("bridge");if(bridge!=null)break;Thread.sleep(100);}
            check(bridge!=null,"Approved Shizuku UserService actually binds");
            final IBackupService service=bridge;
            check(new JSONObject(service.info()).getInt("uid")==2000,"Non-root shell UID");
            check(Arrays.asList(service.listBackups()).contains(sourceName),"Real native folder lists imported source");
            check(Arrays.equals(source,bytes(service.openBackup(sourceName))),"Binder reads native backup byte-for-byte");
            reject(() -> service.openBackup("../"+sourceName),"Path traversal rejected by file service");
            ExmlEditor.Result output=ExmlEditor.build(snapshot,copy(originalPlan));
            String name=Long.toString(System.currentTimeMillis())+".exml";
            reject(() -> invokeStage(service,sourceName,snapshot.sourceSha256,sourceName,output.bytes),"Native source overwrite rejected");
            reject(() -> invokeStage(service,sourceName,String.join("",Collections.nCopies(64,"0")),name,output.bytes),"Wrong source hash rejected");
            byte[] bad=new String(output.bytes,StandardCharsets.UTF_8).replace("<appOrder>","\n<appOrder>").getBytes(StandardCharsets.UTF_8);
            check(!Arrays.equals(bad,output.bytes),"Protection test mutates only bytes outside appOrder");
            reject(() -> invokeStage(service,sourceName,snapshot.sourceSha256,name,bad),"Outside-byte mutation rejected before writing");
            ui(() -> {set("pendingOutput",output);set("pendingName",name);call("stage",new Class<?>[0]);});
            File receipt=new File(dir,"last-stage.json");JSONObject staged=null;
            deadline=System.currentTimeMillis()+25000;
            while(System.currentTimeMillis()<deadline){if(receipt.isFile()){JSONObject r=new JSONObject(new String(Files.readAllBytes(receipt.toPath()),StandardCharsets.UTF_8));if(name.equals(r.optString("name"))){staged=r;break;}}Thread.sleep(100);}
            check(staged!=null,"App's real stage callback completed through Binder");
            check(staged.getString("sha256").equals(output.outputSha256),"App verified staged digest");
            check(staged.getBoolean("protected_bytes_identical")&&!staged.getBoolean("launcher_restored"),"No native restore was performed");
            check(Arrays.equals(bytes(service.openBackup(name)),output.bytes),"Independent Binder readback matches exact output");
            reject(() -> invokeStage(service,sourceName,snapshot.sourceSha256,name,output.bytes),"Existing output overwrite rejected");
            check(Arrays.equals(source,bytes(service.openBackup(sourceName))),"Source remained byte-identical after all writes");
            JSONObject report=new JSONObject();report.put("assertions",assertions);report.put("apps",snapshot.apps.size());report.put("folders",originalPlan.size());report.put("ui_filter_verified",true);report.put("app_move_and_local_reload_verified",true);report.put("temporary_move_reverted",true);report.put("shizuku_binder_uid",2000);report.put("stage",staged);report.put("launcher_restored",false);
            Files.write(new File(dir,"device-test-result.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            result.putString("result",report.toString());reset();originalPlan=null;finish(Activity.RESULT_OK,result);return;
        } catch(Throwable t) {StringWriter text=new StringWriter();t.printStackTrace(new PrintWriter(text));result.putString("error",text.toString());try{Files.write(new File(getTargetContext().getFilesDir(),"device-test-error.txt").toPath(),text.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){} }
        finally {try{reset();}catch(Exception ignored){} }
        finish(Activity.RESULT_CANCELED,result);
    }
}
