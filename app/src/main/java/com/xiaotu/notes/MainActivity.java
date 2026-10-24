package com.xiaotu.notes;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import static com.xiaotu.notes.Ui.*;

public final class MainActivity extends Activity {
    private static java.lang.ref.WeakReference<MainActivity> liveActivity = new java.lang.ref.WeakReference<>(null);
    private static final int WRAP=ViewGroup.LayoutParams.WRAP_CONTENT, MATCH=ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int EXPORT_TEXT=720;
    private static final String APPEARANCE="appearance", FONT_SIZE="font_size";
    private static final float[] FONT_SCALES={0f,.85f,1f,1.15f,1.3f};
    private NoteStore store;
    private LinearLayout root,noteList;
    private FrameLayout contentHost;
    private View currentScreen,outgoingScreen;
    private SplashView splash;
    private boolean launching,nativeSplashExited;
    private TextView countLabel,categoryLabel;
    private EditText search;
    private NoteEditor editor;
    private Dialog categories;
    private int visibleLimit=40;
    private long folder,notebook,editorId;
    private boolean newDraft;
    private String filter="all",query="";
    private Map<Long,String> folderNames=Collections.emptyMap();
    private Map<Long,NoteStore.Notebook> notebookIndex=Collections.emptyMap();
    private final Set<Long> expandedFolders=new HashSet<>();
    private final Map<Long,NoteRow> noteRows=new HashMap<>();
    private final Set<Long> appearedNoteIds=new HashSet<>();
    private String listContext="";
    private boolean emptyListShown;
    private View emptyState,loadMore;
    private boolean selectionMode;
    private final Set<Long> selectedNoteIds=new HashSet<>();
    private List<Long> matchingSelectionIds=Collections.emptyList();
    private LinearLayout normalHeader,selectionHeader,selectionFooter;
    private TextView selectionCount,selectAllButton,deleteSelectionButton;
    private View createButton;
    private SwipeNoteLayout openedSwipe;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable searchRefresh=this::renderList;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private long seenSyncGeneration;
    private final SyncActivity.SyncHost syncHost = new SyncActivity.SyncHost() {
        @Override public boolean prepareSync() { return flushForSync(); }
        @Override public void syncFinished() { refreshAfterSync(); }
    };

    public static boolean flushForSync() {
        MainActivity current = liveActivity.get();
        if (current != null && current.store != null && current.seenSyncGeneration != SyncActivity.syncGeneration()) current.refreshAfterSync();
        return current == null || current.editor == null
            || (!current.editor.isImporting() && current.editor.save());
    }

    private void refreshAfterSync() {
        seenSyncGeneration = SyncActivity.syncGeneration();
        clearSelection();
        if (editor != null) {
            long previousId = editorId;
            boolean fresh = newDraft;
            editor.dispose(); editor = null; editorId = 0; newDraft = false;
            NoteStore.Note updated = store.getNote(previousId);
            if (updated != null && updated.deletedAt == 0) openEditor(previousId, fresh);
            else render(false);
        } else render(false);
    }

    private static int fontSizeChoice(Context context){
        int choice=context.getSharedPreferences(APPEARANCE,MODE_PRIVATE).getInt(FONT_SIZE,0);
        return choice>=0 && choice<FONT_SCALES.length?choice:0;
    }
    @Override protected void attachBaseContext(Context base){
        super.attachBaseContext(base);
        applyFontPreference(this, base);
    }
    static void applyFontPreference(android.app.Activity activity, Context base){
        float scale=FONT_SCALES[fontSizeChoice(base)];
        if(scale>0f){
            android.content.res.Configuration override=new android.content.res.Configuration();
            override.fontScale=scale;
            activity.applyOverrideConfiguration(override);
        }
    }

