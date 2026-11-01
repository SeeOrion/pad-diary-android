package com.paddiary.local;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.database.sqlite.SQLiteException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMPORT = 11, EXPORT = 12, PHOTOS = 13, BACKUP_FOLDER = 14, LOCATION = 15, COVER = 16;
    private static final int INK = Color.rgb(34, 48, 50), MUTED = Color.rgb(98, 110, 110);
    private static final int TEAL = Color.rgb(23, 113, 109), PALE = Color.rgb(232, 244, 241), PAPER = Color.rgb(250, 249, 245);
    private DiaryStore store;
    private LinearLayout root;
    private TextView status;
    private EditText titleInput, bodyInput, placeInput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService backupWorker = Executors.newSingleThreadExecutor();
    private final Runnable delayedSave = this::saveNow;
    private Runnable delayedBackup;
    private long selectedCategory = -1, selectedNotebook = -1, selectedEntry = -1;
    private long entryTime;
    private Double latitude, longitude;
    private int portraitPage = 0;
    private boolean rendering = false;
    private float uiScale = 1f, lineSpacing = 1.3f;
    private long coverTarget = -1;
    private static final int[] COVER_COLORS = {0xff18736d, 0xffd27b64, 0xff647ca8, 0xff8c75a0, 0xffba9a58, 0xff628477};
    private static final String[] COVER_NAMES = {"松绿", "暖陶", "远蓝", "暮紫", "麦金", "苔青"};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DiaryStore(this);
        uiScale = getPreferences(0).getFloat("ui_scale", 1f);
        getWindow().setStatusBarColor(TEAL);
        getWindow().setNavigationBarColor(INK);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(PAPER);
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom)); return insets;
        });
        setContentView(root);
        if (state != null) {
            selectedCategory = state.getLong("category", -1); selectedNotebook = state.getLong("notebook", -1);
            selectedEntry = state.getLong("entry", -1); portraitPage = state.getInt("page", 0);
            coverTarget = state.getLong("cover_target", -1);
        } else {
            JSONObject firstBook = store.one("SELECT id FROM notebooks ORDER BY created_at DESC LIMIT 1");
            if (firstBook != null) {
                selectedNotebook = number(firstBook, "id");
                JSONObject firstEntry = store.one("SELECT id FROM entries WHERE notebook_id=? ORDER BY entry_time DESC LIMIT 1", String.valueOf(selectedNotebook));
                if (firstEntry != null) selectedEntry = number(firstEntry, "id");
            }
        }
        render();
        if (!getPreferences(0).getBoolean("backup_prompted", false)) {
            getPreferences(0).edit().putBoolean("backup_prompted", true).apply();
            new AlertDialog.Builder(this).setTitle("保护你的日记")
                    .setMessage("日记会保存在这台平板。为防止误卸载造成丢失，建议选择一个平板本地文件夹，App 会自动保留最近 3 份完整备份。")
                    .setPositiveButton("选择本地备份目录", (d, w) -> chooseBackupFolder())
                    .setNegativeButton("稍后设置", null).show();
        }
    }

    @Override protected void onPause() { saveNow(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        saveNow(); state.putLong("category", selectedCategory); state.putLong("notebook", selectedNotebook);
        state.putLong("entry", selectedEntry); state.putInt("page", portraitPage);
        state.putLong("cover_target", coverTarget); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(delayedSave);
        if (delayedBackup != null) {
            handler.removeCallbacks(delayedBackup);
            String folder = getPreferences(0).getString("backup_folder", null);
            if (folder != null) backupWorker.execute(() -> {
                try { DiaryArchive.autoBackup(getApplicationContext(), store, Uri.parse(folder)); }
                catch (Exception ignored) { }
            });
        }
        backupWorker.shutdown(); super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (!wide() && portraitPage > 0) { saveNow(); portraitPage--; render(); }
        else super.onBackPressed();
    }
    @Override public void onConfigurationChanged(Configuration newConfig) { super.onConfigurationChanged(newConfig); saveNow(); render(); }

    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density * n + .5f); }
    private boolean wide() { return getResources().getConfiguration().screenWidthDp >= 840; }
    private String date(long time) { return new SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(new Date(time)); }
    private String shortDate(long time) { return new SimpleDateFormat("M月d日  HH:mm", Locale.CHINA).format(new Date(time)); }
    private String value(JSONObject o, String key) { return o == null || o.isNull(key) ? "" : o.optString(key, ""); }
    private long number(JSONObject o, String key) { return o == null ? -1 : o.optLong(key, -1); }
    private void message(String text) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show(); }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size * uiScale); v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    private Button button(String text, boolean filled) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(17 * uiScale);
        b.setTextColor(filled ? Color.WHITE : TEAL); b.setBackground(bg(filled ? TEAL : PALE, 12));
        b.setMinHeight(dp(52)); b.setPadding(dp(14), dp(8), dp(14), dp(8)); return b;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private void gap(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private void pad(View v, int n) { v.setPadding(dp(n), dp(n), dp(n), dp(n)); }
    private ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this); s.setFillViewport(true); s.addView(child); return s;
    }
    private EditText input(String hint, int size) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextSize(size * uiScale); e.setTextColor(INK);
        e.setHintTextColor(MUTED); e.setSingleLine(true); e.setPadding(dp(12), dp(12), dp(12), dp(12));
        e.setBackground(bg(Color.WHITE, 10)); return e;
    }
    private void line(LinearLayout parent) {
        View v = new View(this); v.setBackgroundColor(Color.rgb(224, 228, 225));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1)); p.topMargin = dp(12); p.bottomMargin = dp(12); parent.addView(v, p);
    }
    private void addFull(LinearLayout parent, View child) { parent.addView(child, new LinearLayout.LayoutParams(-1, -2)); }

    private void render() {
        rendering = true;
        root.removeAllViews();
        root.addView(topBar(), new LinearLayout.LayoutParams(-1, dp(78)));
        if (wide()) {
            LinearLayout panes = row(); panes.setGravity(Gravity.TOP);
            panes.addView(scroll(shelf()), new LinearLayout.LayoutParams(dp(250), -1));
            panes.addView(scroll(entryList()), new LinearLayout.LayoutParams(dp(285), -1));
            panes.addView(scroll(editor()), new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            root.addView(pageTabs(), new LinearLayout.LayoutParams(-1, dp(64)));
            View page = portraitPage == 0 ? shelf() : portraitPage == 1 ? entryList() : editor();
            root.addView(scroll(page), new LinearLayout.LayoutParams(-1, 0, 1));
        }
        rendering = false;
    }

    private View topBar() {
        LinearLayout bar = row(); pad(bar, 12); bar.setBackgroundColor(Color.WHITE);
        TextView title = label("纸间日记", 26, INK); title.setTypeface(null, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        status = label("已保存在本机", 14, MUTED);
        if (wide()) bar.addView(status, new LinearLayout.LayoutParams(-2, -1));
        Button backup = button("备份与迁移", false); backup.setOnClickListener(v -> backupMenu());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -1); bp.leftMargin = dp(8); bar.addView(backup, bp);
        Button display = button("字体", false); display.setOnClickListener(v -> chooseUiScale());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -1); fp.leftMargin = dp(8); bar.addView(display, fp);
        return bar;
    }

    private View pageTabs() {
        LinearLayout tabs = row(); tabs.setBackgroundColor(PALE);
        String[] names = {"1  日记本", "2  日记列表", "3  写日记"};
        for (int i = 0; i < names.length; i++) {
            final int index = i; Button b = button(names[i], i == portraitPage);
            b.setEnabled(index == 0 || selectedNotebook > 0 && (index == 1 || selectedEntry > 0));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1); p.setMargins(dp(4), 0, dp(4), 0); tabs.addView(b, p);
            b.setOnClickListener(v -> { saveNow(); portraitPage = index; render(); });
        }
        return tabs;
    }

    private void chooseUiScale() {
        String[] choices = {"标准", "稍大", "大字", "特大字"};
        float[] sizes = {1f, 1.12f, 1.25f, 1.4f};
        new AlertDialog.Builder(this).setTitle("页面字体大小")
                .setSingleChoiceItems(choices, uiScale < 1.06f ? 0 : uiScale < 1.19f ? 1 : uiScale < 1.32f ? 2 : 3,
                        (d, which) -> { saveNow(); uiScale = sizes[which]; getPreferences(0).edit().putFloat("ui_scale", uiScale).apply(); d.dismiss(); render(); })
                .setNegativeButton("取消", null).show();
    }

    private View coverView(JSONObject book, int height) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        int style = Math.floorMod(book.optInt("cover_style", 0), COVER_COLORS.length);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{COVER_COLORS[style], Color.rgb(28, 49, 52)});
        background.setCornerRadius(dp(14)); frame.setBackground(background); frame.setClipToOutline(true);
        String file = value(book, "cover_file");
        if (!file.isEmpty()) {
            File coverFile = new File(DiaryArchive.coverDir(this), file);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(coverFile.getAbsolutePath(), options);
            options.inSampleSize = Math.max(1, Math.max(options.outWidth / 700, options.outHeight / 400)); options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(coverFile.getAbsolutePath(), options);
            if (bitmap != null) {
                ImageView image = new ImageView(this); image.setImageBitmap(bitmap); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                frame.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
            }
        }
        TextView title = label(value(book, "name"), 21, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD); title.setShadowLayer(dp(3), 0, dp(2), Color.BLACK);
        android.widget.FrameLayout.LayoutParams p = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        p.setMargins(dp(15), dp(8), dp(15), dp(15)); frame.addView(title, p);
        frame.setMinimumHeight(height);
        frame.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
        return frame;
    }

    private void notebookCoverMenu(JSONObject book) {
        long bookId = number(book, "id");
        String[] choices = new String[COVER_NAMES.length + 1];
        for (int i = 0; i < COVER_NAMES.length; i++) choices[i] = "内置封面 · " + COVER_NAMES[i];
        choices[COVER_NAMES.length] = "从本地图片选择封面";
        new AlertDialog.Builder(this).setTitle("选择日记本封面").setItems(choices, (d, which) -> {
            if (which == COVER_NAMES.length) {
                coverTarget = bookId;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("image/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, COVER);
            } else {
                String old = value(book, "cover_file");
                store.setCover(bookId, which, null);
                if (!old.isEmpty()) new File(DiaryArchive.coverDir(this), old).delete();
                scheduleBackup(); render();
            }
        }).show();
    }

    private View formatToolbar() {
        HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = row();
        addTool(tools, "标题1", () -> paragraph("heading", 1));
        addTool(tools, "标题2", () -> paragraph("heading", 2));
        addTool(tools, "标题3", () -> paragraph("heading", 3));
        addTool(tools, "加粗", () -> inline(new StyleSpan(Typeface.BOLD)));
        addTool(tools, "斜体", () -> inline(new StyleSpan(Typeface.ITALIC)));
        addTool(tools, "字体", this::chooseBodyFont);
        addTool(tools, "字号", this::chooseBodySize);
        addTool(tools, "行距", this::chooseLineSpacing);
        addTool(tools, "• 项目符号", () -> paragraph("bullet", 0));
        addTool(tools, "1. 编号", () -> paragraph("numbered", 0));
        addTool(tools, "普通段落", () -> paragraph("plain", 0));
        strip.addView(tools); return strip;
    }

    private void addTool(LinearLayout bar, String text, Runnable action) {
        Button b = button(text, false); b.setTextSize(15 * uiScale);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(48)); p.rightMargin = dp(6); bar.addView(b, p);
        b.setOnClickListener(v -> action.run());
    }

    private void inline(Object span) {
        if (bodyInput == null) return;
        int start = bodyInput.getSelectionStart(), end = bodyInput.getSelectionEnd();
        if (start < 0 || end <= start) { message("请先选中要设置格式的文字"); return; }
        RichText.applyInline(bodyInput.getText(), start, end, span);
        bodyInput.setSelection(start, end); saveNow();
    }

    private void paragraph(String kind, int value) {
        if (bodyInput == null) return;
        RichText.applyParagraph(bodyInput.getText(), Math.max(0, bodyInput.getSelectionStart()),
                Math.max(0, bodyInput.getSelectionEnd()), kind, value, uiScale, getResources().getDisplayMetrics().density);
        saveNow();
    }

    private void chooseBodyFont() {
        String[] names = {"默认字体", "衬线字体", "等宽字体", "手写风格"};
        String[] families = {"sans-serif", "serif", "monospace", "cursive"};
        new AlertDialog.Builder(this).setTitle("选中文字的字体")
                .setItems(names, (d, which) -> inline(new TypefaceSpan(families[which]))).show();
    }

    private void chooseBodySize() {
        int[] sizes = {16, 18, 20, 24, 28, 32, 38};
        String[] names = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) names[i] = sizes[i] + " 号";
        new AlertDialog.Builder(this).setTitle("选中文字的字号")
                .setItems(names, (d, which) -> inline(new AbsoluteSizeSpan((int)(sizes[which] * uiScale), true))).show();
    }

    private void chooseLineSpacing() {
        String[] names = {"紧凑 · 1.0", "舒适 · 1.3", "宽松 · 1.6", "特宽 · 2.0"};
        float[] values = {1f, 1.3f, 1.6f, 2f};
        new AlertDialog.Builder(this).setTitle("正文行距")
                .setItems(names, (d, which) -> { lineSpacing = values[which]; bodyInput.setLineSpacing(0, lineSpacing); saveNow(); }).show();
    }

    private View shelf() {
        LinearLayout pane = column(); pad(pane, 16); pane.setBackgroundColor(Color.rgb(244, 247, 244));
        TextView heading = label("分类", 22, INK); heading.setTypeface(null, Typeface.BOLD); addFull(pane, heading); gap(pane, 12);
        Button all = button("全部日记本", selectedCategory == -1); addFull(pane, all);
        all.setOnClickListener(v -> { saveNow(); selectedCategory = -1; render(); }); gap(pane, 6);
        JSONArray cats = store.query("SELECT * FROM categories ORDER BY name COLLATE NOCASE");
        for (int i = 0; i < cats.length(); i++) {
            JSONObject c = cats.optJSONObject(i); long id = number(c, "id");
            Button b = button(value(c, "name"), selectedCategory == id); addFull(pane, b);
            b.setOnClickListener(v -> { saveNow(); selectedCategory = id; render(); });
            b.setOnLongClickListener(v -> { categoryMenu(c); return true; }); gap(pane, 6);
        }
        Button addCategory = button("＋ 新建分类", false); addFull(pane, addCategory); addCategory.setOnClickListener(v -> addCategory());
        line(pane);
        TextView notebooksTitle = label("日记本", 22, INK); notebooksTitle.setTypeface(null, Typeface.BOLD); addFull(pane, notebooksTitle); gap(pane, 10);
        JSONArray books = selectedCategory < 0 ? store.query("SELECT * FROM notebooks ORDER BY created_at DESC") :
                store.query("SELECT * FROM notebooks WHERE category_id=? ORDER BY created_at DESC", String.valueOf(selectedCategory));
        for (int i = 0; i < books.length(); i++) {
            JSONObject book = books.optJSONObject(i); long id = number(book, "id");
            View b = coverView(book, dp(76));
            pane.addView(b, new LinearLayout.LayoutParams(-1, dp(76)));
            b.setOnClickListener(v -> { saveNow(); selectedNotebook = id; selectedEntry = -1; portraitPage = 1; render(); });
            b.setOnLongClickListener(v -> { notebookMenu(book); return true; }); gap(pane, 8);
        }
        if (books.length() == 0) { addFull(pane, label("还没有日记本", 17, MUTED)); gap(pane, 12); }
        Button addBook = button("＋ 新建日记本", true); addFull(pane, addBook); addBook.setOnClickListener(v -> addNotebook());
        gap(pane, 22); addFull(pane, label("长按分类或日记本可管理。", 14, MUTED));
        return pane;
    }

    private View entryList() {
        LinearLayout pane = column(); pad(pane, 16); pane.setBackgroundColor(Color.WHITE);
        JSONObject book = selectedNotebook < 0 ? null : store.one("SELECT * FROM notebooks WHERE id=?", String.valueOf(selectedNotebook));
        if (book == null) { selectedNotebook = -1; selectedEntry = -1; addFull(pane, label("先新建或选择一本日记本", 20, MUTED)); return pane; }
        TextView heading = label(value(book, "name"), 22, INK); heading.setTypeface(null, Typeface.BOLD); addFull(pane, heading); gap(pane, 12);
        View cover = coverView(book, dp(105)); addFull(pane, cover); gap(pane, 10);
        Button changeCover = button("更换日记本封面", false); addFull(pane, changeCover);
        changeCover.setOnClickListener(v -> notebookCoverMenu(book)); gap(pane, 12);
        Button add = button("＋ 新建日记", true); addFull(pane, add); add.setOnClickListener(v -> addEntry());
        line(pane);
        JSONArray entries = store.query("SELECT * FROM entries WHERE notebook_id=? ORDER BY entry_time DESC, id DESC", String.valueOf(selectedNotebook));
        if (entries.length() == 0) addFull(pane, label("这里还没有日记，写下第一篇吧。", 17, MUTED));
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i); long id = number(e, "id");
            LinearLayout card = column(); pad(card, 12); card.setBackground(bg(id == selectedEntry ? PALE : PAPER, 12));
            String title = value(e, "title").trim();
            TextView t = label(title.isEmpty() ? "无标题日记" : title, 19, INK); t.setTypeface(null, Typeface.BOLD); addFull(card, t);
            addFull(card, label(shortDate(number(e, "entry_time")), 14, MUTED));
            String excerpt = value(e, "body").replace('\n', ' ').trim();
            if (!excerpt.isEmpty()) { TextView ex = label(excerpt.length() > 45 ? excerpt.substring(0, 45) + "…" : excerpt, 15, MUTED); ex.setMaxLines(2); addFull(card, ex); }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.bottomMargin = dp(9); pane.addView(card, cp);
            card.setOnClickListener(v -> { saveNow(); selectedEntry = id; portraitPage = 2; render(); });
            card.setOnLongClickListener(v -> { entryMenu(e); return true; });
        }
        gap(pane, 22); addFull(pane, label("长按日记可删除。", 14, MUTED));
        return pane;
    }

    private View editor() {
        LinearLayout pane = column(); pad(pane, 22); pane.setBackgroundColor(PAPER);
        JSONObject e = selectedEntry < 0 ? null : store.one("SELECT * FROM entries WHERE id=?", String.valueOf(selectedEntry));
        if (e == null) { selectedEntry = -1; addFull(pane, label("选择一篇日记，或新建日记", 20, MUTED)); return pane; }
        entryTime = number(e, "entry_time");
        latitude = e.isNull("latitude") ? null : e.optDouble("latitude");
        longitude = e.isNull("longitude") ? null : e.optDouble("longitude");
        TextView heading = label("写日记", 23, INK); heading.setTypeface(null, Typeface.BOLD); addFull(pane, heading); gap(pane, 14);
        titleInput = input("给这篇日记起个标题", 24); titleInput.setText(value(e, "title")); addFull(pane, titleInput); gap(pane, 12);
        LinearLayout meta = row();
        Button time = button("日期时间  " + date(entryTime), false);
        meta.addView(time, new LinearLayout.LayoutParams(-2, dp(56)));
        time.setOnClickListener(v -> chooseDate()); addFull(pane, meta); gap(pane, 12);
        LinearLayout locationRow = row();
        placeInput = input("地点名称（可手动输入）", 18); placeInput.setText(value(e, "place"));
        locationRow.addView(placeInput, new LinearLayout.LayoutParams(0, dp(58), 1));
        Button locate = button("获取当前位置", false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(58)); lp.leftMargin = dp(8); locationRow.addView(locate, lp);
        locate.setOnClickListener(v -> requestLocation()); addFull(pane, locationRow);
        if (latitude != null && longitude != null) { gap(pane, 5); addFull(pane, label(String.format(Locale.CHINA, "坐标：%.5f, %.5f", latitude, longitude), 14, MUTED)); }
        gap(pane, 14);
        bodyInput = input("今天发生了什么？", 20); bodyInput.setSingleLine(false); bodyInput.setGravity(Gravity.TOP);
        bodyInput.setMinHeight(dp(300)); bodyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bodyInput.setText(value(e, "body"));
        RichText.decode(bodyInput.getText(), value(e, "body_styles"), uiScale, getResources().getDisplayMetrics().density);
        lineSpacing = (float)e.optDouble("line_spacing", 1.3);
        bodyInput.setLineSpacing(0, lineSpacing);
        addFull(pane, formatToolbar()); gap(pane, 5);
        addFull(pane, label("选中文字可改字体；标题和列表作用于当前段落。", 13, MUTED)); gap(pane, 9);
        addFull(pane, bodyInput); gap(pane, 16);
        LinearLayout photoHeading = row();
        TextView photoLabel = label("图片", 21, INK); photoLabel.setTypeface(null, Typeface.BOLD);
        photoHeading.addView(photoLabel, new LinearLayout.LayoutParams(0, dp(56), 1));
        Button addPhoto = button("＋ 添加本地图片", false); photoHeading.addView(addPhoto, new LinearLayout.LayoutParams(-2, dp(56)));
        addPhoto.setOnClickListener(v -> pickPhotos()); addFull(pane, photoHeading);
        JSONArray photos = store.query("SELECT * FROM photos WHERE entry_id=? ORDER BY sort_order, id", String.valueOf(selectedEntry));
        if (photos.length() == 0) { gap(pane, 6); addFull(pane, label("可添加多张图片；选中后会复制并保存在本机。", 15, MUTED)); }
        else {
            HorizontalScrollView strip = new HorizontalScrollView(this); LinearLayout photoRow = row();
            for (int i = 0; i < photos.length(); i++) {
                JSONObject p = photos.optJSONObject(i); long photoId = number(p, "id"); String fileName = value(p, "file_name");
                LinearLayout tile = column(); tile.setBackground(bg(Color.WHITE, 10)); pad(tile, 5);
                ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                File photoFile = new File(DiaryArchive.imageDir(this), fileName);
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
                options.inSampleSize = Math.max(1, Math.max(options.outWidth / 320, options.outHeight / 320)); options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
                if (bitmap != null) image.setImageBitmap(bitmap);
                tile.addView(image, new LinearLayout.LayoutParams(dp(150), dp(120)));
                Button remove = button("删除图片", false); tile.addView(remove, new LinearLayout.LayoutParams(-1, dp(44)));
                remove.setOnClickListener(v -> confirm("删除这张图片？", () -> { saveNow(); store.delete("photos", photoId); photoFile.delete(); scheduleBackup(); render(); }));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(160), -2); tp.rightMargin = dp(10); photoRow.addView(tile, tp);
            }
            strip.addView(photoRow); addFull(pane, strip);
        }
        gap(pane, 18); addFull(pane, label("内容自动保存在这台平板；建议设置自动备份目录。", 15, MUTED));
        titleInput.addTextChangedListener(watcher()); bodyInput.addTextChangedListener(watcher()); placeInput.addTextChangedListener(watcher());
        return pane;
    }

    private TextWatcher watcher() {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!rendering) { if (status != null) status.setText("正在编辑…"); handler.removeCallbacks(delayedSave); handler.postDelayed(delayedSave, 650); }
            }
            @Override public void afterTextChanged(Editable e) { }
        };
    }

    private void saveNow() {
        handler.removeCallbacks(delayedSave);
        if (selectedEntry < 0 || titleInput == null || bodyInput == null || placeInput == null) return;
        try {
            store.saveEntry(selectedEntry, titleInput.getText().toString(), bodyInput.getText().toString(),
                    RichText.encode(bodyInput.getText(), uiScale), lineSpacing, entryTime,
                    placeInput.getText().toString(), latitude, longitude);
            if (status != null) status.setText("已保存在本机");
            scheduleBackup();
        } catch (Exception e) { if (status != null) status.setText("保存失败"); message("保存失败：" + e.getMessage()); }
    }

    private void scheduleBackup() {
        String folder = getPreferences(0).getString("backup_folder", null);
        if (folder == null) return;
        if (delayedBackup != null) handler.removeCallbacks(delayedBackup);
        delayedBackup = () -> backupWorker.execute(() -> {
            try {
                DiaryArchive.autoBackup(getApplicationContext(), store, Uri.parse(folder));
                runOnUiThread(() -> { if (status != null) status.setText("已保存并备份"); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (status != null) status.setText("本机已保存 · 备份失败"); message("自动备份失败，请检查备份目录"); });
            }
        });
        handler.postDelayed(delayedBackup, 15000);
    }

    private void addCategory() {
        saveNow();
        nameDialog("新建分类", "分类名称", "", text -> {
            try { selectedCategory = store.addCategory(text); scheduleBackup(); render(); }
            catch (Exception e) { message("分类名称可能已存在"); }
        });
    }
    private void addNotebook() {
        saveNow();
        nameDialog("新建日记本", "日记本名称", "", text -> {
            try { selectedNotebook = store.addNotebook(text, selectedCategory < 0 ? null : selectedCategory); selectedEntry = -1; portraitPage = 1; scheduleBackup(); render(); }
            catch (Exception e) { message("新建失败：" + e.getMessage()); }
        });
    }
    private void addEntry() {
        if (selectedNotebook < 0) return;
        saveNow();
        try { selectedEntry = store.addEntry(selectedNotebook); portraitPage = 2; titleInput = null; bodyInput = null; placeInput = null; scheduleBackup(); render(); }
        catch (Exception e) { message("新建失败：" + e.getMessage()); }
    }
    private interface NameAction { void apply(String name); }
    private void nameDialog(String heading, String hint, String initial, NameAction action) {
        EditText edit = input(hint, 20); edit.setText(initial); edit.selectAll();
        LinearLayout box = column(); pad(box, 18); addFull(box, edit);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(heading).setView(box)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            String name = edit.getText().toString().trim(); if (name.isEmpty()) { edit.setError("请输入名称"); return; }
            action.apply(name); dialog.dismiss();
        }));
        dialog.show();
    }
    private void confirm(String text, Runnable action) {
        new AlertDialog.Builder(this).setMessage(text).setNegativeButton("取消", null).setPositiveButton("确认", (d, w) -> action.run()).show();
    }

    private void categoryMenu(JSONObject category) {
        saveNow();
        long id = number(category, "id");
        new AlertDialog.Builder(this).setTitle(value(category, "name"))
                .setItems(new String[]{"重命名", "删除分类"}, (d, which) -> {
                    if (which == 0) nameDialog("重命名分类", "分类名称", value(category, "name"), name -> {
                        try { store.rename("categories", id, name); scheduleBackup(); render(); }
                        catch (SQLiteException e) { message("名称已存在"); }
                    });
                    else confirm("删除分类？其中的日记本会保留在“全部日记本”。", () -> {
                        store.delete("categories", id); if (selectedCategory == id) selectedCategory = -1; scheduleBackup(); render();
                    });
                }).show();
    }
    private void notebookMenu(JSONObject book) {
        saveNow();
        long id = number(book, "id");
        new AlertDialog.Builder(this).setTitle(value(book, "name"))
                .setItems(new String[]{"重命名", "移动到分类", "更换封面", "删除日记本"}, (d, which) -> {
                    if (which == 0) nameDialog("重命名日记本", "日记本名称", value(book, "name"), name -> { store.rename("notebooks", id, name); scheduleBackup(); render(); });
                    else if (which == 1) chooseNotebookCategory(id);
                    else if (which == 2) notebookCoverMenu(book);
                    else confirm("删除日记本及其中所有日记、图片和封面？此操作无法撤销。", () -> {
                        JSONArray photos = store.query("SELECT p.file_name FROM photos p JOIN entries e ON e.id=p.entry_id WHERE e.notebook_id=?", String.valueOf(id));
                        store.delete("notebooks", id); deleteFiles(photos);
                        String cover = value(book, "cover_file");
                        if (!cover.isEmpty()) new File(DiaryArchive.coverDir(this), cover).delete();
                        if (selectedNotebook == id) { selectedNotebook = -1; selectedEntry = -1; }
                        scheduleBackup(); render();
                    });
                }).show();
    }
    private void chooseNotebookCategory(long bookId) {
        JSONArray cats = store.query("SELECT * FROM categories ORDER BY name COLLATE NOCASE");
        String[] names = new String[cats.length() + 1]; names[0] = "不分类";
        for (int i = 0; i < cats.length(); i++) names[i + 1] = value(cats.optJSONObject(i), "name");
        new AlertDialog.Builder(this).setTitle("移动到分类").setItems(names, (d, which) -> {
            Long cat = which == 0 ? null : number(cats.optJSONObject(which - 1), "id");
            store.moveNotebook(bookId, cat); selectedCategory = cat == null ? -1 : cat; scheduleBackup(); render();
        }).show();
    }
    private void entryMenu(JSONObject entry) {
        saveNow();
        long id = number(entry, "id");
        confirm("删除这篇日记和其中的图片？此操作无法撤销。", () -> {
            JSONArray photos = store.query("SELECT file_name FROM photos WHERE entry_id=?", String.valueOf(id));
            store.delete("entries", id); deleteFiles(photos);
            if (selectedEntry == id) { selectedEntry = -1; titleInput = null; bodyInput = null; placeInput = null; portraitPage = 1; }
            scheduleBackup(); render();
        });
    }
    private void deleteFiles(JSONArray photos) {
        for (int i = 0; i < photos.length(); i++) new File(DiaryArchive.imageDir(this), value(photos.optJSONObject(i), "file_name")).delete();
    }

    private void chooseDate() {
        saveNow();
        Calendar cal = Calendar.getInstance(); cal.setTimeInMillis(entryTime);
        new DatePickerDialog(this, (DatePicker view, int year, int month, int day) -> {
            cal.set(year, month, day);
            new TimePickerDialog(this, (TimePicker timeView, int hour, int minute) -> {
                cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute); cal.set(Calendar.SECOND, 0);
                entryTime = cal.getTimeInMillis(); saveNow(); render();
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickPhotos() {
        saveNow();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PHOTOS);
    }

    private void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION);
        } else fetchLocation();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == LOCATION) {
            boolean allowed = false; for (int result : grants) if (result == PackageManager.PERMISSION_GRANTED) allowed = true;
            if (allowed) fetchLocation(); else message("未获得定位权限，仍可手动填写地点");
        }
    }
    private void fetchLocation() {
        LocationManager manager = (LocationManager)getSystemService(LOCATION_SERVICE);
        String provider;
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
            else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
            else { message("请先开启平板定位服务，或手动输入地点"); return; }
            if (status != null) status.setText("正在获取位置…");
            if (Build.VERSION.SDK_INT >= 30) {
                CancellationSignal signal = new CancellationSignal();
                handler.postDelayed(signal::cancel, 20000);
                manager.getCurrentLocation(provider, signal, command -> runOnUiThread(command), loc -> showLocation(loc));
            } else {
                final LocationListener[] listener = new LocationListener[1];
                listener[0] = location -> { manager.removeUpdates(listener[0]); showLocation(location); };
                manager.requestSingleUpdate(provider, listener[0], Looper.getMainLooper());
                handler.postDelayed(() -> manager.removeUpdates(listener[0]), 20000);
            }
        } catch (SecurityException e) { message("请允许定位权限"); }
        catch (Exception e) { message("定位暂时不可用，请手动填写地点"); }
    }
    private void showLocation(Location location) {
        if (location == null) { message("没有获取到当前位置，请手动填写地点"); return; }
        latitude = location.getLatitude(); longitude = location.getLongitude();
        if (placeInput != null) placeInput.setText(String.format(Locale.CHINA, "%.5f, %.5f", latitude, longitude));
        saveNow(); render(); message("位置已添加；可将坐标改成地点名称");
    }

    private void backupMenu() {
        String folder = getPreferences(0).getString("backup_folder", null);
        String folderOption = folder == null ? "设置自动本地备份目录" : "更改自动本地备份目录";
        new AlertDialog.Builder(this).setTitle("备份与迁移")
                .setItems(new String[]{"导出完整备份", "导入备份并恢复", folderOption, "立即自动备份"}, (d, which) -> {
                    if (which == 0) startExport();
                    else if (which == 1) startImport();
                    else if (which == 2) chooseBackupFolder();
                    else if (folder == null) chooseBackupFolder();
                    else runAutoBackupNow();
                }).show();
    }
    private void startExport() {
        saveNow();
        String name = "纸间日记-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".zip";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, EXPORT);
    }
    private void startImport() {
        saveNow();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/zip");
        startActivityForResult(intent, IMPORT);
    }
    private void chooseBackupFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, BACKUP_FOLDER);
    }
    private void runAutoBackupNow() {
        String folder = getPreferences(0).getString("backup_folder", null);
        if (folder == null) { chooseBackupFolder(); return; }
        saveNow();
        backupWorker.execute(() -> {
            try { DiaryArchive.autoBackup(getApplicationContext(), store, Uri.parse(folder)); runOnUiThread(() -> message("备份已保存到所选目录")); }
            catch (Exception e) { runOnUiThread(() -> message("备份失败：" + e.getMessage())); }
        });
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PHOTOS) { receivePhotos(data); return; }
        if (requestCode == COVER) { receiveCover(data); return; }
        Uri uri = data.getData(); if (uri == null) return;
        if (requestCode == BACKUP_FOLDER) {
            try {
                if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) {
                    message("请选择这台平板或 SD 卡中的本地文件夹"); return;
                }
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getPreferences(0).edit().putString("backup_folder", uri.toString()).apply();
                message("自动备份目录已设置；会保留最近 3 份备份"); runAutoBackupNow();
            } catch (Exception e) { message("无法使用该目录：" + e.getMessage()); }
        } else if (requestCode == EXPORT) {
            backupWorker.execute(() -> {
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri, "w");
                    if (out == null) throw new IllegalStateException("无法写入文件");
                    DiaryArchive.exportTo(getApplicationContext(), store, out);
                    runOnUiThread(() -> message("完整备份已导出"));
                } catch (Exception e) {
                    try { DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { }
                    runOnUiThread(() -> message("导出失败：" + e.getMessage()));
                }
            });
        } else if (requestCode == IMPORT) {
            confirm("导入会替换当前所有日记本、日记、图片和封面。建议先导出当前数据。确认继续？", () -> {
                AlertDialog progress = new AlertDialog.Builder(this).setMessage("正在检查并导入备份…").setCancelable(false).create();
                progress.show();
                backupWorker.execute(() -> {
                    try {
                        InputStream in = getContentResolver().openInputStream(uri);
                        if (in == null) throw new IllegalStateException("无法读取备份");
                        try (InputStream source = in) { DiaryArchive.importFrom(getApplicationContext(), store, source); }
                        runOnUiThread(() -> { progress.dismiss(); selectedCategory = selectedNotebook = selectedEntry = -1;
                            titleInput = null; bodyInput = null; placeInput = null; portraitPage = 0; render(); scheduleBackup(); message("导入完成"); });
                    } catch (Exception e) { runOnUiThread(() -> { progress.dismiss(); message("导入失败，原数据已保留：" + e.getMessage()); }); }
                });
            });
        }
    }
    private void receivePhotos(Intent data) {
        if (selectedEntry < 0) return;
        long targetEntry = selectedEntry;
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        AlertDialog progress = new AlertDialog.Builder(this).setMessage("正在保存图片到本机…").setCancelable(false).create();
        progress.show();
        backupWorker.execute(() -> {
            int added = 0, failed = 0;
            for (Uri uri : uris) {
                String mime = getContentResolver().getType(uri);
                String ext = mime != null && mime.contains("png") ? ".png" : mime != null && mime.contains("webp") ? ".webp" :
                        mime != null && (mime.contains("heic") || mime.contains("heif")) ? ".heic" : ".jpg";
                String name = UUID.randomUUID() + ext; File file = new File(DiaryArchive.imageDir(getApplicationContext()), name);
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                    if (in == null) throw new IllegalStateException("无法读取图片");
                    byte[] bytes = new byte[64 * 1024]; int n; long size = 0;
                    while ((n = in.read(bytes)) != -1) { size += n; if (size > 80L * 1024 * 1024) throw new IllegalStateException("图片超过 80 MB"); out.write(bytes, 0, n); }
                    store.addPhoto(targetEntry, name); added++;
                } catch (Exception e) { file.delete(); failed++; }
            }
            final int count = added, errors = failed;
            runOnUiThread(() -> { progress.dismiss(); if (count > 0) { scheduleBackup(); render(); }
                message("已添加 " + count + " 张图片" + (errors > 0 ? "，" + errors + " 张失败" : "")); });
        });
    }

    private void receiveCover(Intent data) {
        Uri uri = data.getData(); long target = coverTarget; coverTarget = -1;
        if (uri == null || target < 0) return;
        JSONObject book = store.one("SELECT * FROM notebooks WHERE id=?", String.valueOf(target));
        if (book == null) return;
        String mime = getContentResolver().getType(uri);
        String ext = mime != null && mime.contains("png") ? ".png" : mime != null && mime.contains("webp") ? ".webp" :
                mime != null && (mime.contains("heic") || mime.contains("heif")) ? ".heic" : ".jpg";
        String name = UUID.randomUUID() + ext;
        File file = new File(DiaryArchive.coverDir(this), name);
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IllegalStateException("无法读取封面图片");
            byte[] bytes = new byte[64 * 1024]; int n; long size = 0;
            while ((n = in.read(bytes)) != -1) {
                size += n; if (size > 80L * 1024 * 1024) throw new IllegalStateException("封面图片超过 80 MB");
                out.write(bytes, 0, n);
            }
            if (size == 0) throw new IllegalStateException("图片为空");
            String old = value(book, "cover_file");
            store.setCover(target, book.optInt("cover_style", 0), name);
            if (!old.isEmpty()) new File(DiaryArchive.coverDir(this), old).delete();
            scheduleBackup(); render(); message("日记本封面已保存");
        } catch (Exception e) { file.delete(); message("设置封面失败：" + e.getMessage()); }
    }
}
