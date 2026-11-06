package dev.local.oneuifolders.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
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
    private byte[] crowdedFixture(){
        String base=new String(fixture(),StandardCharsets.UTF_8);
        StringBuilder xml=new StringBuilder(base.substring(0,base.indexOf("<appOrder>")+"<appOrder>".length()));
        for(int i=1;i<=24;i++){
            String name=String.format(Locale.ROOT,"가상 폴더 %02d",i),pkg="example.fixture.crowded.f"+i;
            xml.append("<folder screen=\"0\" title=\"").append(name).append("\" options=\"0\" color=\"0\">");
            for(String suffix:new String[]{"EntryAlpha","EntryBeta"}){
                String activity=pkg+"."+suffix;demoLabels.put(pkg+"\t"+activity,"가상 같은 이름 "+i);
                xml.append("<favorite screen=\"0\" packageName=\"").append(pkg).append("\" className=\"").append(activity).append("\" hidden=\"0\"/>");
            }
            xml.append("</folder>");
        }
        return xml.append("</appOrder><appOrder_dex/>").toString().getBytes(StandardCharsets.UTF_8);
    }
    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node){
        if(node==null)return null;if(node.isScrollable())return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo result=scrollable(node.getChild(i));if(result!=null)return result;}return null;
    }
    private void crowdedCase()throws Exception{
        byte[] source=crowdedFixture();ExmlEditor.Snapshot snapshot=ExmlEditor.parse(source);
        ui(() -> {call("saveSource",new Class<?>[]{byte[].class,String.class,String.class},source,"synthetic-crowded.exml","saf");call("installSource",new Class<?>[]{byte[].class,ExmlEditor.Snapshot.class,String.class,String.class},source,snapshot,"synthetic-crowded.exml","saf");labels();call("showFolder",new Class<?>[]{String.class},"가상 폴더 01");});idle();
        check(snapshot.folders.size()==24&&snapshot.apps.size()==48,"Many-folder fixture has exact paired components");
        ui(() -> {ListView list=(ListView)field("list");Set<String> spoken=new HashSet<>();
            for(int i:appRows()){String key=rowKey(i);CharSequence description=list.getAdapter().getView(i,null,list).getContentDescription();
                check(description!=null&&description.toString().contains(key.split("\t")[0])&&description.toString().contains(key.split("\t")[1]),"Duplicate label accessibility includes exact package and activity");spoken.add(description.toString());}
            check(spoken.size()==2,"Same-package duplicate-label activities expose distinct native descriptions");});
        final byte[] before=planBytes();rowClick(appRows().get(0),false);
        check(semantic("새 폴더").isVisibleToUser(),"New-folder action visible immediately for 24 destinations");
        check(semantic("취소",true).isVisibleToUser(),"Native cancel reachable with many destinations and large font");
        boolean lastVisible=false;
        for(int i=0;i<snapshot.folders.size();i++){
            idle();AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("가상 폴더 24"))if(node.isVisibleToUser()){lastVisible=true;break;}
            if(lastVisible)break;
            AccessibilityNodeInfo list=scrollable(root);check(list!=null&&list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),"Native destinations scroll forward toward final folder");
        }
        check(lastVisible,"Last of 24 destinations reachable by native scrolling");
        check(semantic("새 폴더").isVisibleToUser()&&semantic("취소",true).isVisibleToUser(),"Create and cancel stay pinned while destination list scrolls");
        capture("manytargets");semanticTap("취소");
        check(Arrays.equals(before,planBytes()),"Cancelling crowded native chooser preserves exact saved bytes");
        check(ExmlEditor.build(snapshot,plan()).protectedBytesIdentical,"Crowded fixture keeps exact protected bytes");
        JSONObject report=new JSONObject();report.put("scene","manytargets");report.put("assertions",assertions);report.put("apps",snapshot.apps.size());report.put("folders",snapshot.folders.size());report.put("synthetic_emulator_only",true);report.put("duplicate_label_accessibility_verified",true);report.put("pinned_create_cancel_verified",true);report.put("native_launcher_restored",false);
        Bundle result=new Bundle();result.putString("result",report.toString());finish(Activity.RESULT_OK,result);
    }
    private void tap(String tag)throws Exception{View v=tagged(activity.getWindow().getDecorView(),tag);check(v!=null,"Visible tagged control: "+tag);check(v.isEnabled(),"Enabled control: "+tag);v.performClick();}
    private byte[] planBytes()throws Exception{return Files.readAllBytes(new File(getTargetContext().getFilesDir(),"plan.json").toPath());}
    private void idle()throws Exception{waitForIdleSync();getUiAutomation().waitForIdle(100,4000);}
    private AccessibilityNodeInfo semantic(String text)throws Exception{return semantic(text,false);}
    private AccessibilityNodeInfo semantic(String text,boolean exact)throws Exception{
        idle();AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        check(root!=null,"Active native accessibility window exists");
        for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null&&(exact?node.getText().toString().equals(text):node.getText().toString().contains(text)))return node;
        throw new AssertionError("Native text not found: "+text);
    }
    private void semanticTap(String text)throws Exception{
        AccessibilityNodeInfo node=semantic(text);
        while(node!=null&&!node.isClickable())node=node.getParent();
        check(node!=null&&node.isEnabled(),"Native clickable/enabled action: "+text);
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Native semantic click: "+text);idle();
    }
    private AccessibilityNodeInfo inputNode(AccessibilityNodeInfo root){
        if(root==null)return null;
        if("android.widget.EditText".contentEquals(root.getClassName()))return root;
        for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo result=inputNode(root.getChild(i));if(result!=null)return result;}return null;
    }
    private void enterName(String name)throws Exception{
        idle();AccessibilityNodeInfo input=inputNode(getUiAutomation().getRootInActiveWindow());
        check(input!=null,"Native folder-name input exists");Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name);
        check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text),"Native folder-name input accepts text");idle();
    }
    private void searchFor(String query)throws Exception{ui(() -> ((EditText)field("search")).setText(query));idle();}
    private String rowKey(int index)throws Exception{
        ListView list=(ListView)field("list");Object item=list.getAdapter().getItem(index);
        if(item instanceof String)return (String)item;
        Object tag=list.getAdapter().getView(index,null,list).getTag();
        if(tag instanceof String&&((String)tag).startsWith("app:"))return ((String)tag).substring(4);
        return null;
    }
    private List<Integer> appRows()throws Exception{
        ListView list=(ListView)field("list");List<Integer> result=new ArrayList<>();
        for(int i=0;i<list.getAdapter().getCount();i++){String key=rowKey(i);if(key!=null&&key.indexOf('\t')>0)result.add(i);}return result;
    }
    private void rowClick(int index,boolean longClick)throws Exception{
        idle();ui(() -> {ListView list=(ListView)field("list");View row=list.getChildAt(index-list.getFirstVisiblePosition());
            check(row!=null,"Native app row rendered for selection");
            if(longClick)list.getOnItemLongClickListener().onItemLongClick(list,row,index,list.getAdapter().getItemId(index));
            else list.performItemClick(row,index,list.getAdapter().getItemId(index));});idle();
    }
    @SuppressWarnings("unchecked") private Set<String> selected()throws Exception{return (Set<String>)field("selectedKeys");}
    private void placementUiChecks(LinkedHashMap<String,List<String>> original,ExmlEditor.Snapshot snapshot)throws Exception{
        final byte[] before=planBytes();
        // After a source switch the legacy file can still belong to the other source.
        // Undo restores THIS source's archived plan, not an unrelated legacy source.
        final byte[] activeBefore=Files.readAllBytes(new File(getTargetContext().getFilesDir(),"plan-"+snapshot.sourceSha256+".json").toPath());
        check(new JSONObject(new String(activeBefore,StandardCharsets.UTF_8)).getString("source_sha256").equals(snapshot.sourceSha256),"Undo expectation is tied to active source hash");
        ui(() -> call("showFolders",NONE));searchFor("지도");
        check(appRows().size()==2,"Home search exposes both matching app rows directly");
        String first=rowKey(appRows().get(0));check(first.contains("example.fixture.maps"),"Search returns an exact app key, not containing-folder row");
        rowClick(appRows().get(0),false);
        AccessibilityNodeInfo newFolder=semantic("새 폴더"),existingFolder=semantic("가상 보관함",true);
        Rect newBounds=new Rect(),existingBounds=new Rect();newFolder.getBoundsInScreen(newBounds);existingFolder.getBoundsInScreen(existingBounds);
        check(newBounds.top<existingBounds.top,"New-folder action is physically before existing targets: "+newBounds+" / "+existingBounds);
        semantic("4개 앱");
        check(plan().equals(original),"Opening destination picker does not mutate plan");
        AccessibilityNodeInfo current=semantic("여기 있음");
        if(current.isClickable()&&current.isEnabled())current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        idle();check(plan().equals(original),"Current-folder no-op cannot move or overwrite a plan");
        semanticTap("취소");
        check(plan().equals(original)&&Arrays.equals(before,planBytes()),"Cancelling native destination picker is byte-exact no-op");
        searchFor("지도");rowClick(appRows().get(0),true);
        check(Boolean.TRUE.equals(field("selecting"))&&selected().size()==1,"Long press enters selection from direct search result");
        rowClick(appRows().get(1),false);searchFor("은행");rowClick(appRows().get(0),false);
        LinkedHashSet<String> chosen=new LinkedHashSet<>(selected());
        check(chosen.size()==3,"Multi-selection survives changing queries across folders");
        View move=tagged(activity.getWindow().getDecorView(),"selection-move");
        check(move!=null&&move.isShown()&&move.isEnabled(),"Bottom move action visible for three selected apps");
        Rect bounds=new Rect();check(move.getGlobalVisibleRect(bounds),"Move action has visible screen bounds");
        check(bounds.centerY()>activity.getWindow().getDecorView().getHeight()/2,"Selection move action is in bottom half, not top scroll row");
        check(move.getHeight()>=48*activity.getResources().getDisplayMetrics().density,"Move action keeps 48dp touch target");
        ui(() -> tap("selection-move"));semanticTap("취소");
        check(selected().equals(chosen)&&Arrays.equals(before,planBytes()),"Destination cancel preserves exact multi-selection and saved plan");
        ui(() -> tap("selection-move"));semanticTap("새 폴더");enterName("가상 선택 폴더");semanticTap("만들고 이동");
        check(new ArrayList<>(plan().entrySet()).equals(new ArrayList<>(FolderPlanner.move(original,chosen,"가상 선택 폴더").entrySet())),"Native multi-query selection creates expected folder with exact source order");
        check(ExmlEditor.build(snapshot,plan()).protectedBytesIdentical,"Native placement result remains exportable with exact protected bytes");
        View undo=tagged(activity.getWindow().getDecorView(),"result-undo");
        check(undo!=null&&undo.isShown()&&undo.isEnabled(),"Undo visible without horizontal action-strip scrolling");
        recreate();undo=tagged(activity.getWindow().getDecorView(),"result-undo");
        check(undo!=null&&undo.isShown()&&undo.isEnabled(),"Result Undo remains visible after real native recreation");
        ui(() -> tap("result-undo"));
        check(new ArrayList<>(plan().entrySet()).equals(new ArrayList<>(original.entrySet()))&&Arrays.equals(activeBefore,planBytes()),"Visible result Undo restores exact active-source plan bytes and ordering");
        ui(() -> call("showFolders",NONE));searchFor("은행");rowClick(appRows().get(0),false);semanticTap("새 폴더");
        semantic("1개");enterName("전체");semanticTap("만들고 이동");
        check(inputNode(getUiAutomation().getRootInActiveWindow())!=null&&plan().equals(original),"Reserved new folder rejected in actual name dialog");
        enterName("가상 임시 단독");semanticTap("만들고 이동");
        check(plan().get("가상 임시 단독").size()==1,"Single-app draft explicitly allowed with warning");
        boolean blocked=false;try{ExmlEditor.build(snapshot,plan());}catch(IllegalArgumentException expected){blocked=true;}
        check(blocked,"Single-app draft remains blocked by actual EXML output validation");
        ui(() -> tap("result-undo"));check(Arrays.equals(activeBefore,planBytes()),"Single-app draft Undo restores saved plan");
        ui(() -> {call("showFolders",NONE);set("selecting",true);selected().clear();call("refresh",NONE);});
        move=tagged(activity.getWindow().getDecorView(),"selection-move");
        check(move!=null&&move.isShown()&&!move.isEnabled(),"Bottom move disabled at zero selection");
        ui(() -> call("navigateBack",NONE));check(Boolean.FALSE.equals(field("selecting"))&&Arrays.equals(activeBefore,planBytes()),"System/controller Back exits empty selection without mutation");
        ui(() -> call("showFolders",NONE));
    }
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
        if("manytargets".equals(scene)){crowdedCase();return;}
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
        placementUiChecks(original,snapshot);
        final String first="가상 보관함";
        if("folders".equals(scene)){ui(() -> call("showFolders",NONE));}
        else if("detail".equals(scene)||"selection".equals(scene)){
            ui(() -> call("showFolder",new Class<?>[]{String.class},first));waitForIdleSync();
            check(((ListView)field("list")).getAdapter().getCount()==4,"Folder detail contains only selected folder apps");
            if("selection".equals(scene))ui(() -> {ListView list=(ListView)field("list");View row=list.getChildAt(0);check(row!=null,"Rendered app row");list.getOnItemLongClickListener().onItemLongClick(list,row,0,list.getAdapter().getItemId(0));});
        }else if("search".equals(scene)){searchFor("지도");}
        else if("destination".equals(scene)||"newfolder".equals(scene)){
            searchFor("지도");rowClick(appRows().get(0),false);
            if("newfolder".equals(scene))semanticTap("새 폴더");
        }else if("result".equals(scene)){ui(() -> call("moveApps",new Class<?>[]{Collection.class,String.class},new ArrayList<>(original.get("미분류").subList(0,2)),"가상 보관함"));}
        else if("proposal".equals(scene))ui(() -> call("showProposal",new Class<?>[]{boolean.class},true));
        else if(!"regression".equals(scene))throw new IllegalArgumentException("Unknown scene "+scene);
        if(!"regression".equals(scene))capture(scene);
        JSONObject report=new JSONObject();report.put("assertions",assertions);report.put("scene",scene);report.put("apps",14);report.put("synthetic_emulator_only",true);report.put("proposal_cancel_no_mutation",true);report.put("apply_undo_bulk_persistence_verified",true);report.put("placement_native_controls_verified",true);report.put("protected_bytes_identical",true);report.put("native_launcher_restored",false);
        Files.write(new File(getTargetContext().getFilesDir(),"workbench-test-result.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));result.putString("result",report.toString());finish(Activity.RESULT_OK,result);return;
    }catch(Throwable t){try{capture("failure");}catch(Throwable ignored){}StringWriter text=new StringWriter();t.printStackTrace(new PrintWriter(text));result.putString("error",text.toString());}finish(Activity.RESULT_CANCELED,result);}
}