    @Override public void onCreate(Bundle state){
        setTheme(R.style.AppTheme);
        super.onCreate(state);
        liveActivity = new java.lang.ref.WeakReference<>(this);
        launching=state==null;
        nativeSplashExited=Build.VERSION.SDK_INT<31;
        if(Build.VERSION.SDK_INT>=31)getSplashScreen().setOnExitAnimationListener(view->{view.remove();nativeSplashExited=true;startSplash();});
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null)controller.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        root=column(this);root.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));return insets;
        });
        contentHost=new FrameLayout(this);root.addView(contentHost,new LinearLayout.LayoutParams(MATCH,0,1));
        setContentView(root);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::onBackPressed);
        try{store=new NoteStore(this);store.activeCount();seenSyncGeneration=SyncActivity.syncGeneration();}catch(RuntimeException e){fatal(e);return;}
        if(state!=null){
            folder=state.getLong("folder",0);notebook=state.getLong("notebook",0);
            filter=state.getString("filter","all");query=state.getString("query","");
            if("checklist".equals(filter))filter="all";
            if(state.getInt("uiVersion",0)<3 && folder>0){
                List<NoteStore.Notebook> books=store.notebooks(folder);
                if(!books.isEmpty())notebook=books.get(0).id;else folder=0;
            }
            long[] expanded=state.getLongArray("expandedFolders");
            if(expanded!=null){for(long id:expanded)expandedFolders.add(id);}
            else if(folder>0)expandedFolders.add(folder);
            long id=state.getLong("editorId",0);
            if(id>0 && store.getNote(id)!=null){openEditor(id,state.getBoolean("newDraft",false));if(editor!=null && !state.getBoolean("discardEditorStateAfterSync",false))editor.restoreState(state);return;}
        }
        render();
        if(launching)showSplash();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        // Selection is intentionally cleared on recreation, so hidden notes cannot remain selected.
        boolean syncing=SyncActivity.syncInProgress() || seenSyncGeneration!=SyncActivity.syncGeneration();
        state.putBoolean("discardEditorStateAfterSync",syncing);
        if(editor!=null && !syncing){editor.save();editor.captureState(state);}
        state.putInt("uiVersion",3);state.putLong("notebook",notebook);state.putLong("folder",folder);state.putString("filter",filter);state.putString("query",query);
        long[] expanded=new long[expandedFolders.size()];int index=0;for(long id:expandedFolders)expanded[index++]=id;state.putLongArray("expandedFolders",expanded);
        state.putLong("editorId",editorId);state.putBoolean("newDraft",newDraft);super.onSaveInstanceState(state);
    }
    @Override protected void onResume(){super.onResume();if(store!=null){if(seenSyncGeneration!=SyncActivity.syncGeneration())refreshAfterSync();SyncActivity.onForeground(this,store,syncHost);}}
    @Override protected void onPause(){SyncActivity.onBackground(this);closeSwipe();dismissSplash(true);finishPageTransition();if(editor!=null && !SyncActivity.syncInProgress() && seenSyncGeneration==SyncActivity.syncGeneration())editor.save();super.onPause();}
    @Override protected void onDestroy(){SyncActivity.onBackground(this);if(liveActivity.get()==this)liveActivity.clear();finishPageTransition();handler.removeCallbacksAndMessages(null);if(splash!=null)splash.cancel();Motion.cancelTree(root);if(editor!=null)editor.dispose();if(categories!=null)categories.dismiss();io.shutdown();super.onDestroy();}
    @Override public void onBackPressed(){
        if(splash!=null){dismissSplash(true);return;}
        if(editor!=null){editor.onBack();return;}
        if(selectionMode){exitSelection();return;}
        if(!query.isEmpty()){query="";render(false);return;}
        if(notebook!=0 || folder!=0 || !"all".equals(filter)){notebook=0;folder=0;filter="all";render(false);return;}
        finish();
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(editor!=null)editor.onActivityResult(requestCode,resultCode,data);
        if(requestCode==EXPORT_TEXT && resultCode==RESULT_OK && data!=null && data.getData()!=null)exportText(data.getData());
    }
    private int d(float value){return dp(this,value);}
    private void finishPageTransition(){
        View outgoing=outgoingScreen;outgoingScreen=null;
        if(outgoing!=null){contentHost.getOverlay().remove(outgoing);Motion.cancelTree(outgoing);}
        Motion.finishPage(currentScreen);
    }
    private void present(View screen,boolean forward){
        closeSwipe();
        View previous=currentScreen;
        float previousAlpha=previous==null?1f:previous.getAlpha(),previousX=previous==null?0f:previous.getTranslationX();
        finishPageTransition();
        root.setBackgroundColor(editor!=null?WHITE:BG);
        if(screen.getBackground()==null)screen.setBackgroundColor(editor!=null?WHITE:BG);
        boolean transition=!launching && previous!=null && previous.isLaidOut() && Motion.allowed(this);
        if(previous!=null){
            Motion.cancelTree(previous);previous.clearFocus();
            previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            contentHost.removeView(previous);
            if(transition){
                // Overlay views are visual only: the incoming page owns touch and focus immediately.
                contentHost.getOverlay().add(previous);outgoingScreen=previous;
                previous.setAlpha(previousAlpha);previous.setTranslationX(previousX);
            }
        }
        currentScreen=screen;contentHost.addView(screen,0,new FrameLayout.LayoutParams(MATCH,MATCH));
        if(transition)Motion.page(screen,previous,forward,this::finishPageTransition);
        else if(!launching)Motion.page(screen,forward);
    }
    private void showSplash(){
        if(!Motion.allowed(this)){launching=false;return;}
        splash=new SplashView(this);
        contentHost.addView(splash,new FrameLayout.LayoutParams(MATCH,MATCH));
        if(currentScreen!=null)currentScreen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if(nativeSplashExited)startSplash();
    }
    private void startSplash(){if(splash!=null)splash.start(()->dismissSplash(false));}
    private void dismissSplash(boolean immediate){
        SplashView opening=splash;if(opening==null)return;
        launching=false;opening.cancel();opening.animate().cancel();opening.setClickable(false);
        if(currentScreen!=null)currentScreen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if(immediate || !Motion.allowed(this) || isFinishing() || isDestroyed()){
            contentHost.removeView(opening);if(splash==opening)splash=null;return;
        }
        if(currentScreen!=null)Motion.page(currentScreen,true);
        opening.animate().alpha(0f).setDuration(180).setInterpolator(new android.view.animation.AccelerateInterpolator())
            .withEndAction(()->{contentHost.removeView(opening);if(splash==opening)splash=null;}).start();
    }
    private String categoryTitle(){
        if("favorite".equals(filter))return "我的收藏";
        if("trash".equals(filter))return "最近删除";
        if(notebook>0){NoteStore.Notebook book=notebookIndex.get(notebook);if(book!=null)return book.name;}
        if(folder==-1)return "未分类";
        return "全部笔记";
    }
    private void render(){render(true);}
    private void render(boolean forward){
        if(editor!=null)return;
        handler.removeCallbacks(searchRefresh);visibleLimit=40;
        Map<Long,String> names=new HashMap<>();for(NoteStore.Folder item:store.folders())names.put(item.id,item.name);folderNames=Collections.unmodifiableMap(names);
        Map<Long,NoteStore.Notebook> books=new HashMap<>();for(NoteStore.Notebook item:store.notebooks(0))books.put(item.id,item);notebookIndex=Collections.unmodifiableMap(books);
        if(notebook>0){NoteStore.Notebook selected=books.get(notebook);if(selected==null){notebook=0;folder=0;}else folder=selected.folderId;}
        String context=folder+":"+notebook+":"+filter;
        if(!context.equals(listContext) || "trash".equals(filter))clearSelection();
        if(noteList!=null && currentScreen!=null && !(currentScreen instanceof NoteEditor) && context.equals(listContext)){
            categoryLabel.setText(categoryTitle());
            if(categoryLabel.getParent() instanceof View)((View)categoryLabel.getParent()).setContentDescription(categoryTitle()+"，选择分类");
            if(!query.contentEquals(search.getText())){search.setText(query);search.setSelection(search.length());handler.removeCallbacks(searchRefresh);}
            renderList();updateSelectionUi();return;
        }
        listContext=context;noteRows.clear();emptyState=null;loadMore=null;createButton=null;
        noteList=null;
        FrameLayout host=new FrameLayout(this);
        LinearLayout screen=column(this);host.addView(screen,new FrameLayout.LayoutParams(MATCH,MATCH));
        LinearLayout header=column(this);pad(header,20,4,0);
        LinearLayout titleRow=row(this);normalHeader=titleRow;
        LinearLayout titleButton=row(this);titleButton.setMinimumHeight(d(48));
        TextView title=text(this,categoryTitle(),20,INK,true);categoryLabel=title;title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(d(Math.max(72,getResources().getConfiguration().screenWidthDp-180)));
        titleButton.addView(title,lp(WRAP,WRAP));
        {LinearLayout.LayoutParams arrow=lp(d(14),d(14));arrow.leftMargin=d(6);titleButton.addView(new IconView(this,"chevronDown",MUTED),arrow);
            titleButton.setContentDescription(categoryTitle()+"，选择分类");titleButton.setFocusable(true);ripple(titleButton,Color.TRANSPARENT,10);titleButton.setOnClickListener(v->showCategories(false));}
        titleRow.addView(titleButton,new LinearLayout.LayoutParams(0,WRAP,1));
        countLabel=text(this,"",12,MUTED,false);countLabel.setSingleLine(true);countLabel.setMaxWidth(d(80));countLabel.setEllipsize(TextUtils.TruncateAt.END);titleRow.addView(countLabel,lp(WRAP,WRAP));
        LinearLayout.LayoutParams menuParams=lp(d(48),d(48));menuParams.leftMargin=d(4);titleRow.addView(iconButton("more","更多选项",INK,this::mainMenu),menuParams);
        header.addView(titleRow);
        selectionHeader=row(this);selectionHeader.setMinimumHeight(d(48));
        TextView cancelSelection=button(this,"取消",Color.TRANSPARENT,MUTED);cancelSelection.setTextSize(13);pad(cancelSelection,0,0,0);cancelSelection.setOnClickListener(v->exitSelection());selectionHeader.addView(cancelSelection,lp(d(48),d(48)));
        selectionCount=text(this,"已选 0",15,INK,true);selectionCount.setSingleLine(true);selectionCount.setGravity(Gravity.CENTER);selectionHeader.addView(selectionCount,new LinearLayout.LayoutParams(0,WRAP,1));
        selectAllButton=button(this,"全选",Color.TRANSPARENT,ACCENT_TEXT);selectAllButton.setTextSize(13);pad(selectAllButton,8,0,0);selectAllButton.setOnClickListener(v->selectAllNotes());selectionHeader.addView(selectAllButton,lp(WRAP,d(48)));header.addView(selectionHeader);
        screen.addView(header);
        LinearLayout searchBox=row(this);searchBox.setBackground(new android.graphics.drawable.InsetDrawable(shape(this,SURFACE,10),0,d(5),0,d(5)));pad(searchBox,12,0,0);
        searchBox.addView(new IconView(this,"search",MUTED),lp(d(17),d(17)));
        search=new EditText(this);search.setTextSize(13);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setSingleLine(true);
        search.setHint("搜索笔记");search.setContentDescription(search.getHint());search.setBackground(null);
        search.setPadding(d(8),d(8),0,d(8));search.setMinHeight(d(48));search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search,new LinearLayout.LayoutParams(0,WRAP,1));
        FrameLayout clear=iconButton("close","清空搜索",MUTED,v->search.setText(""));searchBox.addView(clear,lp(d(48),d(48)));
        LinearLayout.LayoutParams searchParams=lp(MATCH,WRAP);searchParams.setMargins(d(20),d(4),d(20),d(8));screen.addView(searchBox,searchParams);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        noteList=column(this);pad(noteList,20,4,80);noteList.setClipToPadding(false);noteList.setClipChildren(false);scroll.addView(noteList,new ScrollView.LayoutParams(MATCH,WRAP));screen.addView(scroll,new LinearLayout.LayoutParams(MATCH,0,1));
        selectionFooter=column(this);pad(selectionFooter,20,0,8);deleteSelectionButton=button(this,"删除（0）",DESTRUCTIVE,WHITE);deleteSelectionButton.setOnClickListener(v->deleteSelected());selectionFooter.addView(deleteSelectionButton,lp(MATCH,d(48)));screen.addView(selectionFooter,lp(MATCH,WRAP));
        search.setText(query);search.setSelection(search.length());clear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}
            public void onTextChanged(CharSequence s,int a,int before,int count){
                if(!query.contentEquals(s)){selectedNoteIds.clear();matchingSelectionIds=Collections.emptyList();closeSwipe();}
                query=s.toString();visibleLimit=40;updateSelectionUi();Motion.visible(clear,!query.isEmpty());handler.removeCallbacks(searchRefresh);handler.postDelayed(searchRefresh,180);
            }});
        search.setOnEditorActionListener((v,action,event)->{hideKeyboard();return false;});
        renderList();
        if(!"trash".equals(filter)){
            FrameLayout create=iconButton("edit","新建笔记",WHITE,v->newNote());createButton=create;create.setTooltipText("写笔记");
            create.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});
            ripple(create,INK,14);create.setElevation(d(1));
            FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(d(48),d(48),Gravity.BOTTOM|Gravity.END);fp.setMargins(d(20),0,d(20),d(20));host.addView(create,fp);
        }
        updateSelectionUi();
        present(host,forward);
    }
    private FrameLayout iconButton(String name,String label,int color,View.OnClickListener click){
        FrameLayout box=new FrameLayout(this);box.addView(new IconView(this,name,color),new FrameLayout.LayoutParams(d(20),d(20),Gravity.CENTER));
        box.setMinimumWidth(d(48));box.setMinimumHeight(d(48));box.setContentDescription(label);box.setFocusable(true);ripple(box,Color.TRANSPARENT,12);box.setOnClickListener(click);return box;
    }
    private void renderList(){
        if(noteList==null || editor!=null)return;
        List<NoteStore.Note> notes;int total;
        try{
            notes=store.loadNotes(query,folder,notebook,filter,visibleLimit,0);total=store.countNotes(query,folder,notebook,filter);
            if(selectionMode){matchingSelectionIds=store.noteIds(query,folder,notebook,filter);selectedNoteIds.retainAll(matchingSelectionIds);}
        }
        catch(RuntimeException e){showError("无法读取笔记",e);return;}
        updateSelectionUi();
        Motion.text(countLabel,query.trim().isEmpty()?total+" 条":"找到 "+total+" 条");
        if(loadMore!=null){noteList.removeView(loadMore);loadMore=null;}
        if(notes.isEmpty()){
            for(NoteRow row:noteRows.values())Motion.cancelTree(row.view);
            noteRows.clear();noteList.removeAllViews();
            noteList.setGravity(Gravity.CENTER);LinearLayout empty=column(this);empty.setGravity(Gravity.CENTER);pad(empty,16,20,20);
            String title="还没有笔记",description="点右下角，开始记录";
            if("favorite".equals(filter)){title="暂无收藏";description="在笔记的更多菜单中添加收藏";}
            if("trash".equals(filter)){title="最近删除为空";description="删除的笔记会保留在这里";}
            if(!query.trim().isEmpty()){title="没有找到相关内容";description="换个关键词试试";}
            IconView illustration=new IconView(this,"document",MUTED);empty.addView(illustration,lp(d(28),d(28)));gap(empty,16);
            TextView heading=text(this,title,16,INK,true);heading.setGravity(Gravity.CENTER);empty.addView(heading);gap(empty,8);TextView hint=text(this,description,13,MUTED,false);hint.setGravity(Gravity.CENTER);hint.setLineSpacing(d(3),1);empty.addView(hint);noteList.addView(empty,lp(MATCH,WRAP));emptyState=empty;
            if(!emptyListShown)Motion.enter(empty,0);emptyListShown=true;return;
        }
        if(emptyState!=null){Motion.cancelTree(emptyState);noteList.removeView(emptyState);emptyState=null;}
        emptyListShown=false;noteList.setGravity(Gravity.TOP);
        Set<Long> incoming=new HashSet<>();for(NoteStore.Note note:notes)incoming.add(note.id);
        Iterator<Map.Entry<Long,NoteRow>> remaining=noteRows.entrySet().iterator();
        while(remaining.hasNext()){Map.Entry<Long,NoteRow> entry=remaining.next();if(!incoming.contains(entry.getKey())){Motion.cancelTree(entry.getValue().view);noteList.removeView(entry.getValue().view);remaining.remove();}}
        int index=0,appearing=0;
        for(NoteStore.Note n:notes){
            String location=locationName(n.notebookId);NoteRow cached=noteRows.get(n.id);
            if(cached==null || !cached.matches(n,location,selectionMode,selectedNoteIds.contains(n.id))){
                if(cached!=null){Motion.cancelTree(cached.view);noteList.removeView(cached.view);}
                cached=new NoteRow(n,location,noteRow(n),selectionMode,selectedNoteIds.contains(n.id));noteRows.put(n.id,cached);
            }
            View row=cached.view;
            if(noteList.indexOfChild(row)!=index){
                if(row.getParent()==noteList)noteList.removeView(row);
                LinearLayout.LayoutParams params=lp(MATCH,WRAP);params.bottomMargin=d(8);noteList.addView(row,index,params);
            }
            if(!appearedNoteIds.contains(n.id) && appearing<8)Motion.enter(row,appearing++);
            index++;
        }
        appearedNoteIds.addAll(incoming);
        if(total>notes.size()){
            TextView more=button(this,"加载更多",SURFACE,ACCENT_TEXT);more.setOnClickListener(v->{visibleLimit+=40;renderList();});
            LinearLayout.LayoutParams params=lp(MATCH,WRAP);params.topMargin=d(16);noteList.addView(more,params);loadMore=more;
        }
    }
    private static final class NoteRow{
        final NoteStore.Note note;final String location;final View view;final boolean selecting,checked;
        NoteRow(NoteStore.Note note,String location,View view,boolean selecting,boolean checked){this.note=note;this.location=location;this.view=view;this.selecting=selecting;this.checked=checked;}
        boolean matches(NoteStore.Note next,String nextLocation,boolean nextSelecting,boolean nextChecked){
            return selecting==nextSelecting && checked==nextChecked && note.updatedAt==next.updatedAt && note.notebookId==next.notebookId && note.favorite==next.favorite && note.pinned==next.pinned
                && Objects.equals(note.title,next.title) && Objects.equals(note.body,next.body) && Objects.equals(note.type,next.type)
                && Objects.equals(note.checklist,next.checklist) && Objects.equals(note.attachments,next.attachments) && Objects.equals(note.conflictOf,next.conflictOf) && location.equals(nextLocation);
        }
    }
    private View noteRow(NoteStore.Note n){
        LinearLayout item=row(this);item.setGravity(Gravity.TOP);pad(item,12,12,12);ripple(item,SURFACE,12);
        if(selectionMode){
            boolean checked=selectedNoteIds.contains(n.id);FrameLayout mark=new FrameLayout(this);mark.setBackground(checked?shape(this,ACCENT,10):outline(this,Color.TRANSPARENT,10,MUTED));mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if(checked)mark.addView(new IconView(this,"check",WHITE),new FrameLayout.LayoutParams(d(14),d(14),Gravity.CENTER));
            LinearLayout.LayoutParams markerParams=lp(d(20),d(20));markerParams.rightMargin=d(10);markerParams.topMargin=d(1);item.addView(mark,markerParams);
        }
        LinearLayout content=column(this);item.addView(content,new LinearLayout.LayoutParams(0,WRAP,1));
        String value=displayText(n);int imageCount=0;try{imageCount=new JSONArray(n.attachments).length();}catch(JSONException ignored){}
        int firstBreak=value.indexOf('\n');String heading=firstBreak>=0?value.substring(0,firstBreak).trim():value;
        TextView body=text(this,heading.isEmpty()?(imageCount>0?"图片笔记":"空白笔记"):heading,15,INK,true);body.setMaxLines(2);body.setEllipsize(TextUtils.TruncateAt.END);body.setLineSpacing(d(2),1);content.addView(body);
        String preview=firstBreak>=0?value.substring(firstBreak+1).trim().replace('\n',' '):"";
        if(!preview.isEmpty()){gap(content,5);TextView excerpt=text(this,preview,13,MUTED,false);excerpt.setMaxLines(2);excerpt.setEllipsize(TextUtils.TruncateAt.END);excerpt.setLineSpacing(d(5),1);content.addView(excerpt);}
        gap(content,8);LinearLayout meta=row(this);String description=new SimpleDateFormat("M月d日 HH:mm",Locale.CHINA).format(new Date(n.updatedAt));
        TextView date=text(this,description,12,MUTED,false);date.setSingleLine(true);date.setEllipsize(TextUtils.TruncateAt.END);meta.addView(date,new LinearLayout.LayoutParams(0,WRAP,1));
        if(n.favorite){LinearLayout.LayoutParams p=lp(d(15),d(15));p.leftMargin=d(8);meta.addView(new IconView(this,"star",ACCENT),p);}
        if(n.pinned){LinearLayout.LayoutParams p=lp(d(14),d(14));p.leftMargin=d(8);meta.addView(new IconView(this,"pin",MUTED),p);}
        if(imageCount>0){LinearLayout.LayoutParams p=lp(d(15),d(15));p.leftMargin=d(8);meta.addView(new IconView(this,"image",MUTED),p);meta.addView(text(this," "+imageCount,12,MUTED,false));}
        if(n.conflictOf!=null && !n.conflictOf.isEmpty()){TextView conflict=text(this,"冲突副本",12,ACCENT_TEXT,false);LinearLayout.LayoutParams p=lp(WRAP,WRAP);p.leftMargin=d(8);meta.addView(conflict,p);}
        NoteStore.Notebook assigned=notebookIndex.get(n.notebookId);
        if(notebook==0 && assigned!=null){
            TextView bookName=text(this,assigned.name,12,MUTED,false);bookName.setSingleLine(true);bookName.setEllipsize(TextUtils.TruncateAt.END);bookName.setMaxWidth(d(96));
            LinearLayout.LayoutParams p=lp(WRAP,WRAP);p.leftMargin=d(8);meta.addView(bookName,p);
        }
        content.addView(meta);
        item.setContentDescription(noteLabel(n)+(n.favorite?"，已收藏":"")+(selectionMode?(selectedNoteIds.contains(n.id)?"，已选中":"，未选中"):("trash".equals(filter)?"，点按恢复或永久删除":"，点按编辑，长按更多操作，左滑删除")));item.setFocusable(true);
        long noteId=n.id;
        if(selectionMode)item.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(CheckBox.class.getName());info.setCheckable(true);info.setChecked(selectedNoteIds.contains(noteId));}});
        item.setOnClickListener(v->{if(selectionMode){toggleSelection(noteId);return;}try{NoteStore.Note latest=store.getNote(noteId);if(latest==null)return;if("trash".equals(filter))trashMenu(latest);else openEditor(noteId,false);}catch(RuntimeException error){showError("无法读取笔记",error);}});
        item.setOnLongClickListener(v->{if(selectionMode){toggleSelection(noteId);return true;}try{NoteStore.Note latest=store.getNote(noteId);if(latest!=null){if("trash".equals(filter))trashMenu(latest);else noteMenu(latest);}}catch(RuntimeException error){showError("无法读取笔记",error);}return true;});
        if("trash".equals(filter)){cardShadow(item);return item;}
        return new SwipeNoteLayout(this,item,!selectionMode,()->quickTrash(noteId),this::openSwipe);
    }
    private String displayText(NoteStore.Note note){
        try{return NoteStore.plainText(note).trim();}
        catch(IllegalArgumentException error){return "内容格式异常，原数据已保留";}
    }
    private String noteLabel(NoteStore.Note note){String value=displayText(note).replace('\n',' ');return value.isEmpty()?"笔记":value.length()>40?value.substring(0,40)+"…":value;}
    private String folderName(long id){String value=folderNames.get(id);return value==null?"未分类":value;}
    private String locationName(long id){NoteStore.Notebook book=notebookIndex.get(id);return book==null?"未分类":folderName(book.folderId)+" / "+book.name;}
    private void openSwipe(SwipeNoteLayout row){
        if(openedSwipe!=null && openedSwipe!=row)openedSwipe.close(false);
        openedSwipe=row;
    }
    private void closeSwipe(){if(openedSwipe!=null){openedSwipe.close(false);openedSwipe=null;}}
    private void clearSelection(){selectionMode=false;selectedNoteIds.clear();matchingSelectionIds=Collections.emptyList();closeSwipe();}
    private void updateSelectionUi(){
        if(normalHeader==null || selectionHeader==null)return;
        normalHeader.setVisibility(selectionMode?View.GONE:View.VISIBLE);selectionHeader.setVisibility(selectionMode?View.VISIBLE:View.GONE);
        selectionFooter.setVisibility(selectionMode?View.VISIBLE:View.GONE);
        if(createButton!=null)createButton.setVisibility(selectionMode?View.GONE:View.VISIBLE);
        selectionCount.setText("已选 "+selectedNoteIds.size());
        boolean all=!matchingSelectionIds.isEmpty() && selectedNoteIds.containsAll(matchingSelectionIds);
        selectAllButton.setText(all?"取消全选":"全选");
        deleteSelectionButton.setText("删除（"+selectedNoteIds.size()+"）");
        Motion.enabled(deleteSelectionButton,selectionMode && !selectedNoteIds.isEmpty());
    }
    private void enterSelection(long initialId){
        if("trash".equals(filter) || editor!=null)return;
        hideKeyboard();closeSwipe();
        try{
            matchingSelectionIds=store.noteIds(query,folder,notebook,filter);selectedNoteIds.clear();
            if(initialId>0 && matchingSelectionIds.contains(initialId))selectedNoteIds.add(initialId);
            selectionMode=true;updateSelectionUi();renderList();
        }catch(RuntimeException error){showError("无法选择笔记",error);}
    }
    private void exitSelection(){clearSelection();updateSelectionUi();renderList();}
    private void toggleSelection(long id){
        if(!selectionMode)return;
        try{
            matchingSelectionIds=store.noteIds(query,folder,notebook,filter);selectedNoteIds.retainAll(matchingSelectionIds);
            if(matchingSelectionIds.contains(id)){if(!selectedNoteIds.add(id))selectedNoteIds.remove(id);}
            updateSelectionUi();renderList();
        }catch(RuntimeException error){showError("无法选择笔记",error);}
    }
    private void selectAllNotes(){
        if(!selectionMode)return;
        try{
            matchingSelectionIds=store.noteIds(query,folder,notebook,filter);selectedNoteIds.retainAll(matchingSelectionIds);
            if(!matchingSelectionIds.isEmpty() && selectedNoteIds.containsAll(matchingSelectionIds))selectedNoteIds.clear();
            else selectedNoteIds.addAll(matchingSelectionIds);
            updateSelectionUi();renderList();
        }catch(RuntimeException error){showError("无法选择笔记",error);}
    }
    private void deleteSelected(){
        if(!selectionMode || selectedNoteIds.isEmpty() || "trash".equals(filter))return;
        try{
            List<Long> ids=new ArrayList<>(store.noteIds(query,folder,notebook,filter));ids.retainAll(selectedNoteIds);
            if(ids.isEmpty()){selectedNoteIds.clear();updateSelectionUi();renderList();return;}
            int count=store.trashNotes(ids);clearSelection();renderList();updateSelectionUi();
            Toast.makeText(this,"已删除 "+count+" 条，可在最近删除恢复",Toast.LENGTH_SHORT).show();
        }catch(RuntimeException error){showError("删除未完成",error);}
    }
    private void quickTrash(long id){
        if(selectionMode || "trash".equals(filter))return;
        closeSwipe();
        try{
            int count=store.trashNotes(Collections.singletonList(id));renderList();
            if(count>0)Toast.makeText(this,"已移至最近删除，可恢复",Toast.LENGTH_SHORT).show();
        }catch(RuntimeException error){showError("删除未完成",error);}
    }
    private void selectCategory(long id,String value){
        clearSelection();if(categories!=null)categories.dismiss();folder=id;notebook=0;filter=value;query="";hideKeyboard();render();
    }
    private void selectNotebook(NoteStore.Notebook book){
        clearSelection();if(categories!=null)categories.dismiss();folder=book.folderId;notebook=book.id;filter="all";query="";expandedFolders.add(folder);hideKeyboard();render();
    }
    private void showCategories(boolean managing){
        hideKeyboard();if(categories!=null)categories.dismiss();
        Dialog dialog=new Dialog(this);categories=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet=new LinearLayout(this){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int available=getResources().getDisplayMetrics().heightPixels;
                if(Build.VERSION.SDK_INT>=30){
                    WindowMetrics metrics=getWindowManager().getCurrentWindowMetrics();
                    android.graphics.Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                    available=metrics.getBounds().height()-bars.top-bars.bottom;
                }else if(root.getHeight()>0)available=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom();
                int maximum=Math.max(0,Math.round(available*.8f));
                if(MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED)maximum=Math.min(maximum,MeasureSpec.getSize(heightSpec));
                // LinearLayout passes only the space remaining after heading and padding to the scroll view.
                super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(maximum,MeasureSpec.AT_MOST));
            }
        };
        sheet.setOrientation(LinearLayout.VERTICAL);pad(sheet,20,8,16);sheet.setClipToPadding(false);sheet.setBackground(shape(this,BG,20));
        LinearLayout heading=row(this);heading.addView(text(this,managing?"管理分类":"笔记本",18,INK,true),new LinearLayout.LayoutParams(0,WRAP,1));heading.addView(iconButton("close","关闭分类",MUTED,v->dialog.dismiss()),lp(d(48),d(48)));sheet.addView(heading);gap(sheet,4);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setPadding(d(4),d(4),d(4),d(4));scroll.setClipToPadding(false);LinearLayout content=column(this);content.setClipChildren(false);scroll.addView(content);LinearLayout.LayoutParams scrollParams=new LinearLayout.LayoutParams(MATCH,WRAP);scrollParams.setMargins(-d(4),0,-d(4),0);sheet.addView(scroll,scrollParams);
        if(!managing){
            LinearLayout general=column(this);pad(general,4,4,4);general.setBackground(shape(this,SURFACE,12));cardShadow(general);
            general.addView(categoryRow("document","全部笔记",store.activeCount(),folder==0&&notebook==0&&"all".equals(filter),v->selectCategory(0,"all")));
            general.addView(categoryRow("document","未分类",store.countNotes("",-1,0,"all"),folder==-1&&"all".equals(filter),v->selectCategory(-1,"all")));
            general.addView(categoryRow("star","我的收藏",store.favoriteCount(),"favorite".equals(filter),v->selectCategory(0,"favorite")));
            general.addView(categoryRow("trash","最近删除",store.trashCount(),"trash".equals(filter),v->selectCategory(0,"trash")));content.addView(general);
        }
        gap(content,12);LinearLayout foldersHeading=row(this);foldersHeading.addView(text(this,"文件夹",13,MUTED,true),new LinearLayout.LayoutParams(0,WRAP,1));TextView manage=button(this,managing?"完成":"管理",Color.TRANSPARENT,ACCENT_TEXT);pad(manage,8,8,8);manage.setTextSize(13);manage.setOnClickListener(v->showCategories(!managing));foldersHeading.addView(manage);content.addView(foldersHeading);
        Map<Long,List<NoteStore.Notebook>> grouped=new HashMap<>();for(NoteStore.Notebook b:store.notebooks(0))grouped.computeIfAbsent(b.folderId,key->new ArrayList<>()).add(b);
        for(NoteStore.Folder f:store.folders()){
            List<NoteStore.Notebook> books=grouped.get(f.id);if(books==null)books=Collections.emptyList();
            int bookCount=books.size();
            LinearLayout group=column(this);pad(group,4,4,4);group.setBackground(shape(this,SURFACE,12));cardShadow(group);
            LinearLayout folderHeader=row(this);LinearLayout expand=row(this);pad(expand,8,8,8);expand.setMinimumHeight(d(48));ripple(expand,Color.TRANSPARENT,10);
            expand.addView(new IconView(this,"folder",MUTED),lp(d(18),d(18)));TextView name=text(this,f.name,14,INK,true);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,WRAP,1);np.setMargins(d(10),0,d(8),0);expand.addView(name,np);
            TextView amount=text(this,bookCount+" 本",12,MUTED,false);amount.setSingleLine(true);amount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams amountParams=lp(WRAP,WRAP);amountParams.rightMargin=d(8);expand.addView(amount,amountParams);
            IconView arrow=new IconView(this,"chevronDown",MUTED);expand.addView(arrow,lp(d(18),d(18)));folderHeader.addView(expand,new LinearLayout.LayoutParams(0,WRAP,1));
            if(managing)folderHeader.addView(iconButton("more","管理文件夹 "+f.name,MUTED,v->{dialog.dismiss();folderMenu(f);}),lp(d(48),d(48)));group.addView(folderHeader);
            LinearLayout children=column(this);children.setPadding(d(26),0,d(6),d(4));
            for(NoteStore.Notebook book:books){LinearLayout bookLine=row(this);View cell=categoryRow("notebook",book.name,book.count,notebook==book.id&&"all".equals(filter),v->selectNotebook(book));
                cell.setOnLongClickListener(v->{dialog.dismiss();notebookMenu(book);return true;});bookLine.addView(cell,new LinearLayout.LayoutParams(0,WRAP,1));
                if(managing)bookLine.addView(iconButton("more","管理笔记本 "+book.name,MUTED,v->{dialog.dismiss();notebookMenu(book);}),lp(d(48),d(48)));children.addView(bookLine);}
            TextView addBook=button(this,"＋ 新建笔记本",Color.TRANSPARENT,ACCENT_TEXT);addBook.setTextSize(14);addBook.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);pad(addBook,12,8,8);addBook.setOnClickListener(v->{dialog.dismiss();notebookDialog(f.id,null);});children.addView(addBook,lp(MATCH,WRAP));
            boolean open=expandedFolders.contains(f.id);children.setVisibility(open?View.VISIBLE:View.GONE);arrow.setRotation(open?180:0);group.addView(children);
            expand.setContentDescription(f.name+"，文件夹，"+bookCount+" 个笔记本，"+(open?"已展开":"已折叠"));expand.setFocusable(true);
            expand.setOnClickListener(v->{boolean show=!expandedFolders.contains(f.id);if(show)expandedFolders.add(f.id);else expandedFolders.remove(f.id);Motion.expand(children,arrow,show);expand.setContentDescription(f.name+"，文件夹，"+bookCount+" 个笔记本，"+(show?"已展开":"已折叠"));});
            expand.setOnLongClickListener(v->{dialog.dismiss();folderMenu(f);return true;});content.addView(group,lp(MATCH,WRAP));gap(content,10);
        }
        TextView addFolder=button(this,"＋ 新建文件夹",WHITE,ACCENT_TEXT);addFolder.setOnClickListener(v->{dialog.dismiss();folderDialog(null);});content.addView(addFolder,lp(MATCH,WRAP));
        dialog.setContentView(sheet);Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams attributes=window.getAttributes();attributes.dimAmount=.28f;window.setAttributes(attributes);window.setGravity(Gravity.BOTTOM);}
        Motion.sheet(dialog).show();if(window!=null)window.setLayout(MATCH,WRAP);
    }
    private View categoryRow(String icon,String title,int count,boolean selected,View.OnClickListener click){
        LinearLayout row=row(this);pad(row,8,8,8);row.setMinimumHeight(d(48));ripple(row,selected?ACCENT_SOFT:Color.TRANSPARENT,10);
        row.addView(new IconView(this,icon,selected||"notebook".equals(icon)?ACCENT:MUTED),lp(d(18),d(18)));TextView label=text(this,title,14,selected?ACCENT_TEXT:INK,selected);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,WRAP,1);p.setMargins(d(10),0,d(8),0);row.addView(label,p);
        row.addView(text(this,String.valueOf(count),12,selected?ACCENT_TEXT:MUTED,false));row.setContentDescription(title+"，"+count+" 条笔记"+(selected?"，已选择":""));row.setSelected(selected);row.setFocusable(true);row.setOnClickListener(click);return row;
    }
    private void notebookMenu(NoteStore.Notebook book){
        new Motion.DialogBuilder(this).setTitle(book.name).setItems(new String[]{"重命名笔记本","删除笔记本"},(dialog,which)->{
            if(which==0)notebookDialog(book.folderId,book);else new Motion.DialogBuilder(this).setTitle("删除笔记本？").setMessage("其中的笔记会移至未分类，内容不会被删除。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(a,b)->mutate(()->{store.deleteNotebook(book.id);if(notebook==book.id){notebook=0;folder=0;}})).show();
        }).show();
    }
    private void notebookDialog(long parentId,NoteStore.Notebook existing){
        nameDialog(existing==null?"新建笔记本":"重命名笔记本",existing==null?"":existing.name,"笔记本名称",value->{
            if(existing==null){long id=store.createNotebook(parentId,value);NoteStore.Notebook added=store.getNotebook(id);if(added==null)throw new IllegalStateException("新建笔记本未能读取");notebook=added.id;folder=added.folderId;filter="all";query="";expandedFolders.add(parentId);}
            else store.renameNotebook(existing.id,value);
        },false);
    }
    private void folderMenu(NoteStore.Folder f){
        new Motion.DialogBuilder(this).setTitle(f.name).setItems(new String[]{"重命名","删除文件夹"},(dialog,which)->{
            if(which==0)folderDialog(f);else new Motion.DialogBuilder(this).setTitle("删除文件夹？").setMessage("其中的笔记会移至未分类，内容不会被删除。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(a,b)->mutate(()->{store.deleteFolder(f.id);expandedFolders.remove(f.id);if(folder==f.id){folder=0;notebook=0;}})).show();
        }).show();
    }
    private interface NameAction{void save(String value);}
    private void folderDialog(NoteStore.Folder existing){
        nameDialog(existing==null?"新建文件夹":"重命名文件夹",existing==null?"":existing.name,"文件夹名称",value->{
            if(existing==null)expandedFolders.add(store.createFolder(value,"sky"));else store.renameFolder(existing.id,value);
        },existing==null);
    }
    private void nameDialog(String title,String initial,String label,NameAction save,boolean reopenCategories){
        LinearLayout view=column(this);pad(view,24,12,16);view.addView(text(this,label,13,MUTED,false));gap(view,8);EditText name=new EditText(this);name.setSingleLine(true);name.setTextSize(20);name.setTextColor(INK);name.setHintTextColor(MUTED);name.setHint("输入名称");name.setContentDescription(label);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});name.setBackground(null);name.setPadding(0,d(12),0,d(12));name.setMinHeight(d(52));view.addView(name,lp(MATCH,WRAP));name.setText(initial);name.setSelection(name.length());
        AlertDialog dialog=new Motion.DialogBuilder(this).setTitle(title).setView(view).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("请输入名称");Motion.pulse(name);return;}
            try{save.save(value);dialog.dismiss();hideKeyboard();render();if(reopenCategories)showCategories(false);}catch(RuntimeException e){name.setError(e.getMessage()==null?"保存失败，请重试":e.getMessage());Motion.pulse(name);}}));Motion.dialog(dialog).show();
    }
    private void newNote(){
        try{NoteStore.Note note=store.createNoteInNotebook(notebook);openEditor(note.id,true);}catch(RuntimeException e){showError("新建失败",e);}
    }
    private void openEditor(long id,boolean fresh){
        hideKeyboard();handler.removeCallbacks(searchRefresh);if(categories!=null)categories.dismiss();
        try{if(editor!=null)editor.dispose();editorId=id;newDraft=fresh;editor=new NoteEditor(this,store,id,fresh,()->{hideKeyboard();if(editor!=null)editor.dispose();editor=null;editorId=0;newDraft=false;render(false);});present(editor,true);}
        catch(RuntimeException e){editor=null;editorId=0;newDraft=false;render();showError("无法打开笔记",e);}
    }
    private void noteMenu(NoteStore.Note n){
        closeSwipe();
        new Motion.DialogBuilder(this).setTitle(noteLabel(n)).setItems(new String[]{"多选",n.favorite?"取消收藏":"收藏",n.pinned?"取消置顶":"置顶","移动到笔记本","删除"},(a,which)->{
            if(which==0)enterSelection(n.id);else if(which==1)mutate(()->store.toggleFavorite(n.id));else if(which==2)mutate(()->store.togglePinned(n.id));else if(which==3)moveDialog(n);else quickTrash(n.id);
        }).show();
    }
    private void moveDialog(NoteStore.Note n){
        List<NoteStore.Notebook> books=store.notebooks(0);String[] labels=new String[books.size()+1];labels[0]="未分类";for(int i=0;i<books.size();i++)labels[i+1]=folderName(books.get(i).folderId)+" / "+books.get(i).name;
        new Motion.DialogBuilder(this).setTitle("移动到笔记本").setItems(labels,(a,pos)->mutate(()->store.moveNoteToNotebook(n.id,pos==0?0:books.get(pos-1).id))).setNegativeButton("取消",null).show();
    }
    private void trashMenu(NoteStore.Note n){
        new Motion.DialogBuilder(this).setTitle(noteLabel(n)).setItems(new String[]{"恢复","永久删除"},(a,which)->{
            if(which==0)mutate(()->store.restoreNote(n.id));else new Motion.DialogBuilder(this).setTitle("永久删除？").setMessage("这篇笔记及其中的图片将无法恢复。")
                .setNegativeButton("取消",null).setPositiveButton("永久删除",(x,y)->mutate(()->store.deleteForever(n.id))).show();
        }).show();
    }
    private void mainMenu(View anchor){
        closeSwipe();PopupMenu popup=new PopupMenu(this,anchor);if(!"trash".equals(filter))popup.getMenu().add(0,5,0,"多选笔记");popup.getMenu().add(0,6,1,"电脑同步");popup.getMenu().add(0,1,2,"管理分类");popup.getMenu().add(0,2,3,"导出文字");popup.getMenu().add(0,4,4,"字体大小");popup.getMenu().add(0,3,5,"关于简记");
        popup.setOnMenuItemClickListener(item->{if(item.getItemId()==5)enterSelection(0);else if(item.getItemId()==6)startActivity(new Intent(this,SyncActivity.class));else if(item.getItemId()==1)showCategories(true);else if(item.getItemId()==2)requestExport();else if(item.getItemId()==4)showFontSizeSettings();else about();return true;});Motion.pulse(anchor);popup.show();
    }
    private void showFontSizeSettings(){
        hideKeyboard();
        int original=fontSizeChoice(this);
        int[] selected={original};
        LinearLayout preview=column(this);pad(preview,24,12,16);
        preview.addView(text(this,"仅调整简记内的文字大小",12,MUTED,false));gap(preview,12);
        TextView sample=text(this,"记录此刻，留住灵感。\n每一个想法，都值得被记下。",16,INK,false);
        sample.setLineSpacing(d(4),1.1f);preview.addView(sample,lp(MATCH,WRAP));
        Runnable updatePreview=()->{
            android.content.res.Configuration config=new android.content.res.Configuration(getBaseContext().getResources().getConfiguration());
            if(FONT_SCALES[selected[0]]>0f)config.fontScale=FONT_SCALES[selected[0]];
            Context scaled=getBaseContext().createConfigurationContext(config);
            float pixels=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,16,scaled.getResources().getDisplayMetrics());
            sample.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,pixels);
        };
        updatePreview.run();
        new Motion.DialogBuilder(this).setTitle("字体大小")
            .setSingleChoiceItems(new String[]{"跟随系统","小 · 85%","标准 · 100%","大 · 115%","特大 · 130%"},original,(dialog,which)->{selected[0]=which;updatePreview.run();})
            .setView(preview).setNegativeButton("取消",null)
            .setPositiveButton("应用",(dialog,which)->{
                if(selected[0]==original)return;
                getSharedPreferences(APPEARANCE,MODE_PRIVATE).edit().putInt(FONT_SIZE,selected[0]).apply();
                recreate();
            }).show();
    }
    private void about(){
        new Motion.DialogBuilder(this).setTitle("简记 2.0.0").setMessage("专注记录。\n\n作者 @yue\n\n笔记保存在本机，无需登录。可与同一 Wi-Fi 下已配对的电脑双向同步。卸载应用会删除本机数据，换机前请同步或导出。")
            .setPositiveButton("知道了",null).show();
    }
    private void requestExport(){
        if(store.activeCount()==0){Toast.makeText(this,"暂无可导出的笔记",Toast.LENGTH_SHORT).show();return;}
        new Motion.DialogBuilder(this).setTitle("导出文字").setMessage("将正常笔记的文字保存为 TXT。图片与最近删除的内容不包含在内。")
            .setNegativeButton("取消",null).setPositiveButton("选择位置",(a,b)->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TITLE,"简记-"+new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(new Date())+".txt");
                try{startActivityForResult(intent,EXPORT_TEXT);}catch(ActivityNotFoundException e){showError("无法打开文件保存器",e);}}).show();
    }
    private void exportText(Uri uri){
        Toast.makeText(this,"正在导出…",Toast.LENGTH_SHORT).show();Map<Long,String> names=new HashMap<>();for(NoteStore.Notebook book:store.notebooks(0))names.put(book.id,folderName(book.folderId)+" / "+book.name);
        io.execute(()->{try{List<NoteStore.Note> notes=store.loadNotes("",0,"all");
            try(OutputStream stream=getContentResolver().openOutputStream(uri,"wt")){
                if(stream==null)throw new IOException("无法写入选择的位置");try(Writer out=new OutputStreamWriter(stream,java.nio.charset.StandardCharsets.UTF_8)){
                    out.write("简记 · 文字导出\n\n");for(NoteStore.Note n:notes){out.write("位置："+(names.containsKey(n.notebookId)?names.get(n.notebookId):"未分类")+"\n");out.write(new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new Date(n.updatedAt))+"\n\n");out.write(exportBody(n));out.write("\n\n────────────────────────\n\n");}
                }
            }runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())Toast.makeText(this,"文字已导出",Toast.LENGTH_LONG).show();});
        }catch(Exception e){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())showError("导出失败",e);});}});
    }
    private String exportBody(NoteStore.Note n) throws JSONException{
        StringBuilder result=new StringBuilder(NoteStore.plainText(n));
        int count=new JSONArray(n.attachments).length();if(count>0)result.append("\n[另含 ").append(count).append(" 张图片，未包含在文字导出中]");return result.toString();
    }
    private void divider(LinearLayout view){View line=new View(this);line.setBackgroundColor(LINE);view.addView(line,lp(MATCH,d(1)));}
    private void mutate(Runnable change){try{change.run();render();}catch(RuntimeException e){render();showError("操作未完成",e);}}
    private void hideKeyboard(){View focus=getCurrentFocus();if(focus!=null){InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(manager!=null)manager.hideSoftInputFromWindow(focus.getWindowToken(),0);focus.clearFocus();}}
    private void showError(String title,Exception e){new Motion.DialogBuilder(this).setTitle(title).setMessage(e.getMessage()==null?"请稍后重试。":e.getMessage()).setPositiveButton("知道了",null).show();}
    private void fatal(Exception e){launching=false;LinearLayout content=column(this);pad(content,24,40,24);content.addView(text(this,"暂时无法打开简记",22,INK,true));gap(content,16);content.addView(text(this,"本机存储初始化失败："+e.getMessage(),14,MUTED,false));present(content,true);}
}
