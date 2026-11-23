package com.happyhome.hhn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HappyHome 原生版：单文件全部逻辑（Activity + 数据 + 存储 + 文档解析）。
 */
public class MainActivity extends Activity {

    private static final int REQ_ICON = 101;
    private static final int REQ_FILE = 102;
    private static final int REQ_EXPORT = 103;
    private static final int REQ_IMPORT = 104;

    private static final String[][] TABS = {
            {"phone", "📱小手机"},
            {"api", "🔌API"},
            {"world", "📖世界书"},
            {"char", "👤人设"},
            {"beauty", "🎀美化"},
            {"font", "🔤字体"},
            {"backup", "🗂️备份"},
    };

    private Store store;
    private GridView grid;
    private LinearLayout chipsBox;
    private LinearLayout backupPanel;
    private TextView backupInfo;
    private EditText search;

    private String currentTab = "phone";
    private String filterAuthor = null;
    private String filterFunc = null;
    private String query = "";

    private final List<Entry> visible = new ArrayList<>();
    private EntryAdapter adapter;

    private AlertDialog currentDialog;
    private String pendingIconId = null;

    private int backCount = 0;
    private long lastBackAt = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        store = new Store(this);
        grid = findViewById(R.id.grid);
        chipsBox = findViewById(R.id.chips);
        backupPanel = findViewById(R.id.backupPanel);
        backupInfo = findViewById(R.id.backupInfo);
        search = findViewById(R.id.search);

