package dev.local.oneuifolders.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import dev.local.oneuifolders.MainActivity;
import dev.local.oneuifolders.core.ExmlEditor;
import dev.local.oneuifolders.planning.FolderPlanner;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Explicit emulator-only synthetic controller/render regression. No Shizuku or native file writes. */
public final class WorkbenchDeviceTest extends Instrumentation {
    private MainActivity activity;
    private Bundle options;
    private int assertions;
    private final LinkedHashMap<String,String> demoLabels=new LinkedHashMap<>();
    private static final Class<?>[] NONE=new Class<?>[0];
    @Override public void onCreate(Bundle args){options=args;start();}
    private void check(boolean ok,String what){assertions++;if(!ok)throw new AssertionError(what);}
    private Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private interface Action{void run()throws Exception;}
    private void ui(Action action)throws Exception{final Throwable[] issue=new Throwable[1];runOnMainSync(() -> {try{action.run();}catch(Throwable e){issue[0]=e;}});if(issue[0]!=null)throw new Exception("UI/controller regression",issue[0]);}
    @SuppressWarnings("unchecked") private LinkedHashMap<String,List<String>> plan()throws Exception{return (LinkedHashMap<String,List<String>>)field("folders");}
    @SuppressWarnings("unchecked") private void labels()throws Exception{((Map<String,String>)field("labels")).putAll(demoLabels);}
    private byte[] fixture(){
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><viewType_appOrder>CUSTOM</viewType_appOrder><Rows_appOrder>4</Rows_appOrder><Columns_appOrder>5</Columns_appOrder><FolderGrid>3X3</FolderGrid><home><appwidget screen=\"0\" x=\"0\" y=\"0\" packageName=\"example.fixture.widget\" className=\"example.fixture.Widget\" appWidgetID=\"77\"/></home><hotseat/><home_dex/><hotseat_dex/><appOrder><folder screen=\"0\" title=\"가상 보관함\" options=\"0\" color=\"0\">");
        String[][] groups={{"notes","메모"},{"maps","지도"},{"music","음악"},{"game","게임"},{"camera","사진"},{"bank","은행"},{"unknown","확인할 앱"}};
        int count=0;
        for(String[] group:groups)for(int i=0;i<2;i++){
            if(count==4)b.append("</folder>");
            String p="example.fixture."+group[0]+"."+(i==0?"alpha":"beta"),a=p+".Main";
            demoLabels.put(p+"\t"+a,"가상 "+group[1]+" "+(i==0?"A":"B"));
            b.append("<favorite screen=\"0\" packageName=\"").append(p).append("\" className=\"").append(a).append("\" hidden=\"0\"/>");count++;
        }
        return b.append("</appOrder><appOrder_dex/>").toString().getBytes(StandardCharsets.UTF_8);
    }
    private View tagged(View view,String tag){if(tag.equals(view.getTag()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View match=tagged(group.getChildAt(i),tag);if(match!=null)return match;}}return null;}
    private void tap(String tag)throws Exception{View v=tagged(activity.getWindow().getDecorView(),tag);check(v!=null,"Visible tagged control: "+tag);check(v.isEnabled(),"Enabled control: "+tag);v.performClick();}
    private byte[] planBytes()throws Exception{return Files.readAllBytes(new File(getTargetContext().getFilesDir(),"plan.json").toPath());}
    private void importFixture(byte[] bytes)throws Exception{
        File file=new File(getTargetContext().getCacheDir(),"synthetic-import-check.exml");
        Files.write(file.toPath(),bytes);
        try{
            ui(() -> call("onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},(Integer)field("OPEN"),Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file))));
            long deadline=System.currentTimeMillis()+8000;
            while(Boolean.TRUE.equals(field("busy"))&&System.currentTimeMillis()<deadline)Thread.sleep(50);
            waitForIdleSync();check(!Boolean.TRUE.equals(field("busy")),"Real import callback finished");
        }finally{file.delete();}
    }
    private void recreate()throws Exception{
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
        Activity next;
        try{ui(() -> activity.recreate());next=waitForMonitorWithTimeout(monitor,8000);}
        finally{removeMonitor(monitor);}
        check(next instanceof MainActivity,"Native activity recreation completed");
        activity=(MainActivity)next;waitForIdleSync();ui(() -> labels());
    }
    private void capture(String scene)throws Exception{
        waitForIdleSync();
        getUiAutomation().waitForIdle(200,4000);
        Bitmap image=getUiAutomation().takeScreenshot();check(image!=null,"Native screenshot available");
        File target=new File(getTargetContext().getFilesDir(),"workbench-"+scene+".png");
        try(OutputStream out=new FileOutputStream(target)){check(image.compress(Bitmap.CompressFormat.PNG,100,out),"PNG capture written");}image.recycle();
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(options==null||!"yes".equals(options.getString("allowSynthetic"))||!"ranchu".equals(Build.HARDWARE))throw new IllegalArgumentException("Emulator-only: explicit allowSynthetic=yes and ranchu hardware required");
        String scene=options.getString("scene","regression");
        check(scene.matches("[a-z-]+"),"Safe scene name");
        Intent intent=new Intent().setClassName(getTargetContext(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity=(MainActivity)startActivitySync(intent);waitForIdleSync();
        if("empty".equals(scene)){capture(scene);result.putString("scene",scene);result.putInt("assertions",assertions);finish(Activity.RESULT_OK,result);return;}
        final byte[] source=fixture();final ExmlEditor.Snapshot snapshot=ExmlEditor.parse(source);
        ui(() -> {call("saveSource",new Class<?>[]{byte[].class,String.class,String.class},source,"synthetic-demo.exml","saf");call("installSource",new Class<?>[]{byte[].class,ExmlEditor.Snapshot.class,String.class,String.class},source,snapshot,"synthetic-demo.exml","saf");labels();call("refresh",NONE);});waitForIdleSync();
        final LinkedHashMap<String,List<String>> original=FolderPlanner.copy(plan());
        check(original.size()==2&&snapshot.apps.size()==14,"Synthetic folder and unfiled model");
        check(((ListView)field("list")).getAdapter().getCount()==original.size(),"Folder-first list after import");
        final byte[] padded=new String(source,StandardCharsets.UTF_8).replace("title=\"가상 보관함\"","title=\" 전체 \"").getBytes(StandardCharsets.UTF_8);
        importFixture(padded);
        check(plan().containsKey(" 전체 "),"Previously supported padded title imports without normalization or crash");
        importFixture(source);
        check(plan().equals(original),"Importing original source restores its archived plan");
        final byte[] sourceBeforeReject=Files.readAllBytes(new File(getTargetContext().getFilesDir(),"source.exml").toPath());
        final byte[] planBeforeReject=planBytes();
        final byte[] reserved=new String(source,StandardCharsets.UTF_8).replace("title=\"가상 보관함\"","title=\"전체\"").getBytes(StandardCharsets.UTF_8);
        importFixture(reserved);
        check(Arrays.equals(sourceBeforeReject,Files.readAllBytes(new File(getTargetContext().getFilesDir(),"source.exml").toPath()))&&Arrays.equals(planBeforeReject,planBytes())&&plan().equals(original),"Unsupported reserved source rejected before saved/live session replacement");
        ui(() -> {set("prepareAfterConnect",true);call("disconnect",new Class<?>[]{boolean.class},false);});
        check(Boolean.FALSE.equals(field("prepareAfterConnect")),"Disconnect clears pending prepare intent");
        ui(() -> {set("prepareAfterConnect",true);((rikka.shizuku.Shizuku.OnBinderDeadListener)field("binderDead")).onBinderDead();});
        check(Boolean.FALSE.equals(field("prepareAfterConnect")),"Binder death clears pending prepare intent");
        ui(() -> {call("saveSource",new Class<?>[]{byte[].class,String.class,String.class},source,"synthetic-demo.exml","saf");call("installSource",new Class<?>[]{byte[].class,ExmlEditor.Snapshot.class,String.class,String.class},source,snapshot,"synthetic-demo.exml","saf");labels();});
        final byte[] persisted=planBytes();
        final FolderPlanner.Proposal[] proposals=new FolderPlanner.Proposal[2];
        ui(() -> {proposals[0]=(FolderPlanner.Proposal)call("createProposal",new Class<?>[]{boolean.class},false);proposals[1]=(FolderPlanner.Proposal)call("createProposal",new Class<?>[]{boolean.class},true);});
        check(plan().equals(original)&&Arrays.equals(persisted,planBytes()),"Generating proposal never mutates local plan or persistence");
        ui(() -> call("showProposal",new Class<?>[]{boolean.class},true));
        check(field("proposal")!=null&&plan().equals(original),"Opening actual preview does not apply it");
        ui(() -> call("cancelProposal",NONE));
        check(field("proposal")==null&&plan().equals(original)&&Arrays.equals(persisted,planBytes()),"Cancelling actual preview preserves exact persisted plan");
        check(proposals[0].folders.get("가상 보관함").equals(original.get("가상 보관함")),"Unfiled-only keeps manual folder");
        check(proposals[1].movedCount>0,"All-mode produces an actual proposal");
        check(proposals[1].singletonCount==0,"Paired demo categories avoid invalid singleton output");
        ui(() -> call("applyProposal",new Class<?>[]{FolderPlanner.Proposal.class},proposals[1]));
        check(plan().equals(proposals[1].folders),"Apply uses exact proposed plan");
        ExmlEditor.Result built=ExmlEditor.build(snapshot,plan());check(built.protectedBytesIdentical&&built.appCount==14,"Applied proposal preserves every component and protected bytes");
        recreate();
        check(plan().equals(proposals[1].folders)&&field("undoPlan")!=null,"Applied plan and source-tied undo survive native activity recreation");
        ui(() -> call("undoLastChange",NONE));
        check(plan().equals(original),"Undo restores exact previous folder order and membership");
        ui(() -> {call("applyProposal",new Class<?>[]{FolderPlanner.Proposal.class},proposals[1]);call("restoreLocalSession",NONE);labels();});
        check(plan().equals(proposals[1].folders),"Automatic plan survives saved-session reload");
        ui(() -> {plan().clear();plan().putAll(FolderPlanner.copy(original));call("savePlan",NONE);call("refresh",NONE);});
        List<String> moved=new ArrayList<>(original.get("미분류").subList(0,2));
        ui(() -> call("moveApps",new Class<?>[]{Collection.class,String.class},moved,"가상 새 폴더"));
        check(plan().get("가상 새 폴더").equals(moved),"Bulk move preserves selection source order");
        ui(() -> call("undoLastChange",NONE));check(plan().equals(original),"Bulk-move undo exact");
        final File savedPlan=new File(getTargetContext().getFilesDir(),"plan.json");
        final byte[] validPlan=planBytes();
        final byte[] broken="{not-valid-json".getBytes(StandardCharsets.UTF_8);
        Files.write(savedPlan.toPath(),broken);
        ui(() -> call("restoreLocalSession",NONE));
        check(Boolean.TRUE.equals(field("planReadFailed")),"Corrupt saved plan blocks editing");
        check(Arrays.equals(broken,planBytes()),"Failed plan decode preserves original corrupt bytes");
        ui(() -> call("moveApps",new Class<?>[]{Collection.class,String.class},moved,"새 수정 금지"));
        check(Arrays.equals(broken,planBytes()),"Blocked editing does not overwrite unreadable user work");
        Files.write(savedPlan.toPath(),validPlan);
        ui(() -> {call("restoreLocalSession",NONE);labels();});
        check(!Boolean.TRUE.equals(field("planReadFailed"))&&plan().equals(original),"Restoring valid plan recovers session without loss");
        final byte[] otherSource=new String(source,StandardCharsets.UTF_8).replace("appWidgetID=\"77\"","appWidgetID=\"78\"").getBytes(StandardCharsets.UTF_8);
        check(!Arrays.equals(otherSource,source),"Alternate synthetic source hash differs");
        final ExmlEditor.Snapshot other=ExmlEditor.parse(otherSource);
        ui(() -> {call("saveSource",new Class<?>[]{byte[].class,String.class,String.class},otherSource,"synthetic-other.exml","saf");call("installSource",new Class<?>[]{byte[].class,ExmlEditor.Snapshot.class,String.class,String.class},otherSource,other,"synthetic-other.exml","saf");call("moveApps",new Class<?>[]{Collection.class,String.class},moved,"다른 원본 폴더");});
        ui(() -> {call("saveSource",new Class<?>[]{byte[].class,String.class,String.class},source,"synthetic-demo.exml","saf");call("installSource",new Class<?>[]{byte[].class,ExmlEditor.Snapshot.class,String.class,String.class},source,snapshot,"synthetic-demo.exml","saf");labels();});
        check(plan().equals(original),"Switching source hashes recovers its own saved plan");
        final String first="가상 보관함";
        if("folders".equals(scene)){ui(() -> call("showFolders",NONE));}
        else if("detail".equals(scene)||"selection".equals(scene)){
            ui(() -> call("showFolder",new Class<?>[]{String.class},first));waitForIdleSync();
            check(((ListView)field("list")).getAdapter().getCount()==4,"Folder detail contains only selected folder apps");
            if("selection".equals(scene))ui(() -> {ListView list=(ListView)field("list");View row=list.getChildAt(0);check(row!=null,"Rendered app row");list.getOnItemLongClickListener().onItemLongClick(list,row,0,list.getAdapter().getItemId(0));});
        }else if("proposal".equals(scene))ui(() -> call("showProposal",new Class<?>[]{boolean.class},true));
        else if(!"regression".equals(scene))throw new IllegalArgumentException("Unknown scene "+scene);
        if(!"regression".equals(scene))capture(scene);
        JSONObject report=new JSONObject();report.put("assertions",assertions);report.put("scene",scene);report.put("apps",14);report.put("synthetic_emulator_only",true);report.put("proposal_cancel_no_mutation",true);report.put("apply_undo_bulk_persistence_verified",true);report.put("protected_bytes_identical",true);report.put("native_launcher_restored",false);
        Files.write(new File(getTargetContext().getFilesDir(),"workbench-test-result.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));result.putString("result",report.toString());finish(Activity.RESULT_OK,result);return;
    }catch(Throwable t){StringWriter text=new StringWriter();t.printStackTrace(new PrintWriter(text));result.putString("error",text.toString());}finish(Activity.RESULT_CANCELED,result);}
}
