package dev.local.oneuifolders;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import dev.local.oneuifolders.core.ExmlEditor;
import org.json.JSONArray;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline folder assignment editor. Stages files, never restores the launcher. */
public final class MainActivity extends Activity {
    private static final int OPEN = 10, SAVE = 11, EXPORT_PLAN = 12, IMPORT_PLAN = 13, SHIZUKU_PERMISSION = 20;
    private static final int LIMIT = 8 * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, List<String>> folders = new LinkedHashMap<>();
    private final LinkedHashMap<String, ExmlEditor.App> apps = new LinkedHashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private final JSONObject diagnostics = new JSONObject();
    private ExmlEditor.Snapshot snapshot;
    private byte[] source;
    private String sourceName = "", transport = "", selectedFolder = "전체", query = "";
    private TextView status, summary;
    private Spinner filter;
    private ListView list;
    private boolean busy, rebuildingFilter, binding;
    private IBackupService bridge;
    private ExmlEditor.Result pendingOutput;
    private String pendingName;
    private Shizuku.UserServiceArgs serviceArgs;
    private final Shizuku.OnBinderReceivedListener binderReceived = () -> runOnUiThread(() -> updateShizukuState());
    private final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(() -> { bridge = null; binding = false; note("Shizuku 연결이 끊겼습니다. 다시 연결하거나 ADB 대안을 쓰세요."); });
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, grant) -> runOnUiThread(() -> {
        if (code == SHIZUKU_PERMISSION && grant == PackageManager.PERMISSION_GRANTED) bindBridge();
        else note("Shizuku 권한이 거부됐습니다. 오프라인 편집과 PC ADB 대안은 사용할 수 있습니다.");
    });
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false; bridge = IBackupService.Stub.asInterface(binder);
            background("Shizuku 백업 목록 읽는 중", () -> {
                JSONObject info = new JSONObject(bridge.info());
                if (info.getInt("uid") != 2000) throw new IllegalStateException("안전을 위해 ADB shell 모드 Shizuku만 지원합니다.");
                String[] names = bridge.listBackups();
                runOnUiThread(() -> { putDiagnostic("shizuku_info", info); showBackups(names); });
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { bridge = null; binding = false; note("파일 서비스가 종료됐습니다."); }
    };
    private interface Work { void run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildScreen();
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(getPackageName(), BackupService.class.getName()))
                .daemon(false).processNameSuffix("homeup_files").debuggable(false).version(3);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        restoreLocalSession();
        updateShizukuState();
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private void buildScreen() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16),dp(12),dp(16),dp(10)); root.setBackgroundColor(Color.rgb(247,249,252)); setContentView(root);
        TextView title = text("One UI 폴더 정리",22); title.setTypeface(null,1); root.addView(title);
        root.addView(text("백업·복원은 Home Up에서 직접 · 여기서는 분류와 새 파일 준비만",13));
        LinearLayout sources = row(root); button(sources,"Shizuku로 백업 불러오기",v -> connect()); button(sources,"파일 불러오기",v -> choose(OPEN,"*/*",""));
        LinearLayout tools = row(root); button(tools,"ADB 대안",v -> fallback()); button(tools,"Home Up 열기",v -> openHomeUp()); button(tools,"연결 종료",v -> disconnect(true));
        status = text("준비 중",13); status.setTextIsSelectable(true); root.addView(status);
        summary = text("먼저 Home Up에서 백업을 만든 뒤 불러오세요.",16); summary.setTypeface(null,1); root.addView(summary);
        LinearLayout plans = row(root); button(plans,"분류안 저장",v -> choose(EXPORT_PLAN,"application/json","oneui-folder-plan.json")); button(plans,"분류안 불러오기",v -> choose(IMPORT_PLAN,"application/json","")); button(plans,"폴더 관리",v -> folderManager());
        LinearLayout actions = row(root); button(actions,"미분류만 추천",v -> suggest()); button(actions,"원본 분류로",v -> resetPlan());
        filter = new Spinner(this); root.addView(filter);
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p,View v,int position,long id) { if (!rebuildingFilter) { selectedFolder=(String)p.getItemAtPosition(position); refreshList(); } }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint("앱 이름·패키지 검색"); root.addView(search);
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();refreshList();} public void afterTextChanged(Editable e){} });
        list = new ListView(this); list.setDividerHeight(1); root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout output = row(root); button(output,"새 EXML 저장",v -> exportExml()); button(output,"Home Up에 적용 준비",v -> confirmStage());
        root.addView(text("원본은 보관합니다. 적용 준비는 파일만 추가하며 런처를 복원하지 않습니다.",12));
    }
    private TextView text(String value,int size) { TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(Color.rgb(25,35,49));v.setPadding(0,dp(4),0,dp(5));return v; }
    private LinearLayout row(LinearLayout root) { LinearLayout r=new LinearLayout(this);r.setOrientation(0);root.addView(r);return r; }
    private Button button(LinearLayout row,String label,View.OnClickListener action) { Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(dp(44));row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v -> { if(!busy) action.onClick(v); });return b; }
    private void note(String message) { if(status!=null)status.setText(message);putDiagnostic("status",message); }
    private void background(String message,Work task) {
        if(busy){note("진행 중인 작업이 끝난 뒤 다시 눌러 주세요.");return;}busy=true;if(list!=null)list.setEnabled(false);note(message);
        worker.execute(() -> { try{task.run();}catch(Exception e){runOnUiThread(() -> note("실패: "+e.getMessage()+"\nADB 대안 또는 다시 불러오기를 사용하세요."));putDiagnostic("last_error",e.getClass().getSimpleName()+": "+e.getMessage());}finally{runOnUiThread(() -> {busy=false;if(list!=null)list.setEnabled(true);});} });
    }
    private void updateShizukuState() {
        try { note(Shizuku.pingBinder() ? "Shizuku 실행 중 · 불러오기를 누르면 권한을 확인합니다." : "Shizuku가 실행되지 않았습니다. Shizuku 앱에서 시작하거나 ADB 대안을 쓰세요."); }
        catch(Exception e){note("Shizuku 없음 · 파일 불러오기와 ADB 대안을 사용할 수 있습니다.");}
    }
    private void connect() {
        try {
            if(!Shizuku.pingBinder()){fallback();return;}
            if(Shizuku.isPreV11())throw new IllegalStateException("Shizuku 11 이상이 필요합니다.");
            if(Shizuku.getUid()!=2000)throw new IllegalStateException("루트가 아닌 shell 모드 Shizuku만 지원합니다.");
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){Shizuku.requestPermission(SHIZUKU_PERMISSION);return;}
            if(bridge!=null){background("백업 목록 확인 중",() -> {String[] names=bridge.listBackups();runOnUiThread(() -> showBackups(names));});return;}
            bindBridge();
        }catch(Exception e){note("Shizuku 연결 실패: "+e.getMessage());}
    }
    private void bindBridge(){if(binding)return;try{binding=true;note("Shizuku 파일 서비스 연결 중");Shizuku.bindUserService(serviceArgs,connection);}catch(Exception e){binding=false;note("파일 서비스 연결 실패: "+e.getMessage());}}
    private void disconnect(boolean visible){
        try{if(bridge!=null||binding)Shizuku.unbindUserService(serviceArgs,connection,true);}catch(Exception ignored){}
        bridge=null;binding=false;if(visible)note("Shizuku 파일 서비스 종료. 편집한 분류안은 기기에 남아 있습니다.");
    }
    private void showBackups(String[] names){
        if(names.length==0){note("백업이 없습니다. Home Up에서 먼저 즉시 백업을 만드세요.");return;}
        note("백업 "+names.length+"개 · 원본으로 사용할 파일을 선택하세요.");
        new AlertDialog.Builder(this).setTitle("Home Up 백업 선택 (최신순)").setItems(names,(dialog,index) -> loadBridgeBackup(names[index])).setNegativeButton("취소",null).show();
    }
    private void loadBridgeBackup(String name){background("백업 읽기·검증 중",() -> {byte[] bytes;try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(bridge.openBackup(name))){bytes=read(input);}ExmlEditor.Snapshot parsed=ExmlEditor.parse(bytes);saveSource(bytes,name,"shizuku");runOnUiThread(() -> installSource(bytes,parsed,name,"shizuku"));});}
    private void installSource(byte[] bytes,ExmlEditor.Snapshot parsed,String name,String via){
        source=bytes;snapshot=parsed;sourceName=name;transport=via;apps.clear();labels.clear();for(ExmlEditor.App app:parsed.apps)apps.put(app.key,app);
        folders.clear();for(Map.Entry<String,List<String>> entry:parsed.folders.entrySet())folders.put(entry.getKey(),new ArrayList<>(entry.getValue()));
        selectedFolder="전체";savePlan();refresh();note("불러오기 완료 · "+apps.size()+"개 앱 / "+folders.size()+"개 폴더. 앱을 누르면 폴더를 바꿀 수 있습니다.");
        putDiagnostic("source_name",name);putDiagnostic("source_sha256",parsed.sourceSha256);putDiagnostic("source_transport",via);putDiagnostic("app_count",apps.size());
    }
    private void refresh(){
        if(summary==null)return;int invalid=0;for(List<String> f:folders.values())if(f.size()<2)invalid++;
        summary.setText(apps.size()+"개 앱 · "+folders.size()+"개 폴더"+(invalid>0?" · 2개 미만 폴더 "+invalid+"개 확인 필요":""));
        rebuildingFilter=true;ArrayList<String> names=new ArrayList<>();names.add("전체");names.addAll(folders.keySet());if(!names.contains(selectedFolder))selectedFolder="전체";filter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));filter.setSelection(names.indexOf(selectedFolder));rebuildingFilter=false;refreshList();
        putDiagnostic("folder_count",folders.size());putDiagnostic("current_plan_sha256",snapshot==null?"":ExmlEditor.sha256(planJson().toString().getBytes(StandardCharsets.UTF_8)));
    }
    private String label(ExmlEditor.App app){
        if(labels.containsKey(app.key))return labels.get(app.key);String value=app.packageName;
        try{ActivityInfo info=getPackageManager().getActivityInfo(new ComponentName(app.packageName,app.activity),0);value=info.loadLabel(getPackageManager()).toString();}
        catch(Exception ignored){try{value=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(app.packageName,0)).toString();}catch(Exception ignoredAgain){}}
        labels.put(app.key,value);return value;
    }
    private void refreshList(){
        if(list==null)return;ArrayList<String> keys=new ArrayList<>();String needle=query.toLowerCase(Locale.ROOT);
        for(Map.Entry<String,List<String>> folder:folders.entrySet())for(String key:folder.getValue()){
            ExmlEditor.App app=apps.get(key);if(app==null)continue;
            if(!selectedFolder.equals("전체")&&!selectedFolder.equals(folder.getKey()))continue;
            if(!needle.isEmpty()&&!(label(app)+" "+app.packageName).toLowerCase(Locale.ROOT).contains(needle))continue;keys.add(key);
        }
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_2,android.R.id.text1,keys){
            @Override public View getView(int position,View convert,ViewGroup parent){View row=super.getView(position,convert,parent);String key=getItem(position);ExmlEditor.App app=apps.get(key);((TextView)row.findViewById(android.R.id.text1)).setText(label(app));((TextView)row.findViewById(android.R.id.text2)).setText(folderOf(key)+"  ·  "+app.packageName);row.setContentDescription(label(app)+", "+folderOf(key));return row;}
        };list.setAdapter(adapter);list.setOnItemClickListener((p,v,position,id) -> moveDialog(keys.get(position)));
    }
    private String folderOf(String key){for(Map.Entry<String,List<String>> f:folders.entrySet())if(f.getValue().contains(key))return f.getKey();return "미분류";}
    private void moveDialog(String key){ArrayList<String> names=new ArrayList<>(folders.keySet());names.add("＋ 새 폴더");new AlertDialog.Builder(this).setTitle(label(apps.get(key))+" → 폴더").setItems(names.toArray(new String[0]),(d,index) -> {if(index==names.size()-1)askName("새 폴더 이름","",name -> {folders.put(name,new ArrayList<>());move(key,name);});else move(key,names.get(index));}).setNegativeButton("취소",null).show();}
    private void move(String key,String target){for(List<String> values:folders.values())values.remove(key);folders.get(target).add(key);folders.entrySet().removeIf(e -> e.getValue().isEmpty());savePlan();refresh();note("앱 이동: "+label(apps.get(key))+" → "+target);}
    private interface NameAction{void apply(String name);}
    private void askName(String title,String initial,NameAction action){EditText field=new EditText(this);field.setSingleLine(true);field.setText(initial);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("저장",null).setNegativeButton("취소",null).create();dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {String name=field.getText().toString();if(name.trim().isEmpty()||name.equals("전체")||(!name.equals(initial)&&folders.containsKey(name))){field.setError("빈 이름·전체·중복 폴더명은 쓸 수 없습니다.");return;}action.apply(name);dialog.dismiss();}));dialog.show();}
    private void folderManager(){
        if(snapshot==null){note("먼저 백업을 불러오세요.");return;}
        ArrayList<String> names=new ArrayList<>(folders.keySet());String[] labels=new String[names.size()];for(int i=0;i<names.size();i++)labels[i]=names.get(i)+" ("+folders.get(names.get(i)).size()+"개)";
        new AlertDialog.Builder(this).setTitle("폴더 이름·병합").setItems(labels,(d,index) -> {String name=names.get(index);new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"이름 바꾸기","다른 폴더로 모두 합치기"},(x,action) -> {if(action==0)askName("폴더 이름 바꾸기",name,newName -> {LinkedHashMap<String,List<String>> renamed=new LinkedHashMap<>();for(Map.Entry<String,List<String>> entry:folders.entrySet())renamed.put(entry.getKey().equals(name)?newName:entry.getKey(),entry.getValue());folders.clear();folders.putAll(renamed);savePlan();refresh();});else{ArrayList<String> targets=new ArrayList<>(folders.keySet());targets.remove(name);if(targets.isEmpty())return;new AlertDialog.Builder(this).setTitle("모두 옮길 폴더").setItems(targets.toArray(new String[0]),(y,which) -> {folders.get(targets.get(which)).addAll(folders.remove(name));savePlan();refresh();}).show();}}).show();}).setNegativeButton("닫기",null).show();
    }
    private void resetPlan(){if(snapshot==null)return;new AlertDialog.Builder(this).setTitle("불러온 백업의 폴더 구성으로 되돌릴까요?").setMessage("기기의 런처는 바뀌지 않습니다. 이 앱의 분류안만 초기화합니다.").setPositiveButton("되돌리기",(d,w) -> {folders.clear();for(Map.Entry<String,List<String>> e:snapshot.folders.entrySet())folders.put(e.getKey(),new ArrayList<>(e.getValue()));savePlan();refresh();}).setNegativeButton("취소",null).show();}
    private void suggest(){
        if(snapshot==null)return;List<String> pending=folders.get("미분류");if(pending==null||pending.isEmpty()){note("미분류 앱이 없습니다. 기존 폴더는 그대로 유지합니다.");return;}
        ArrayList<String> keys=new ArrayList<>(pending);folders.remove("미분류");for(String key:keys){String name="기타";try{int category=getPackageManager().getApplicationInfo(apps.get(key).packageName,0).category;switch(category){case ApplicationInfo.CATEGORY_GAME:name="게임";break;case ApplicationInfo.CATEGORY_AUDIO:case ApplicationInfo.CATEGORY_VIDEO:name="미디어";break;case ApplicationInfo.CATEGORY_IMAGE:name="사진·창작";break;case ApplicationInfo.CATEGORY_SOCIAL:name="소통·SNS";break;case ApplicationInfo.CATEGORY_NEWS:name="독서·학습";break;case ApplicationInfo.CATEGORY_MAPS:name="지도·교통";break;case ApplicationInfo.CATEGORY_PRODUCTIVITY:name="업무·생산성";break;default:break;}}catch(Exception ignored){}if(!folders.containsKey(name))folders.put(name,new ArrayList<>());folders.get(name).add(key);}savePlan();refresh();note("미분류만 기기 앱 카테고리로 추천했습니다. 모르는 앱은 기타이며 기존 폴더는 유지합니다.");
    }
    private void choose(int request,String mime,String name){
        if(request!=OPEN&&snapshot==null){note("먼저 백업을 불러오세요.");return;}Intent intent=new Intent(request==OPEN||request==IMPORT_PLAN?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);if(!name.isEmpty())intent.putExtra(Intent.EXTRA_TITLE,name);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try{startActivityForResult(intent,request);}catch(Exception e){note("파일 선택기 오류: "+e.getMessage());}
    }
    private void exportExml(){if(snapshot==null)return;try{pendingOutput=ExmlEditor.build(snapshot,folders);pendingName=System.currentTimeMillis()+".exml";choose(SAVE,"application/octet-stream",pendingName);}catch(Exception e){note("분류안 확인 필요: "+e.getMessage());}}
    private void confirmStage(){
        if(snapshot==null){note("먼저 백업을 불러오세요.");return;}if(bridge==null||!transport.equals("shizuku")){note("Shizuku에서 원본을 불러온 경우에만 기기에 바로 준비할 수 있습니다. 파일 저장 후 ADB 대안도 가능합니다.");return;}
        try{pendingOutput=ExmlEditor.build(snapshot,folders);pendingName=System.currentTimeMillis()+".exml";
            new AlertDialog.Builder(this).setTitle("새 백업 파일을 추가할까요?").setMessage(pendingOutput.appCount+"개 앱 / "+pendingOutput.folderCount+"개 폴더\n홈·도크·위젯 영역은 원본과 동일합니다.\n\n원본은 덮어쓰지 않습니다. 이 단계는 파일만 추가합니다. 실제 복원은 Home Up에서 직접 확인하세요.").setPositiveButton("새 파일 준비",(d,w) -> stage()).setNegativeButton("취소",null).show();
        }catch(Exception e){note("분류안 확인 필요: "+e.getMessage());}
    }
    private void stage(){final ExmlEditor.Result result=pendingOutput;final String name=pendingName;background("검증된 새 백업 저장 중",() -> {File local=new File(getFilesDir(),"converted.exml");write(local,result.bytes);String raw;try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(local,ParcelFileDescriptor.MODE_READ_ONLY)){raw=bridge.stage(sourceName,snapshot.sourceSha256,name,fd,result.outputSha256);}JSONObject response=new JSONObject(raw);if(!response.getString("sha256").equals(result.outputSha256)||response.getBoolean("launcher_restored"))throw new IllegalStateException("서비스 응답 검증 실패");write(new File(getFilesDir(),"last-stage.json"),response.toString(2).getBytes(StandardCharsets.UTF_8));putDiagnostic("last_stage",response);runOnUiThread(() -> note("준비 완료: "+name+"\n"+result.appCount+"개 앱 / "+result.folderCount+"개 폴더 · 원본 보관됨\nHome Up을 다시 열어 이 새 파일을 선택해 복원하세요. 아직 런처에는 적용하지 않았습니다."));});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();background("파일 처리 중",() -> {
        if(request==OPEN){byte[] bytes;try(InputStream input=getContentResolver().openInputStream(uri)){bytes=read(input);}ExmlEditor.Snapshot parsed=ExmlEditor.parse(bytes);String name=displayName(uri);saveSource(bytes,name,"saf");runOnUiThread(() -> installSource(bytes,parsed,name,"saf"));}
        else if(request==SAVE){if(pendingOutput==null)throw new IllegalStateException("변환 파일이 없습니다.");saveUri(uri,pendingOutput.bytes);putDiagnostic("last_export_sha256",pendingOutput.outputSha256);runOnUiThread(() -> note("새 EXML 저장·재읽기 검증 완료. Home Up 파일 이동은 ADB 안내를 참고하세요."));}
        else if(request==EXPORT_PLAN){byte[] bytes=planJson().toString(2).getBytes(StandardCharsets.UTF_8);saveUri(uri,bytes);runOnUiThread(() -> note("분류안 JSON 저장 완료. 개인 앱 목록이므로 공개하지 마세요."));}
        else if(request==IMPORT_PLAN){byte[] bytes;try(InputStream input=getContentResolver().openInputStream(uri)){bytes=read(input);}JSONObject plan=new JSONObject(new String(bytes,StandardCharsets.UTF_8));LinkedHashMap<String,List<String>> loaded=decodePlan(plan);runOnUiThread(() -> {folders.clear();folders.putAll(loaded);savePlan();refresh();note("분류안 불러오기 완료");});}
    });}
    private void saveUri(Uri uri,byte[] bytes)throws Exception{try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){if(output==null)throw new IOException("쓰기 스트림 없음");output.write(bytes);}try(InputStream input=getContentResolver().openInputStream(uri)){if(!Arrays.equals(bytes,read(input)))throw new IOException("저장 후 재읽기 검증 실패");}}
    private String displayName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "manual.exml";}
    private JSONObject planJson(){JSONObject plan=new JSONObject();try{plan.put("version",1);plan.put("source_sha256",snapshot==null?"":snapshot.sourceSha256);JSONArray groups=new JSONArray();for(Map.Entry<String,List<String>> entry:folders.entrySet()){JSONObject folder=new JSONObject();folder.put("name",entry.getKey());JSONArray members=new JSONArray();for(String key:entry.getValue()){ExmlEditor.App app=apps.get(key);JSONObject member=new JSONObject();member.put("package",app.packageName);member.put("activity",app.activity);members.put(member);}folder.put("apps",members);groups.put(folder);}plan.put("folders",groups);}catch(Exception e){throw new IllegalStateException(e);}return plan;}
    private LinkedHashMap<String,List<String>> decodePlan(JSONObject plan)throws Exception{
        if(snapshot==null||!snapshot.sourceSha256.equals(plan.getString("source_sha256")))throw new IllegalArgumentException("분류안이 현재 원본 백업과 다릅니다.");LinkedHashMap<String,List<String>> result=new LinkedHashMap<>();HashSet<String> seen=new HashSet<>();JSONArray groups=plan.getJSONArray("folders");for(int i=0;i<groups.length();i++){JSONObject group=groups.getJSONObject(i);String name=group.getString("name");if(name.trim().isEmpty()||name.equals("전체")||result.containsKey(name))throw new IllegalArgumentException("폴더 이름 오류");ArrayList<String> members=new ArrayList<>();JSONArray values=group.getJSONArray("apps");for(int j=0;j<values.length();j++){JSONObject v=values.getJSONObject(j);String key=v.getString("package")+"\t"+v.getString("activity");if(!apps.containsKey(key)||!seen.add(key))throw new IllegalArgumentException("누락·중복 또는 알 수 없는 앱");members.add(key);}if(members.isEmpty())throw new IllegalArgumentException("빈 폴더");result.put(name,members);}if(seen.size()!=apps.size())throw new IllegalArgumentException("분류안 앱이 누락됐습니다.");return result;
    }
    private void saveSource(byte[] bytes,String name,String via)throws Exception{write(new File(getFilesDir(),"source.exml"),bytes);JSONObject meta=new JSONObject();meta.put("name",name);meta.put("transport",via);write(new File(getFilesDir(),"source-meta.json"),meta.toString().getBytes(StandardCharsets.UTF_8));}
    private void savePlan(){if(snapshot==null)return;try{write(new File(getFilesDir(),"plan.json"),planJson().toString(2).getBytes(StandardCharsets.UTF_8));}catch(Exception e){note("분류안 저장 실패: "+e.getMessage());}}
    private void restoreLocalSession(){try{File file=new File(getFilesDir(),"source.exml");if(!file.exists())return;byte[] bytes;try(InputStream in=new FileInputStream(file)){bytes=read(in);}JSONObject meta=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(),"source-meta.json").toPath()),StandardCharsets.UTF_8));File savedPlan=new File(getFilesDir(),"plan.json");byte[] saved=savedPlan.exists()?java.nio.file.Files.readAllBytes(savedPlan.toPath()):null;installSource(bytes,ExmlEditor.parse(bytes),meta.getString("name"),meta.optString("transport","saf"));if(saved!=null){LinkedHashMap<String,List<String>> loaded=decodePlan(new JSONObject(new String(saved,StandardCharsets.UTF_8)));folders.clear();folders.putAll(loaded);savePlan();refresh();note("저장된 백업·분류안을 복원했습니다. 실제 기기 적용 전 최신 백업인지 확인하세요.");}}catch(Exception e){note("저장된 분류안을 읽지 못했습니다: "+e.getMessage());}}
    private static byte[] read(InputStream input)throws Exception{if(input==null)throw new IOException("읽기 스트림 없음");try(ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=input.read(buf))!=-1){if(output.size()+n>LIMIT)throw new IOException("8 MiB 제한 초과");output.write(buf,0,n);}return output.toByteArray();}}
    private static void write(File file,byte[] data)throws Exception{File temp=new File(file.getParentFile(),file.getName()+".tmp");try(FileOutputStream output=new FileOutputStream(temp)){output.write(data);output.getFD().sync();}if(!temp.renameTo(file))throw new IOException("로컬 파일 저장 실패");}
    private synchronized void putDiagnostic(String key,Object value){try{diagnostics.put(key,value);write(new File(getFilesDir(),"diagnostics.json"),diagnostics.toString(2).getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}}
    private void fallback(){new AlertDialog.Builder(this).setTitle("Shizuku가 안 될 때 · PC ADB").setMessage("1. PC에서 tools/adb_backups.py pull --serial <기기> --output original.exml\n2. original.exml을 Download로 옮겨 이 앱의 파일 불러오기\n3. 분류 후 새 EXML 저장\n4. PC에서 stage 명령으로 새 파일만 Home Up 폴더에 추가\n5. Home Up에서 직접 복원\n\nGitHub 문서: docs/shizuku-and-adb.md\nShizuku 없이도 오프라인 편집은 됩니다. 일반 파일 권한만으로 Android/data를 열 수 있는 것은 아닙니다.").setPositiveButton("확인",null).show();}
    private void openHomeUp(){try{disconnect(false);Intent intent=getPackageManager().getLaunchIntentForPackage("com.samsung.android.app.homestar");if(intent==null)throw new IllegalStateException("Home Up을 먼저 설치하세요.");startActivity(intent);}catch(Exception e){note(e.getMessage());}}
    @Override protected void onDestroy(){disconnect(false);Shizuku.removeBinderReceivedListener(binderReceived);Shizuku.removeBinderDeadListener(binderDead);Shizuku.removeRequestPermissionResultListener(permissionResult);worker.shutdown();super.onDestroy();}
}