        adapter = new EntryAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                if ("backup".equals(currentTab)) return;
                if (pos == visible.size()) { openEdit(null); return; }
                if (pos == visible.size() + 1 && canImport()) { pickFile(); return; }
                openEdit(visible.get(pos));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View v, int pos, long id) {
                if ("backup".equals(currentTab)) return true;
                if (pos >= visible.size()) return true;
                showMenu(visible.get(pos));
                return true;
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
        });

        findViewById(R.id.btnExport).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportBackup(); }
        });
        findViewById(R.id.btnImport).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { importBackup(); }
        });

        buildTabBar();
        refresh();
    }

    // ---------------- 板块 ----------------

    private void buildTabBar() {
        LinearLayout bar = findViewById(R.id.tabs);
        bar.removeAllViews();
        for (String[] t : TABS) {
            Button btn = new Button(this);
            btn.setText(t[1]);
            btn.setAllCaps(false);
            btn.setPadding(dp(14), dp(8), dp(14), dp(8));
            final String tab = t[0];
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { switchTab(tab); }
            });
            bar.addView(btn);
        }
    }

    private void switchTab(String tab) {
        currentTab = tab;
        filterAuthor = null;
        filterFunc = null;
        search.setText("");
        query = "";
        refresh();
    }

    private List<Entry> rawOfTab() {
        if ("backup".equals(currentTab)) return new ArrayList<>();
        return store.byTab(currentTab);
    }

    private void refresh() {
        boolean isBackup = "backup".equals(currentTab);
        grid.setVisibility(isBackup ? View.GONE : View.VISIBLE);
        backupPanel.setVisibility(isBackup ? View.VISIBLE : View.GONE);
        if (isBackup) {
            backupInfo.setText("当前共 " + store.entries.size()
                    + " 条数据。\n\n备份为 JSON 文件，导入时同 id 覆盖、新条目追加。\n外部浏览器里的聊天记录 App 读不到，也带不走。");
            return;
        }

        visible.clear();
        List<Entry> all = rawOfTab();
        for (Entry e : all) {
            if (filterAuthor != null && !filterAuthor.equals(nz(e.author))) continue;
            if (filterFunc != null && !filterFunc.equals(nz(e.func))) continue;
            if (!query.isEmpty()) {
                String hay = (nz(e.name) + " " + nz(e.author) + " " + nz(e.func)).toLowerCase(Locale.ROOT);
                if (!hay.contains(query)) continue;
            }
            visible.add(e);
        }
        adapter.notifyDataSetChanged();
        renderChips(all);
    }

    private void renderChips(List<Entry> all) {
        chipsBox.removeAllViews();
        List<String> authors = new ArrayList<>();
        List<String> funcs = new ArrayList<>();
        for (Entry e : all) {
            if (!authors.contains(nz(e.author))) authors.add(nz(e.author));
            if (!funcs.contains(nz(e.func))) funcs.add(nz(e.func));
        }
        addChip("全部 (" + all.size() + ")", filterAuthor == null && filterFunc == null, new Runnable() {
            public void run() { filterAuthor = null; filterFunc = null; refresh(); }
        });
        for (final String a : authors) {
            addChip("✍️ " + a + " (" + countBy(all, a, true) + ")", filterAuthor != null && filterAuthor.equals(a), new Runnable() {
                public void run() { filterAuthor = a; filterFunc = null; refresh(); }
            });
        }
        for (final String f : funcs) {
            addChip("🏷️ " + f + " (" + countBy(all, f, false) + ")", filterFunc != null && filterFunc.equals(f), new Runnable() {
                public void run() { filterFunc = f; filterAuthor = null; refresh(); }
            });
        }
    }

    private int countBy(List<Entry> all, String v, boolean author) {
        int n = 0;
        for (Entry e : all) if (author ? v.equals(nz(e.author)) : v.equals(nz(e.func))) n++;
        return n;
    }

    private void addChip(String label, boolean active, final Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        t.setTextColor(active ? 0xFFFFFFFF : 0xFF4A3828);
        t.setBackgroundColor(active ? 0xFFD9A441 : 0xFFF1E4CE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onClick.run(); }
        });
        chipsBox.addView(t);
    }
  // ---------------- 卡片 ----------------

    private boolean canImport() {
        return "world".equals(currentTab) || "char".equals(currentTab)
                || "beauty".equals(currentTab) || "font".equals(currentTab);
    }

    private class EntryAdapter extends BaseAdapter {
        public int getCount() { return visible.size() + 1 + (canImport() ? 1 : 0); }
        public Object getItem(int p) { return p < visible.size() ? visible.get(p) : null; }
        public long getItemId(int p) { return p; }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(10), dp(12), dp(10), dp(12));
            card.setBackgroundColor(0xFFFFFFFF);

            if (pos == visible.size()) {
                TextView add = new TextView(MainActivity.this);
                add.setText("＋\n添加");
                add.setGravity(Gravity.CENTER);
                add.setTextColor(0xFF8B7355);
                card.addView(add);
                return card;
            }
            if (pos == visible.size() + 1 && canImport()) {
                TextView imp = new TextView(MainActivity.this);
                imp.setText("📄\n导入文件");
                imp.setGravity(Gravity.CENTER);
                imp.setTextColor(0xFF8B7355);
                card.addView(imp);
                return card;
            }

            Entry e = visible.get(pos);
            Bitmap bmp = decodeIcon(e.icon);
            if (bmp != null) {
                ImageView icon = new ImageView(MainActivity.this);
                icon.setImageBitmap(bmp);
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
                card.addView(icon);
            } else {
                TextView emoji = new TextView(MainActivity.this);
                emoji.setText(emojiOf(e.tab));
                emoji.setTextSize(26);
                emoji.setGravity(Gravity.CENTER);
                card.addView(emoji);
            }

            TextView name = new TextView(MainActivity.this);
            name.setText(e.name);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setTextSize(12);
            name.setTextColor(0xFF3A2E22);
            card.addView(name);

            TextView tags = new TextView(MainActivity.this);
            tags.setText((nz(e.author).isEmpty() ? "未分类" : nz(e.author))
                    + " · " + (nz(e.func).isEmpty() ? "未分类" : nz(e.func)));
            tags.setTextSize(10);
            tags.setTextColor(0xFF8B7355);
            tags.setGravity(Gravity.CENTER);
            card.addView(tags);
            return card;
        }
    }

    private String emojiOf(String tab) {
        switch (tab == null ? "" : tab) {
            case "api": return "🔌";
            case "world": return "📄";
            case "char": return "👤";
            case "beauty": return "🎨";
            case "font": return "🔤";
            default: return "📱";
        }
    }

    // ---------------- 编辑弹窗 ----------------

    private void openEdit(Entry e) {
        final Entry entry = e == null ? new Entry() : e;
        boolean isNew = e == null;
        FormHolder holder = buildForm(entry);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(isNew ? "新增条目" : "编辑：" + entry.name);
        b.setView(holder.scroll);
        b.setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                String name = holder.fields.get("name").getText().toString().trim();
                if (name.isEmpty()) { toast("名称不能为空"); return; }
                entry.id = (entry.id == null || entry.id.isEmpty()) ? UUID.randomUUID().toString() : entry.id;
                entry.tab = currentTab;
                entry.name = name;
                entry.url = text(holder, "url");
                entry.entry = text(holder, "entry");
                entry.pwd = text(holder, "pwd");
                entry.email = text(holder, "email");
                entry.baseUrl = text(holder, "baseUrl");
                entry.key = text(holder, "key");
                entry.author = text(holder, "author");
                entry.func = text(holder, "func");
                entry.content = text(holder, "content");
                entry.kind = text(holder, "kind");
                entry.iconLocked = holder.lock.isChecked();
                entry.updatedAt = System.currentTimeMillis();
                store.upsert(entry);
                refresh();
                toast("已保存");
            }
        });
        b.setNegativeButton("取消", null);
        currentDialog = b.create();
        currentDialog.show();
    }

    private String text(FormHolder h, String key) {
        EditText et = h.fields.get(key);
        return et == null ? "" : et.getText().toString().trim();
    }

    private static class FormHolder {
        FormHolder(android.widget.ScrollView s, Map<String, EditText> f, CheckBox l) {
            scroll = s; fields = f; lock = l;
        }
        final android.widget.ScrollView scroll;
        final Map<String, EditText> fields;
        final CheckBox lock;
    }

    private FormHolder buildForm(Entry e) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(12), dp(16), dp(12));
        Map<String, EditText> fields = new HashMap<>();

        addField(form, fields, "name", "名称", e.name, false);
        addField(form, fields, "url", "网址", e.url, false);
        addField(form, fields, "entry", "进入方式", e.entry, false);
        addField(form, fields, "pwd", "密码", e.pwd, false);
        addField(form, fields, "email", "邮箱", e.email, false);
        addField(form, fields, "baseUrl", "Base URL", e.baseUrl, false);
        addField(form, fields, "key", "Key", e.key, false);
        addField(form, fields, "author", "作者分类", e.author, false);
        addField(form, fields, "func", "功能分类", e.func, false);
        addField(form, fields, "kind", "类型（css / font / txt…）", e.kind, false);
        addField(form, fields, "content", "内容", e.content, true);

        CheckBox lock = new CheckBox(this);
        lock.setText("🔒 锁定图标（锁定后不被覆盖，改完不会闪回）");
        lock.setChecked(e.iconLocked);
        form.addView(lock);

        Button iconBtn = new Button(this);
        iconBtn.setText("🖼️ 用本地图片更换图标");
        iconBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pendingIconId = e.id; pickIcon(); }
        });
        form.addView(iconBtn);

        sv.addView(form);
        return new FormHolder(sv, fields, lock);
    }

    private void addField(LinearLayout form, Map<String, EditText> fields, String key, String label, String value, boolean multiline) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(0xFF6B5540);
        form.addView(t);
        EditText et = new EditText(this);
        et.setText(nz(value));
        if (multiline) et.setMinLines(5); else et.setSingleLine(true);
        fields.put(key, et);
        form.addView(et);
    }
  // ---------------- 长按菜单 ----------------

    private void showMenu(final Entry e) {
        List<String> items = new ArrayList<>();
        items.add("✏️ 编辑此项");
        items.add("🏷️ 修改分类");
        items.add("⬆️ 上移一位");
        items.add("⬇️ 下移一位");
        items.add(e.iconLocked ? "🔓 解锁图标" : "🔒 锁定图标");
        items.add("🖼️ 更换图标");
        if (!nz(e.url).isEmpty()) items.add("🌐 打开链接");
        items.add("🗑️ 删除此项");

        currentDialog = new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        String act = items.get(which);
                        if (act.startsWith("✏️")) openEdit(e);
                        else if (act.startsWith("🏷️")) categoryDialog(e);
                        else if (act.startsWith("⬆️")) moveEntry(e, -1);
                        else if (act.startsWith("⬇️")) moveEntry(e, 1);
                        else if (act.startsWith("🔒")) { e.iconLocked = true; store.upsert(e); toast("已锁定图标"); refresh(); }
                        else if (act.startsWith("🔓")) { e.iconLocked = false; store.upsert(e); toast("已解锁图标"); refresh(); }
                        else if (act.startsWith("🖼️")) { pendingIconId = e.id; pickIcon(); }
                        else if (act.startsWith("🌐")) openSite(e);
                        else if (act.startsWith("🗑️")) confirmDelete(e);
                    }
                })
                .create();
        currentDialog.show();
    }

    private void categoryDialog(final Entry e) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(12), dp(16), dp(12));
        final EditText author = new EditText(this);
        author.setHint("作者分类");
        author.setText(nz(e.author));
        final EditText func = new EditText(this);
        func.setHint("功能分类");
        func.setText(nz(e.func));
        form.addView(author);
        form.addView(func);

        currentDialog = new AlertDialog.Builder(this)
                .setTitle("修改分类：" + e.name)
                .setView(form)
                .setPositiveButton("确认", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        e.author = author.getText().toString().trim();
                        e.func = func.getText().toString().trim();
                        store.upsert(e);
                        refresh();
                        toast("分类已修改");
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        currentDialog.show();
    }

    private void moveEntry(Entry e, int dir) {
        List<Entry> all = rawOfTab();
        int i = all.indexOf(e);
        int j = i + dir;
        if (i < 0 || j < 0 || j >= all.size()) { toast("已经是边界了"); return; }
        Entry a = all.get(i), b = all.get(j);
        int ia = store.entries.indexOf(a), ib = store.entries.indexOf(b);
        if (ia < 0 || ib < 0) return;
        store.entries.set(ia, b);
        store.entries.set(ib, a);
        store.save();
        refresh();
    }

    private void confirmDelete(final Entry e) {
        currentDialog = new AlertDialog.Builder(this)
                .setMessage("确定删除「" + e.name + "」吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        store.remove(e.id);
                        refresh();
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        currentDialog.show();
    }

    // ---------------- 图标 ----------------

    private void pickIcon() {
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setType("image/*");
        try { startActivityForResult(i, REQ_ICON); } catch (Throwable t) { toast("没有可用的图片选择器"); }
    }

    private Bitmap decodeIcon(String dataUrl) {
        if (dataUrl == null || dataUrl.isEmpty()) return null;
        try {
            String b64 = dataUrl;
            int c = b64.indexOf(",");
            if (c >= 0) b64 = b64.substring(c + 1);
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            return scaleDown(raw, 256);
        } catch (Throwable t) { return null; }
    }

    private Bitmap scaleDown(Bitmap src, int max) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= max && h <= max) return src;
        float ratio = Math.min((float) max / w, (float) max / h);
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * ratio)), Math.max(1, Math.round(h * ratio)), true);
    }

    private String encodeIcon(Bitmap bmp) {
        Bitmap s = scaleDown(bmp, 256);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.PNG, 80, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    // ---------------- 打开链接 ----------------

    private void openSite(final Entry e) {
        String url = nz(e.url);
        if (url.isEmpty()) { toast("这条没有网址"); return; }
        final Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(view, 0);
        if (list == null || list.isEmpty()) {
            startActivity(Intent.createChooser(view, "选择浏览器打开"));
            return;
        }
        List<String> names = new ArrayList<>();
        for (ResolveInfo r : list) names.add(r.loadLabel(getPackageManager()).toString());

        currentDialog = new AlertDialog.Builder(this)
                .setTitle("用哪个浏览器打开？")
                .setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        ResolveInfo r = list.get(w);
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.setPackage(r.activityInfo.packageName);
                        try { startActivity(i); } catch (Throwable t) {
                            startActivity(Intent.createChooser(view, "选择浏览器打开"));
                        }
                    }
                })
                .create();
        currentDialog.show();
    }
  // ---------------- 文件导入 --------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, REQ_FILE); } catch (Throwable t) { toast("没有可用的文件选择器"); }
    }

    private void importFromUri(Uri uri) throws Exception {
        byte[] data = readBytes(uri);
        String name = displayName(uri);
        String low = name.toLowerCase(Locale.ROOT);
        String text;
        String kind;
        if (low.endsWith(".docx")) { text = Docx.parseDocx(data); kind = "docx"; }
        else { text = Docx.parseText(data); kind = low.endsWith(".txt") ? "txt" : "file"; }

        Entry e = new Entry();
        e.id = UUID.randomUUID().toString();
        e.tab = currentTab;
        e.name = name.replaceAll("\\.[^.]+$", "");
        e.content = text;
        e.kind = kind;
        e.author = "";
        e.func = "";
        e.updatedAt = System.currentTimeMillis();
        store.upsert(e);
        refresh();
        toast("已导入：" + e.name);
    }

    // ---------------- 备份 ----------------

    private void exportBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "HappyHome备份.json");
        try { startActivityForResult(i, REQ_EXPORT); } catch (Throwable t) { toast("当前系统不支持导出文件"); }
    }

    private void writeBackup(Uri uri) throws Exception {
        String json = store.exportJson();
        OutputStream out = getContentResolver().openOutputStream(uri);
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
        toast("备份已导出");
    }

    private void importBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/json");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, REQ_IMPORT); } catch (Throwable t) { toast("没有可用的文件选择器"); }
    }

    private void readBackup(Uri uri) throws Exception {
        String text = new String(readBytes(uri), StandardCharsets.UTF_8);
        int n = store.importJson(text);
        refresh();
        toast("已导入 " + n + " 条");
    }

    // ---------------- 回调 --------------

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_ICON) {
                Bitmap bmp = BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
                if (bmp == null) { toast("图片解析失败"); return; }
                Entry e = store.find(pendingIconId);
                if (e == null) { toast("条目已不存在"); return; }
                e.icon = encodeIcon(bmp);
                store.upsert(e);
                refresh();
                toast("图标已更换");
            } else if (req == REQ_FILE) {
                importFromUri(uri);
            } else if (req == REQ_EXPORT) {
                writeBackup(uri);
            } else if (req == REQ_IMPORT) {
                readBackup(uri);
            }
        } catch (Throwable t) {
            toast("处理失败：" + t.getMessage());
        }
    }

    // ---------------- 工具 --------------

    private byte[] readBytes(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        in.close();
        return out.toByteArray();
    }

    private String displayName(Uri uri) {
        String name = "未命名";
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.moveToFirst()) name = c.getString(i);
                c.close();
            }
        } catch (Throwable ignored) {}
        if (name == null || name.isEmpty()) name = "未命名";
        return name;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private String nz(String s) { return s == null ? "" : s; }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    @Override
    public void onBackPressed() {
        if (currentDialog != null && currentDialog.isShowing()) { currentDialog.dismiss(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 3500) backCount++; else backCount = 1;
        lastBackAt = now;
        if (backCount >= 3) { backCount = 0; finish(); }
        else toast("再按 " + (3 - backCount) + " 次退出");
    }
  // =====================================================================
    // 数据实体
    // =====================================================================
    static class Entry {
        String id, tab, name, url, entry, pwd, email, baseUrl, key, author, func, content, kind, icon;
        boolean iconLocked;
        long updatedAt;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", nz(id));
            o.put("tab", nz(tab));
            o.put("name", nz(name));
            o.put("url", nz(url));
            o.put("entry", nz(entry));
            o.put("pwd", nz(pwd));
            o.put("email", nz(email));
            o.put("baseUrl", nz(baseUrl));
            o.put("key", nz(key));
            o.put("author", nz(author));
            o.put("func", nz(func));
            o.put("content", nz(content));
            o.put("kind", nz(kind));
            o.put("icon", nz(icon));
            o.put("iconLocked", iconLocked);
            o.put("updatedAt", updatedAt);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optString("id");
            e.tab = o.optString("tab");
            e.name = o.optString("name");
            e.url = o.optString("url");
            e.entry = o.optString("entry");
            e.pwd = o.optString("pwd");
            e.email = o.optString("email");
            e.baseUrl = o.optString("baseUrl");
            e.key = o.optString("key");
            e.author = o.optString("author");
            e.func = o.optString("func");
            e.content = o.optString("content");
            e.kind = o.optString("kind");
            e.icon = o.optString("icon");
            e.iconLocked = o.optBoolean("iconLocked");
            e.updatedAt = o.optLong("updatedAt");
            return e;
        }

        static String nz(String s) { return s == null ? "" : s; }
    }

    // =====================================================================
    // 本地存储
    // =====================================================================
    static class Store {
        private final android.content.Context ctx;
        final List<Entry> entries = new ArrayList<>();

        Store(android.content.Context ctx) { this.ctx = ctx; load(); }

        private File file() { return new File(ctx.getFilesDir(), "hh_data.json"); }

        synchronized void load() {
            entries.clear();
            try {
                File f = file();
                if (!f.exists()) return;
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int r = in.read(buf);
                in.close();
                if (r <= 0) return;
                JSONObject root = new JSONObject(new String(buf, 0, r, StandardCharsets.UTF_8));
                JSONArray arr = root.optJSONArray("entries");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) entries.add(Entry.fromJson(arr.getJSONObject(i)));
                }
            } catch (Throwable t) { t.printStackTrace(); }
        }

        synchronized void save() {
            try {
                JSONObject root = new JSONObject();
                JSONArray arr = new JSONArray();
                for (Entry e : entries) arr.put(e.toJson());
                root.put("format", "HappyHomeNative");
                root.put("version", 1);
                root.put("entries", arr);
                FileOutputStream out = new FileOutputStream(file());
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.close();
            } catch (Throwable t) { t.printStackTrace(); }
        }

        List<Entry> byTab(String tab) {
            List<Entry> out = new ArrayList<>();
            for (Entry e : entries) if (tab.equals(e.tab)) out.add(e);
            return out;
        }

        Entry find(String id) {
            if (id == null) return null;
            for (Entry e : entries) if (id.equals(e.id)) return e;
            return null;
        }

        void upsert(Entry e) {
            Entry old = find(e.id);
            if (old != null) entries.remove(old);
            entries.add(e);
            save();
        }

        void remove(String id) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (id != null && id.equals(entries.get(i).id)) entries.remove(i);
            }
            save();
        }

        String exportJson() throws Exception {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Entry e : entries) arr.put(e.toJson());
            root.put("format", "HappyHomeBackup");
            root.put("version", 1);
            root.put("entries", arr);
            return root.toString();
        }

        int importJson(String text) throws Exception {
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) return 0;
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                Entry e = Entry.fromJson(arr.getJSONObject(i));
                Entry old = find(e.id);
                if (old != null) entries.remove(old);
                entries.add(e);
                n++;
            }
            save();
            return n;
        }
    }

    // =====================================================================
    // docx / txt 解析
    // =====================================================================
    static final class Docx {
        static String parseDocx(byte[] data) throws Exception {
            StringBuilder sb = new StringBuilder();
            ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(data));
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(ze.getName())) {
                    byte[] buf = new byte[8192];
                    int r;
                    StringBuilder xml = new StringBuilder();
                    while ((r = zis.read(buf)) > 0) xml.append(new String(buf, 0, r, StandardCharsets.UTF_8));
                    sb.append(extractText(xml.toString()));
                }
                zis.closeEntry();
            }
            zis.close();
            return sb.toString().trim();
        }

        private static String extractText(String xml) {
            String x = xml.replaceAll("</w:p>", "\n");
            StringBuilder out = new StringBuilder();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<w:t[^>]*>(.*?)</w:t>", java.util.regex.Pattern.DOTALL)
                    .matcher(x);
            while (m.find()) {
                out.append(m.group(1).replace("&amp;", "&").replace("&lt;", "<")
                        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'"));
            }
            if (out.length() == 0) out.append(x.replaceAll("<[^>]+>", "").trim());
            return out.toString();
        }

        static String parseText(byte[] data) {
            String utf8 = new String(data, StandardCharsets.UTF_8);
            if (!utf8.contains("\uFFFD")) return utf8;
            for (String cs : new String[]{"GB18030", "GBK", "BIG5"}) {
                try {
                    String t = new String(data, Charset.forName(cs));
                    if (!t.contains("\uFFFD")) return t;
                } catch (Throwable ignored) {}
            }
            return utf8;
        }
    }
}
