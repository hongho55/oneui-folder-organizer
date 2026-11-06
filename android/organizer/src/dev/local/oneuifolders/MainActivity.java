package dev.local.oneuifolders;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
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
import dev.local.oneuifolders.planning.FolderPlanner;
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
    // Stable IDs and tags are deliberately independent of generated resource IDs.
    public static final int ID_LIST = 0x4f0101, ID_SEARCH = 0x4f0102,
            ID_MENU = 0x4f0103, ID_PREPARE = 0x4f0104, ID_RECOMMEND = 0x4f0105,
            ID_UNDO = 0x4f0106, ID_APPLY = 0x4f0107, ID_CANCEL = 0x4f0108,
            ID_MOVE = 0x4f0109, ID_SELECT = 0x4f010a, ID_BACK = 0x4f010b;
    private TextView status, summary, contextTitle, empty;
    private EditText search;
    private LinearLayout actions;
    private Button back, prepare, menuButton, fileImport;
    private LinearLayout emptyPanel;
    private final LinkedHashSet<String> selectedKeys = new LinkedHashSet<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private LinkedHashMap<String,List<String>> undoPlan;
    private FolderPlanner.Proposal proposal;
    private boolean selecting, planReadFailed;
    private int surface, foreground, secondary, primary, container;
    private Object systemBack;
    // Keep API 33 types out of the activity's fields on Android 11/12.
    private static final class BackApi33 {
        static Object register(MainActivity activity) {
            OnBackInvokedCallback callback = () -> activity.navigateBack();
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void unregister(MainActivity activity,Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback)callback);
        }
    }
    private ListView list;
    private boolean busy, binding;
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
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        surface = Color.parseColor(dark ? "#151C24" : "#F7F9FC");
        foreground = Color.parseColor(dark ? "#EDF2FA" : "#192331");
        secondary = Color.parseColor(dark ? "#B7C6D8" : "#4E6074");
        primary = Color.parseColor(dark ? "#ACD0FF" : "#174F85");
        container = Color.parseColor(dark ? "#253B51" : "#DEEDFF");
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildScreen();
        if (android.os.Build.VERSION.SDK_INT >= 33) systemBack=BackApi33.register(this);
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(getPackageName(), BackupService.class.getName()))
                .daemon(false).processNameSuffix("homeup_files").debuggable(false).version(3);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        restoreLocalSession();
        if (state != null) {
            selectedFolder = state.getString("folder", "전체");
            search.setText(state.getString("query", ""));
            refresh();
        }
        if (snapshot == null) updateShizukuState();
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(8),dp(16),dp(8));
        root.setBackgroundColor(surface);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(dp(16)+bars.left,dp(8)+bars.top,dp(16)+bars.right,dp(8)+bars.bottom);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        LinearLayout bar = row(root);
        back = button(bar,"뒤로",v -> navigateBack()); back.setId(ID_BACK); back.setTag("folders-back");
        TextView title = text("One UI 폴더 정리",20); title.setTypeface(null,1);
        bar.addView(title,new LinearLayout.LayoutParams(0,-2,2));
        Button menu = button(bar,"더보기",v -> showTools(v)); menu.setId(ID_MENU); menu.setTag("tools_menu"); menuButton=menu;
        contextTitle = text("내 폴더",22); contextTitle.setTypeface(null,1); root.addView(contextTitle);
        summary = text("",14); summary.setTextColor(secondary); root.addView(summary);
        search = new EditText(this); search.setId(ID_SEARCH); search.setTag("app_search");
        search.setSingleLine(true); search.setTextSize(16); search.setMinHeight(dp(48));
        search.setTextColor(foreground); search.setHintTextColor(secondary); search.setHint("폴더·앱 이름·패키지 검색");
        root.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int f){}
            public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();refreshList();}
            public void afterTextChanged(Editable e){}
        });
        HorizontalScrollView actionScroll=new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);root.addView(actionScroll);
        actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        actionScroll.addView(actions,new HorizontalScrollView.LayoutParams(-2,-2));
        FrameLayout content = new FrameLayout(this);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        list = new ListView(this); list.setId(ID_LIST); list.setTag("folder_browser");
        list.setDividerHeight(0); list.setClipToPadding(false); list.setPadding(0,dp(8),0,dp(8));
        content.addView(list,new FrameLayout.LayoutParams(-1,-1));
        emptyPanel=new LinearLayout(this);emptyPanel.setOrientation(LinearLayout.VERTICAL);
        emptyPanel.setGravity(android.view.Gravity.CENTER);
        ScrollView emptyScroll=new ScrollView(this);emptyScroll.setFillViewport(true);emptyScroll.addView(emptyPanel);
        empty = text("",16); empty.setGravity(android.view.Gravity.CENTER); empty.setPadding(dp(20),dp(24),dp(20),dp(24));
        emptyPanel.addView(empty);
        LinearLayout fileRow=row(emptyPanel);
        fileImport=button(fileRow,"파일에서 원본 불러오기",v -> choose(OPEN,"*/*",""));fileImport.setTag("import-file");
        content.addView(emptyScroll,new FrameLayout.LayoutParams(-1,-1)); list.setEmptyView(emptyScroll);
        status = text("준비 중",13); status.setTextColor(secondary); status.setMaxLines(3);
        status.setMinHeight(dp(48)); status.setTag("session_status"); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("작업 상태").setMessage(status.getText()).setPositiveButton("닫기",null).show());
        root.addView(status);
        LinearLayout output = row(root);
        prepare = button(output,"새 파일 준비",v -> prepareFile()); prepare.setId(ID_PREPARE); prepare.setTag("prepare");
        prepare.setBackgroundTintList(ColorStateList.valueOf(container)); prepare.setTextColor(primary);
        TextView footnote = text("로컬 분류안만 편집합니다. 실제 복원은 Home Up에서 직접 합니다.",12);
        footnote.setTextColor(secondary); root.addView(footnote);
        refresh();
    }
    private TextView text(String value,int size) {
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(foreground);
        v.setPadding(0,dp(4),0,dp(5));return v;
    }
    private LinearLayout row(LinearLayout root) {
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);root.addView(r);return r;
    }
    private Button button(LinearLayout row,String label,View.OnClickListener action) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);
        b.setTextColor(primary);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(dp(48));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.setMargins(dp(4),dp(4),dp(4),dp(4));
        if(row==actions){params.width=-2;params.weight=0;b.setMinWidth(dp(96));}
        row.addView(b,params);b.setOnClickListener(v -> { if(!busy) action.onClick(v); });return b;
    }
    private void showTools(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        String[] names={"Shizuku로 백업 불러오기","파일에서 원본 불러오기","분류안 가져오기","분류안 내보내기","폴더 관리","Home Up 열기","PC ADB 안내","Shizuku 연결 종료","원본 분류로 되돌리기"};
        for(int i=0;i<names.length;i++) menu.getMenu().add(0,i,i,names[i]);
        for(int id=0;id<names.length;id++)menu.getMenu().findItem(id).setEnabled(!busy&&proposal==null);
        if(snapshot==null)for(int id:new int[]{2,3,4,8})menu.getMenu().findItem(id).setEnabled(false);
        menu.setOnMenuItemClickListener(item -> {
            if(busy || proposal!=null)return true;
            switch(item.getItemId()) {
                case 0:connect();break;case 1:choose(OPEN,"*/*","");break;
                case 2:choose(IMPORT_PLAN,"application/json","");break;
                case 3:choose(EXPORT_PLAN,"application/json","oneui-folder-plan.json");break;
                case 4:folderManager();break;case 5:openHomeUp();break;case 6:fallback();break;
                case 7:disconnect(true);break;case 8:resetPlan();break;
                default:break;
            }return true;
        });menu.show();
    }
    private void prepareFile() {
        if(snapshot==null){connect();return;}
        if(bridge!=null && transport.equals("shizuku"))confirmStage();else exportExml();
    }
    private void navigateBack() {
        if(proposal!=null){cancelProposal();return;}
        if(selecting){selecting=false;selectedKeys.clear();refresh();return;}
        if(!query.isEmpty()){search.setText("");refresh();return;}
        if(!selectedFolder.equals("전체")){showFolders();return;}
        finish();
    }
    @Override public void onBackPressed(){navigateBack();}
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("folder",selectedFolder);out.putString("query",query);super.onSaveInstanceState(out);
    }
    private void note(String message) { if(status!=null)status.setText(message);putDiagnostic("status",message); }
    private void background(String message,Work task) {
        if(busy){note("진행 중인 작업이 끝난 뒤 다시 눌러 주세요.");return;}busy=true;if(list!=null)list.setEnabled(false);refresh();note(message);
        worker.execute(() -> { try{task.run();}catch(Exception e){runOnUiThread(() -> note("실패: "+e.getMessage()+"\nADB 대안 또는 다시 불러오기를 사용하세요."));putDiagnostic("last_error",e.getClass().getSimpleName()+": "+e.getMessage());}finally{runOnUiThread(() -> {busy=false;if(list!=null)list.setEnabled(true);refresh();});} });
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
        source=bytes;snapshot=parsed;sourceName=name;transport=via;apps.clear();labels.clear();icons.clear();
        for(ExmlEditor.App app:parsed.apps)apps.put(app.key,app);
        folders.clear();folders.putAll(FolderPlanner.copy(parsed.folders));
        selectedFolder="전체";selectedKeys.clear();selecting=false;proposal=null;undoPlan=null;planReadFailed=false;
        // Read the saved plan before any write. A failed decode must leave its bytes intact.
        File legacy=new File(getFilesDir(),"plan.json");
        File archived=planFile(parsed.sourceSha256);
        File candidate=archived.exists()?archived:null;
        if(legacy.exists()) {
            try {
                JSONObject saved=new JSONObject(new String(java.nio.file.Files.readAllBytes(legacy.toPath()),StandardCharsets.UTF_8));
                if(parsed.sourceSha256.equals(saved.optString("source_sha256")))candidate=legacy;
            }catch(Exception e){planReadFailed=true;}
        }
        try {
            if(candidate!=null)foldersFrom(decodePlan(new JSONObject(new String(java.nio.file.Files.readAllBytes(candidate.toPath()),StandardCharsets.UTF_8))));
        }catch(Exception e){planReadFailed=true;}
        refresh();
        note(planReadFailed ? "저장안을 읽지 못했습니다. 저장 파일은 보존했습니다. 더보기에서 분류안을 내보내거나 올바른 분류안을 가져오세요. 원본 분류로 되돌리기를 선택하면 새 편집을 시작합니다."
                : "불러옴 · "+name+" · 로컬 분류안 자동 저장. 실제 런처는 바뀌지 않았습니다.");
        putDiagnostic("source_name",name);putDiagnostic("source_sha256",parsed.sourceSha256);putDiagnostic("source_transport",via);putDiagnostic("app_count",apps.size());
    }
    private void foldersFrom(Map<String,List<String>> plan){folders.clear();folders.putAll(FolderPlanner.copy(plan));}
    private void refresh(){
        if(summary==null)return;
        int invalid=0;for(List<String> f:folders.values())if(f.size()<2)invalid++;
        if(!selectedFolder.equals("전체")&&!folders.containsKey(selectedFolder))selectedFolder="전체";
        contextTitle.setText(proposal!=null?"자동 정리 제안":selectedFolder.equals("전체")?"내 폴더":selectedFolder);
        summary.setText(proposal!=null
                ? proposal.movedCount+"개 이동 예정 · "+proposal.folders.size()+"개 폴더 · 검토 "+proposal.uncertainKeys.size()+"개 · 2개 미만 "+proposal.singletonCount+"개\n아래 전체 제안을 확인하세요. 적용 전까지 저장안은 그대로입니다."
                : snapshot==null?"Home Up에서 백업을 만든 뒤 불러오세요."
                : apps.size()+"개 앱 · "+folders.size()+"개 폴더"+(invalid>0?" · 2개 미만 "+invalid+"개: 파일 준비 전 병합 필요":"")+(selecting?" · "+selectedKeys.size()+"개 선택":""));
        back.setVisibility(proposal!=null||selecting||!selectedFolder.equals("전체")||!query.isEmpty()?View.VISIBLE:View.GONE);
        prepare.setText(snapshot==null?"Shizuku로 백업 불러오기":"새 파일 준비");
        prepare.setEnabled(!busy&&proposal==null&&!planReadFailed);
        search.setVisibility(proposal==null&&snapshot!=null?View.VISIBLE:View.GONE);
        menuButton.setEnabled(!busy);search.setEnabled(!busy);
        fileImport.setVisibility(snapshot==null?View.VISIBLE:View.GONE);fileImport.setEnabled(!busy);
        actions.removeAllViews();
        if(proposal!=null) {
            Button cancel=button(actions,"취소",v -> cancelProposal());cancel.setId(ID_CANCEL);
            Button apply=button(actions,"로컬 분류안에 적용",v -> applyProposal());apply.setId(ID_APPLY);apply.setTag("proposal-apply");
        } else if(snapshot!=null) {
            if(selecting) {
                Button select=button(actions,"현재 결과 모두 선택",v -> {selectedKeys.addAll(visibleKeys());refresh();});select.setId(ID_SELECT);
                Button move=button(actions,"선택 이동 ("+selectedKeys.size()+")",v -> bulkMoveDialog());move.setId(ID_MOVE);move.setTag("selection-move");move.setEnabled(!selectedKeys.isEmpty()&&!busy);
            } else {
                Button recommend=button(actions,"자동 정리",v -> suggest());recommend.setId(ID_RECOMMEND);recommend.setTag("auto");recommend.setEnabled(!planReadFailed&&!busy);
                Button select=button(actions,selectedFolder.equals("전체")?"앱 선택":"선택·이동",v -> {selecting=true;refresh();});select.setId(ID_SELECT);
                if(!selectedFolder.equals("전체"))button(actions,"이름·병합",v -> folderActions(selectedFolder));
            }
            if(undoPlan!=null){Button undo=button(actions,"되돌리기",v -> undo());undo.setId(ID_UNDO);undo.setTag("undo");}
        }
        for(int i=0;i<actions.getChildCount();i++)if(busy)actions.getChildAt(i).setEnabled(false);
        refreshList();
        putDiagnostic("folder_count",folders.size());
        putDiagnostic("current_plan_sha256",snapshot==null?"":ExmlEditor.sha256(planJson().toString().getBytes(StandardCharsets.UTF_8)));
    }
    private String label(ExmlEditor.App app){
        if(labels.containsKey(app.key))return labels.get(app.key);String value=app.packageName;
        try{ActivityInfo info=getPackageManager().getActivityInfo(new ComponentName(app.packageName,app.activity),0);value=info.loadLabel(getPackageManager()).toString();}
        catch(Exception ignored){try{value=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(app.packageName,0)).toString();}catch(Exception ignoredAgain){}}
        labels.put(app.key,value);return value;
    }
    private boolean matches(String key) {
        ExmlEditor.App app=apps.get(key);String needle=query.toLowerCase(Locale.ROOT);
        return (label(app)+" "+app.packageName+" "+folderOf(key)).toLowerCase(Locale.ROOT).contains(needle);
    }
    private List<String> visibleKeys() {
        ArrayList<String> result=new ArrayList<>();
        for(Map.Entry<String,List<String>> group:folders.entrySet())
            if(selectedFolder.equals("전체")||selectedFolder.equals(group.getKey()))
                for(String key:group.getValue())if(matches(key))result.add(key);
        return result;
    }
    private Drawable icon(String key) {
        if(icons.containsKey(key))return icons.get(key);
        ExmlEditor.App app=apps.get(key);Drawable value;
        try{value=getPackageManager().getActivityIcon(new ComponentName(app.packageName,app.activity));}
        catch(Exception e){try{value=getPackageManager().getApplicationIcon(app.packageName);}catch(Exception missing){value=getPackageManager().getDefaultActivityIcon();}}
        icons.put(key,value);return value;
    }
    private void refreshList(){
        if(list==null)return;
        boolean browsing=proposal==null&&!selecting&&selectedFolder.equals("전체");
        ArrayList<String> rows=new ArrayList<>();
        if(proposal!=null) {
            for(Map.Entry<String,List<String>> group:proposal.folders.entrySet()) {
                rows.add(group.getKey());rows.addAll(group.getValue());
            }
        } else if(browsing) {
            for(Map.Entry<String,List<String>> group:folders.entrySet()) {
                boolean match=group.getKey().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
                for(String key:group.getValue())if(matches(key))match=true;
                if(match)rows.add(group.getKey());
            }
        } else rows.addAll(visibleKeys());
        empty.setText(snapshot==null?"백업 속 폴더를 불러와 정리하세요.\n\n앱 이동과 자동 정리는 이 기기의 로컬 분류안에만 저장됩니다."
                : "검색 결과가 없습니다.\n검색어를 지우거나 다른 폴더를 확인하세요.");
        list.setAdapter(new BaseAdapter() {
            public int getCount(){return rows.size();}
            public String getItem(int position){return rows.get(position);}
            public long getItemId(int position){return position;}
            public boolean isEnabled(int position){return proposal==null;}
            public View getView(int position,View convert,ViewGroup parent) {
                String key=getItem(position);
                boolean heading=proposal!=null&&!apps.containsKey(key);
                LinearLayout row=new LinearLayout(MainActivity.this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12),dp(12),dp(12),dp(12));row.setMinimumHeight(dp(72));
                row.setTag((browsing||heading?"folder:":"app:")+key);
                if(selectedKeys.contains(key))row.setBackgroundColor(container);
                LinearLayout words=new LinearLayout(MainActivity.this);words.setOrientation(LinearLayout.VERTICAL);
                if(browsing) {
                    LinearLayout thumbnails=new LinearLayout(MainActivity.this);
                    thumbnails.setOrientation(LinearLayout.VERTICAL);
                    List<String> members=folders.get(key);
                    for(int i=0;i<Math.min(2,members.size());i++) {
                        ImageView im=new ImageView(MainActivity.this);im.setImageDrawable(icon(members.get(i)));im.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                        thumbnails.addView(im,new LinearLayout.LayoutParams(dp(24),dp(24)));
                    }
                    LinearLayout.LayoutParams thumbParams=new LinearLayout.LayoutParams(dp(40),-2);thumbParams.setMarginEnd(dp(12));row.addView(thumbnails,thumbParams);
                    TextView name=text(key,18);name.setTypeface(null,1);words.addView(name);
                    TextView count=text(members.size()+"개 앱"+(members.size()<2?" · 병합 필요":"")+" · 열기",14);count.setTextColor(secondary);words.addView(count);
                } else if(heading) {
                    TextView title=text(key+" · "+proposal.folders.get(key).size()+"개",18);title.setTypeface(null,1);title.setTextColor(primary);words.addView(title);
                } else {
                    if(selecting){CheckBox check=new CheckBox(MainActivity.this);check.setChecked(selectedKeys.contains(key));check.setClickable(false);check.setFocusable(false);check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(check,new LinearLayout.LayoutParams(dp(48),dp(48)));}
                    ImageView im=new ImageView(MainActivity.this);im.setImageDrawable(icon(key));im.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(dp(40),dp(40));imageParams.setMarginEnd(dp(12));row.addView(im,imageParams);
                    words.addView(text(label(apps.get(key)),16));
                    String detail=folderOf(key)+" · "+apps.get(key).packageName;
                    if(proposal!=null) {
                        String target=folderIn(proposal.folders,key);
                        detail=folderOf(key).equals(target)?"유지 · "+target:folderOf(key)+" → "+target;
                        detail+="\n"+(proposal.uncertainKeys.contains(key)?"검토 필요 · ":"")+proposal.reasons.getOrDefault(key,"기존 배정 유지");
                        detail+="\n"+apps.get(key).packageName;
                    }
                    TextView metadata=text(detail,13);metadata.setTextColor(secondary);words.addView(metadata);
                }
                row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
                if(selecting)row.setContentDescription(label(apps.get(key))+", "+folderOf(key)+(selectedKeys.contains(key)?", 선택됨":", 선택 안 됨"));
                return row;
            }
        });
        list.setOnItemClickListener((p,v,position,id) -> {
            if(busy||proposal!=null)return;
            String key=rows.get(position);
            if(browsing){showFolder(key);}
            else if(selecting){toggleSelection(key);}else moveDialog(key);
        });
        list.setOnItemLongClickListener((p,v,position,id) -> {
            if(busy||proposal!=null||browsing)return false;
            selecting=true;toggleSelection(rows.get(position));return true;
        });
    }
    private void toggleSelection(String key){if(!selectedKeys.remove(key))selectedKeys.add(key);refresh();}
    private String folderIn(Map<String,List<String>> plan,String key){for(Map.Entry<String,List<String>> f:plan.entrySet())if(f.getValue().contains(key))return f.getKey();return "미분류";}
    private String folderOf(String key){return folderIn(folders,key);}
    private void moveDialog(String key){selectedKeys.clear();selectedKeys.add(key);bulkMoveDialog();}
    private void bulkMoveDialog(){
        if(selectedKeys.isEmpty())return;
        final ArrayList<String> moving=new ArrayList<>(selectedKeys);
        ArrayList<String> names=new ArrayList<>(folders.keySet());names.add("새 폴더 만들기");
        new AlertDialog.Builder(this).setTitle(moving.size()+"개 앱 이동").setItems(names.toArray(new String[0]),(d,index) -> {
            if(index==names.size()-1)askName("새 폴더 이름","",name -> bulkMove(moving,name));
            else bulkMove(moving,names.get(index));
        }).setNegativeButton("취소",null).show();
    }
    private void move(String key,String target){bulkMove(Collections.singletonList(key),target);}
    public void bulkMove(Collection<String> keys,String target){
        if(busy||snapshot==null||proposal!=null)return;
        try{commitPlan(FolderPlanner.move(folders,keys,target),"앱 이동 완료 · "+target);}
        catch(IllegalArgumentException e){note(e.getMessage());}
    }
    private void commitPlan(LinkedHashMap<String,List<String>> next,String message){
        if(planReadFailed){note("저장안이 손상되어 덮어쓰지 않았습니다. 올바른 분류안을 가져오거나 원본 분류로 되돌리기를 선택하세요.");return;}
        FolderPlanner.validate(plannerApps(),next);
        if(new ArrayList<>(folders.entrySet()).equals(new ArrayList<>(next.entrySet()))){note("변경할 내용이 없습니다.");return;}
        undoPlan=FolderPlanner.copy(folders);foldersFrom(next);pendingOutput=null;
        selectedKeys.clear();selecting=false;savePlan();refresh();note(message+" · 로컬 분류안만 변경됨");
    }
    private interface NameAction{void apply(String name);}
    private void askName(String title,String initial,NameAction action){EditText field=new EditText(this);field.setSingleLine(true);field.setMinHeight(dp(48));field.setText(initial);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("저장",null).setNegativeButton("취소",null).create();dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {String name=field.getText().toString();if(name.trim().isEmpty()||name.equals("전체")||(!name.equals(initial)&&folders.containsKey(name))){field.setError("빈 이름·전체·중복 폴더명은 쓸 수 없습니다.");return;}try{action.apply(name);dialog.dismiss();}catch(IllegalArgumentException e){field.setError(e.getMessage());}}));dialog.show();}
    private void folderManager(){
        if(snapshot==null){note("먼저 백업을 불러오세요.");return;}
        ArrayList<String> names=new ArrayList<>(folders.keySet());
        new AlertDialog.Builder(this).setTitle("폴더 관리").setItems(names.toArray(new String[0]),(d,index) -> folderActions(names.get(index))).setNegativeButton("닫기",null).show();
    }
    private void folderActions(String name){
        new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"이름 바꾸기","다른 폴더로 합치기"},(d,index) -> {
            if(index==0)askName("폴더 이름 바꾸기",name,newName -> {
                commitPlan(FolderPlanner.rename(folders,name,newName),"폴더 이름 변경");
                if(folders.containsKey(newName))selectedFolder=newName;refresh();
            });else {
                ArrayList<String> targets=new ArrayList<>(folders.keySet());targets.remove(name);
                if(targets.isEmpty()){note("합칠 다른 폴더가 없습니다.");return;}
                new AlertDialog.Builder(this).setTitle(name+"의 앱을 모두 합칠 폴더").setItems(targets.toArray(new String[0]),(dialog,which) -> {
                    String target=targets.get(which);commitPlan(FolderPlanner.merge(folders,name,target),"폴더 병합 완료");
                    if(!folders.containsKey(name))selectedFolder=target;refresh();
                }).setNegativeButton("취소",null).show();
            }
        }).setNegativeButton("취소",null).show();
    }
    private void resetPlan(){
        if(snapshot==null)return;
        new AlertDialog.Builder(this).setTitle("원본의 폴더 구성으로 되돌릴까요?")
                .setMessage("현재 분류안을 바꿉니다. 한 번 되돌릴 수 있으며 실제 런처는 바뀌지 않습니다.")
                .setPositiveButton("원본 분류로",(d,w) -> {
                    try {
                        preserveSavedPlan();planReadFailed=false;
                        commitPlan(FolderPlanner.copy(snapshot.folders),"원본 분류로 되돌림");savePlan();
                    }catch(Exception e){note("저장안 보존 실패: "+e.getMessage());}
                }).setNegativeButton("취소",null).show();
    }
    private List<FolderPlanner.AppInfo> plannerApps(){
        ArrayList<FolderPlanner.AppInfo> result=new ArrayList<>();
        for(ExmlEditor.App app:apps.values()) {
            int category=-1;try{category=getPackageManager().getApplicationInfo(app.packageName,0).category;}catch(Exception ignored){}
            result.add(new FolderPlanner.AppInfo(app.key,app.packageName,label(app),category));
        }return result;
    }
    private void suggest(){
        if(snapshot==null)return;
        new AlertDialog.Builder(this).setTitle("자동 정리 범위").setMessage("오프라인 규칙과 설치 앱 카테고리로 추천합니다. 알 수 없는 앱은 검토 대상으로 표시합니다. 먼저 제안만 보여드립니다.")
                .setPositiveButton("미분류만",(d,w) -> showProposal(false))
                .setNeutralButton("모든 앱 재정리",(d,w) -> showProposal(true))
                .setNegativeButton("취소",null).show();
    }
    private void showProposal(boolean regroupAll){generateProposal(regroupAll);}
    public void generateProposal(boolean regroupAll){
        if(busy||snapshot==null||planReadFailed)return;
        try {
            proposal=createProposal(regroupAll);
            selectedKeys.clear();selecting=false;refresh();list.setSelection(0);
            note((regroupAll?"모든 앱":"미분류만")+" 제안 · 아직 분류안을 변경하지 않았습니다. 2개 미만 폴더는 적용 후 합쳐야 파일을 준비할 수 있습니다.");
        }catch(Exception e){proposal=null;refresh();note("제안 생성 실패: "+e.getMessage());}
    }
    public void cancelProposal(){if(busy)return;proposal=null;refresh();note("제안을 취소했습니다. 저장된 분류안은 그대로입니다.");}
    public void applyProposal(){
        if(busy||proposal==null)return;
        applyProposal(proposal);
    }
    private FolderPlanner.Proposal createProposal(boolean regroupAll){
        if(snapshot==null)throw new IllegalStateException("먼저 백업을 불러오세요.");
        FolderPlanner.Proposal result=FolderPlanner.suggest(plannerApps(),FolderPlanner.copy(folders),regroupAll);
        FolderPlanner.validate(plannerApps(),result.folders);return result;
    }
    private void applyProposal(FolderPlanner.Proposal value){
        if(busy||snapshot==null||value==null||planReadFailed)return;
        FolderPlanner.validate(plannerApps(),value.folders);
        LinkedHashMap<String,List<String>> next=FolderPlanner.copy(value.folders);proposal=null;
        commitPlan(next,"제안 적용 완료");refresh();
    }
    private void undoLastChange(){undo();}
    private void moveApps(Collection<String> keys,String target){bulkMove(keys,target);}
    private void showFolder(String name){
        if(busy||proposal!=null||!folders.containsKey(name))return;
        selectedFolder=name;selectedKeys.clear();selecting=false;search.setText("");refresh();list.setSelection(0);
    }
    private void showFolders(){
        if(busy||proposal!=null)return;
        selectedFolder="전체";selectedKeys.clear();selecting=false;search.setText("");refresh();list.setSelection(0);
    }
    public void undo(){
        if(busy||undoPlan==null||proposal!=null||planReadFailed)return;
        foldersFrom(undoPlan);undoPlan=null;pendingOutput=null;selectedKeys.clear();selecting=false;
        savePlan();refresh();note("직전 편집을 되돌렸습니다. 실제 런처는 바뀌지 않았습니다.");
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
        else if(request==IMPORT_PLAN){byte[] bytes;try(InputStream input=getContentResolver().openInputStream(uri)){bytes=read(input);}JSONObject plan=new JSONObject(new String(bytes,StandardCharsets.UTF_8));LinkedHashMap<String,List<String>> loaded=decodePlan(plan);runOnUiThread(() -> {try{preserveSavedPlan();planReadFailed=false;commitPlan(loaded,"분류안 불러오기 완료");savePlan();}catch(Exception e){note("저장안 보존 실패: "+e.getMessage());}});}
    });}
    private void saveUri(Uri uri,byte[] bytes)throws Exception{try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){if(output==null)throw new IOException("쓰기 스트림 없음");output.write(bytes);}try(InputStream input=getContentResolver().openInputStream(uri)){if(!Arrays.equals(bytes,read(input)))throw new IOException("저장 후 재읽기 검증 실패");}}
    private String displayName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "manual.exml";}
    private JSONObject planJson(){JSONObject plan=new JSONObject();try{plan.put("version",1);plan.put("source_sha256",snapshot==null?"":snapshot.sourceSha256);JSONArray groups=new JSONArray();for(Map.Entry<String,List<String>> entry:folders.entrySet()){JSONObject folder=new JSONObject();folder.put("name",entry.getKey());JSONArray members=new JSONArray();for(String key:entry.getValue()){ExmlEditor.App app=apps.get(key);JSONObject member=new JSONObject();member.put("package",app.packageName);member.put("activity",app.activity);members.put(member);}folder.put("apps",members);groups.put(folder);}plan.put("folders",groups);}catch(Exception e){throw new IllegalStateException(e);}return plan;}
    private LinkedHashMap<String,List<String>> decodePlan(JSONObject plan)throws Exception{
        if(snapshot==null||!snapshot.sourceSha256.equals(plan.getString("source_sha256")))throw new IllegalArgumentException("분류안이 현재 원본 백업과 다릅니다.");LinkedHashMap<String,List<String>> result=new LinkedHashMap<>();HashSet<String> seen=new HashSet<>();JSONArray groups=plan.getJSONArray("folders");for(int i=0;i<groups.length();i++){JSONObject group=groups.getJSONObject(i);String name=group.getString("name");if(name.trim().isEmpty()||name.equals("전체")||result.containsKey(name))throw new IllegalArgumentException("폴더 이름 오류");ArrayList<String> members=new ArrayList<>();JSONArray values=group.getJSONArray("apps");for(int j=0;j<values.length();j++){JSONObject v=values.getJSONObject(j);String key=v.getString("package")+"\t"+v.getString("activity");if(!apps.containsKey(key)||!seen.add(key))throw new IllegalArgumentException("누락·중복 또는 알 수 없는 앱");members.add(key);}if(members.isEmpty())throw new IllegalArgumentException("빈 폴더");result.put(name,members);}if(seen.size()!=apps.size())throw new IllegalArgumentException("분류안 앱이 누락됐습니다.");return result;
    }
    private File planFile(String hash){return new File(getFilesDir(),"plan-"+hash+".json");}
    private void preserveSavedPlan()throws Exception {
        File existing=new File(getFilesDir(),"plan.json");if(!existing.exists())return;
        byte[] bytes=java.nio.file.Files.readAllBytes(existing.toPath());
        String hash;
        try{hash=new JSONObject(new String(bytes,StandardCharsets.UTF_8)).getString("source_sha256");}
        catch(Exception e){hash="unreadable-"+ExmlEditor.sha256(bytes);}
        if(!hash.matches("[a-f0-9]{64}"))hash="unreadable-"+ExmlEditor.sha256(bytes);
        write(new File(getFilesDir(),"plan-preserved-"+ExmlEditor.sha256(bytes)+".json"),bytes);
        write(planFile(hash),bytes);
    }
    private void saveSource(byte[] bytes,String name,String via)throws Exception{
        preserveSavedPlan();
        write(new File(getFilesDir(),"source.exml"),bytes);JSONObject meta=new JSONObject();
        meta.put("name",name);meta.put("transport",via);
        write(new File(getFilesDir(),"source-meta.json"),meta.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void savePlan(){
        if(snapshot==null||planReadFailed)return;
        try{
            byte[] bytes=planJson().toString(2).getBytes(StandardCharsets.UTF_8);
            preserveSavedPlan();write(planFile(snapshot.sourceSha256),bytes);
            write(new File(getFilesDir(),"plan.json"),bytes);
        }catch(Exception e){note("분류안 저장 실패: "+e.getMessage());}
    }
    private void restoreLocalSession(){
        try{
            File file=new File(getFilesDir(),"source.exml");if(!file.exists())return;
            byte[] bytes;try(InputStream in=new FileInputStream(file)){bytes=read(in);}
            JSONObject meta=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(),"source-meta.json").toPath()),StandardCharsets.UTF_8));
            installSource(bytes,ExmlEditor.parse(bytes),meta.getString("name"),meta.optString("transport","saf"));
        }catch(Exception e){note("저장된 백업·분류안을 읽지 못했습니다. 기존 파일은 보존했습니다: "+e.getMessage());}
    }
    private static byte[] read(InputStream input)throws Exception{if(input==null)throw new IOException("읽기 스트림 없음");try(ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=input.read(buf))!=-1){if(output.size()+n>LIMIT)throw new IOException("8 MiB 제한 초과");output.write(buf,0,n);}return output.toByteArray();}}
    private static void write(File file,byte[] data)throws Exception{File temp=new File(file.getParentFile(),file.getName()+".tmp");try(FileOutputStream output=new FileOutputStream(temp)){output.write(data);output.getFD().sync();}if(!temp.renameTo(file))throw new IOException("로컬 파일 저장 실패");}
    private synchronized void putDiagnostic(String key,Object value){try{diagnostics.put(key,value);write(new File(getFilesDir(),"diagnostics.json"),diagnostics.toString(2).getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}}
    private void fallback(){new AlertDialog.Builder(this).setTitle("Shizuku가 안 될 때 · PC ADB").setMessage("1. PC에서 tools/adb_backups.py pull --serial <기기> --output original.exml\n2. original.exml을 Download로 옮겨 이 앱의 파일 불러오기\n3. 분류 후 새 EXML 저장\n4. PC에서 stage 명령으로 새 파일만 Home Up 폴더에 추가\n5. Home Up에서 직접 복원\n\nGitHub 문서: docs/shizuku-and-adb.md\nShizuku 없이도 오프라인 편집은 됩니다. 일반 파일 권한만으로 Android/data를 열 수 있는 것은 아닙니다.").setPositiveButton("확인",null).show();}
    private void openHomeUp(){try{disconnect(false);Intent intent=getPackageManager().getLaunchIntentForPackage("com.samsung.android.app.homestar");if(intent==null)throw new IllegalStateException("Home Up을 먼저 설치하세요.");startActivity(intent);}catch(Exception e){note(e.getMessage());}}
    @Override protected void onDestroy(){if(android.os.Build.VERSION.SDK_INT>=33)BackApi33.unregister(this,systemBack);disconnect(false);Shizuku.removeBinderReceivedListener(binderReceived);Shizuku.removeBinderDeadListener(binderDead);Shizuku.removeRequestPermissionResultListener(permissionResult);worker.shutdown();super.onDestroy();}
}
