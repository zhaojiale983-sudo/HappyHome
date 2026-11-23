package com.happyhome.hhn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
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
import android.widget.ScrollView;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HappyHome 原生版 v2
 * 单文件：板块 / 卡片 / 分类 / 批量 / 图标抓取 / 打开方式 / 文件导入 / 备份存档
 */
public class MainActivity extends Activity {

    private static final int REQ_ICON = 101;
    private static final int REQ_FILE = 102;
    private static final int REQ_EXPORT = 103;
    private static final int REQ_IMPORT = 104;

    static final String S_PHONE = "phone";
    static final String S_API = "api";
    static final String S_WORLD = "world";
    static final String S_CHAR = "char";
    static final String S_BEAUTY = "beauty";
    static final String S_FONT = "font";
    static final String S_BACKUP = "backup";

    private static final String[][] TABS = {
            {"phone", "📱小手机"},
            {"api", "🔌API"},
            {"world", "📖世界书"},
            {"char", "👤人设"},
            {"beauty", "🎀美化"},
            {"font", "🔤字体"},
            {"backup", "🗂️备份"},
    };

    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_MAIN = 0xFF4A3828;
    private static final int C_TXT2 = 0xFF8B7355;
    private static final int C_ACC = 0xFFD9A441;
    private static final int C_SOFT = 0xFFF1E4CE;
    private static final int C_SEL = 0xFFE9DCC3;

    private Store store;
    private GridView grid;
    private LinearLayout chipsBox;
    private LinearLayout backupPanel;
    private EditText search;

    private String currentTab = S_PHONE;
    private String filterAuthor = null;
    private String filterFunc = null;
    private String filterType = null;
    private String query = "";

    private final List<Entry> visible = new ArrayList<Entry>();
    private EntryAdapter adapter;

    private AlertDialog currentDialog;
    private String pendingIconId = null;

    private boolean batchMode = false;
    private final List<String> selectedIds = new ArrayList<String>();

    private int backCount = 0;
    private long lastBackAt = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        store = new Store(this);
        grid = (GridView) findViewById(R.id.grid);
        chipsBox = (LinearLayout) findViewById(R.id.chips);
        backupPanel = (LinearLayout) findViewById(R.id.backupPanel);
        search = (EditText) findViewById(R.id.search);

        adapter = new EntryAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                if (S_BACKUP.equals(currentTab)) return;
                if (pos < visible.size()) {
                    if (batchMode) { toggleSelect(visible.get(pos)); return; }
                    detail(visible.get(pos));
                    return;
                }
                if (pos == visible.size()) { openEdit(null); return; }
                if (pos == visible.size() + 1 && canImport()) { pickFile(); }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View v, int pos, long id) {
                if (S_BACKUP.equals(currentTab)) return true;
                if (pos >= visible.size()) return true;
                menu(visible.get(pos));
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

        buildTabBar();
        refresh();
    }

    // ---------------- 板块 ----------------

    private void buildTabBar() {
        LinearLayout bar = (LinearLayout) findViewById(R.id.tabs);
        bar.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            Button btn = new Button(this);
            btn.setText(TABS[i][1]);
            btn.setAllCaps(false);
            btn.setTextSize(12);
            btn.setPadding(dp(10), dp(8), dp(10), dp(8));
            final String tab = TABS[i][0];
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
        filterType = null;
        batchMode = false;
        selectedIds.clear();
        search.setText("");
        query = "";
        refresh();
    }

    private List<Entry> rawOfTab() {
        if (S_BACKUP.equals(currentTab)) return new ArrayList<Entry>();
        return store.bySection(currentTab);
    }

    private void refresh() {
        boolean isBackup = S_BACKUP.equals(currentTab);
        grid.setVisibility(isBackup ? View.GONE : View.VISIBLE);
        backupPanel.setVisibility(isBackup ? View.VISIBLE : View.GONE);
        if (isBackup) { renderBackupPanel(); return; }

        visible.clear();
        List<Entry> all = rawOfTab();
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (filterAuthor != null && !filterAuthor.equals(nz(e.author))) continue;
            if (filterFunc != null && !filterFunc.equals(nz(e.func))) continue;
            if (filterType != null && !filterType.equals(nz(e.phoneType))) continue;
            if (!query.isEmpty()) {
                String hay = (nz(e.name) + " " + nz(e.author) + " " + nz(e.func) + " " + nz(e.url))
                        .toLowerCase(Locale.ROOT);
                if (!hay.contains(query)) continue;
            }
            visible.add(e);
        }
        adapter.notifyDataSetChanged();
        renderChips(all);
    }

    private void renderChips(List<Entry> all) {
        chipsBox.removeAllViews();

        if (batchMode) {
            addChip("✅ 已选 " + selectedIds.size(), true, new Runnable() {
                public void run() { applyBatchMove(); }
            });
            addChip("📁 批量移动分类", false, new Runnable() {
                public void run() { applyBatchMove(); }
            });
            addChip("🗑️ 批量删除", false, new Runnable() {
                public void run() { applyBatchDelete(); }
            });
            addChip("✖️ 退出批量", false, new Runnable() {
                public void run() { batchMode = false; selectedIds.clear(); refresh(); }
            });
        }

        addChip("全部 (" + all.size() + ")", filterAuthor == null && filterFunc == null && filterType == null, new Runnable() {
            public void run() { filterAuthor = null; filterFunc = null; filterType = null; refresh(); }
        });

        if (S_PHONE.equals(currentTab)) {
            int free = 0, paid = 0;
            for (int i = 0; i < all.size(); i++) {
                if ("paid".equals(nz(all.get(i).phoneType))) paid++; else free++;
            }
            addChip("📱 免费 (" + free + ")", "free".equals(filterType), new Runnable() {
                public void run() {
                    filterType = "free".equals(filterType) ? null : "free";
                    filterAuthor = null; filterFunc = null; refresh();
                }
            });
            addChip("💎 付费 (" + paid + ")", "paid".equals(filterType), new Runnable() {
                public void run() {
                    filterType = "paid".equals(filterType) ? null : "paid";
                    filterAuthor = null; filterFunc = null; refresh();
                }
            });
        }

        List<String> authors = store.catAuthors(currentTab);
        List<String> funcs = store.catFuncs(currentTab);
        for (int i = 0; i < all.size(); i++) {
            String a = nz(all.get(i).author);
            String f = nz(all.get(i).func);
            if (!a.isEmpty() && !authors.contains(a)) authors.add(a);
            if (!f.isEmpty() && !funcs.contains(f)) funcs.add(f);
        }

        for (int i = 0; i < authors.size(); i++) {
            final String a = authors.get(i);
            addChip("✍️ " + a + " (" + countBy(all, a, true) + ")", a.equals(filterAuthor), new Runnable() {
                public void run() {
                    filterAuthor = a.equals(filterAuthor) ? null : a;
                    filterFunc = null; refresh();
                }
            });
        }
        for (int i = 0; i < funcs.size(); i++) {
            final String f = funcs.get(i);
            addChip("🏷️ " + f + " (" + countBy(all, f, false) + ")", f.equals(filterFunc), new Runnable() {
                public void run() {
                    filterFunc = f.equals(filterFunc) ? null : f;
                    refresh();
                }
            });
        }

        addChip("⚙️ 分类管理", false, new Runnable() {
            public void run() { catManage(); }
        });
        if (!batchMode) {
            addChip("☑️ 批量模式", false, new Runnable() {
                public void run() { batchMode = true; selectedIds.clear(); refresh(); }
            });
        }
    }

    private int countBy(List<Entry> all, String v, boolean author) {
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (author ? v.equals(nz(e.author)) : v.equals(nz(e.func))) n++;
        }
        return n;
    }

    private void addChip(String label, boolean active, final Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        t.setTextColor(active ? 0xFFFFFFFF : C_MAIN);
        t.setBackgroundColor(active ? C_ACC : C_SOFT);
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
        return S_WORLD.equals(currentTab) || S_CHAR.equals(currentTab)
                || S_BEAUTY.equals(currentTab) || S_FONT.equals(currentTab);
    }

    private class EntryAdapter extends BaseAdapter {
        public int getCount() { return visible.size() + 1 + (canImport() ? 1 : 0); }
        public Object getItem(int p) { return p < visible.size() ? visible.get(p) : null; }
        public long getItemId(int p) { return p; }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(8), dp(10), dp(8), dp(10));

            if (pos == visible.size()) {
                card.setBackgroundColor(C_CARD);
                TextView add = new TextView(MainActivity.this);
                add.setText("＋\n添加");
                add.setGravity(Gravity.CENTER);
                add.setTextColor(C_TXT2);
                card.addView(add);
                return card;
            }
            if (pos == visible.size() + 1 && canImport()) {
                card.setBackgroundColor(C_CARD);
                TextView imp = new TextView(MainActivity.this);
                imp.setText("📄\n导入文件");
                imp.setGravity(Gravity.CENTER);
                imp.setTextColor(C_TXT2);
                card.addView(imp);
                return card;
            }

            Entry e = visible.get(pos);
            boolean sel = selectedIds.contains(nz(e.id));
            card.setBackgroundColor(sel ? C_SEL : C_CARD);

            Bitmap bmp = decodeIcon(e.icon);
            if (bmp != null) {
                ImageView icon = new ImageView(MainActivity.this);
                icon.setImageBitmap(bmp);
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
                card.addView(icon);
            } else {
                TextView emoji = new TextView(MainActivity.this);
                emoji.setText(emojiOf(e.section));
                emoji.setTextSize(24);
                emoji.setGravity(Gravity.CENTER);
                card.addView(emoji);
            }

            TextView name = new TextView(MainActivity.this);
            name.setText(nz(e.name));
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setTextSize(11);
            name.setTextColor(C_MAIN);
            card.addView(name);

            String sub = nz(e.author).isEmpty() ? "未分类" : nz(e.author);
            if (!nz(e.func).isEmpty()) sub = sub + " · " + nz(e.func);
            if ("paid".equals(nz(e.phoneType))) sub = "💎 " + sub;
            TextView tags = new TextView(MainActivity.this);
            tags.setText(sel ? "✅ " + sub : sub);
            tags.setTextSize(9);
            tags.setTextColor(C_TXT2);
            tags.setGravity(Gravity.CENTER);
            tags.setMaxLines(2);
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

    // ---------------- 详情 ----------------

    private void detail(Entry e) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        sv.addView(box);

        addInfoRow(box, "名称", nz(e.name));
        if (S_PHONE.equals(e.section)) {
            addInfoRow(box, "手机类型", "paid".equals(nz(e.phoneType)) ? "💎 付费小手机" : "📱 免费小手机");
        }
        if (!nz(e.url).isEmpty()) addInfoRow(box, "链接", nz(e.url));
        if (!nz(e.baseUrl).isEmpty()) addInfoRow(box, "地址", nz(e.baseUrl));
        if (!nz(e.entry).isEmpty()) addInfoRow(box, "进入方式", nz(e.entry));
        if (!nz(e.email).isEmpty()) addInfoRow(box, "邮箱", nz(e.email));
        if (!nz(e.pwd).isEmpty()) addInfoRow(box, "密码", nz(e.pwd));
        if (!nz(e.key).isEmpty()) addInfoRow(box, "Key", nz(e.key));
        addInfoRow(box, "作者分类", nz(e.author).isEmpty() ? "未分类" : nz(e.author));
        addInfoRow(box, "功能分类", nz(e.func).isEmpty() ? "未分类" : nz(e.func));
        if (!nz(e.kind).isEmpty()) addInfoRow(box, "文件类型", nz(e.kind));
        if (!nz(e.content).isEmpty()) addInfoRow(box, "内容", nz(e.content));

        if (!nz(e.url).isEmpty() || !nz(e.baseUrl).isEmpty()) {
            Button open = new Button(this);
            open.setText("🌐 打开 ›");
            open.setAllCaps(false);
            open.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (currentDialog != null) currentDialog.dismiss();
                    openEntry(e);
                }
            });
            box.addView(open);
        }

        Button edit = new Button(this);
        edit.setText("✏️ 查看与编辑");
        edit.setAllCaps(false);
        edit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentDialog != null) currentDialog.dismiss();
                openEdit(e);
            }
        });
        box.addView(edit);

        Button del = new Button(this);
        del.setText("🗑️ 删除此卡片");
        del.setAllCaps(false);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentDialog != null) currentDialog.dismiss();
                confirmDelete(e);
            }
        });
        box.addView(del);

        currentDialog = new AlertDialog.Builder(this)
                .setTitle(nz(e.name))
                .setView(sv)
                .setPositiveButton("关闭", null)
                .create();
        currentDialog.show();
    }

    private void addInfoRow(LinearLayout box, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(label + "：" + value);
        tv.setTextSize(13);
        tv.setTextColor(C_MAIN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        row.addView(tv);

        final String copy = value;
        Button btn = new Button(this);
        btn.setText("复制");
        btn.setAllCaps(false);
        btn.setTextSize(11);
        btn.setPadding(dp(8), 0, dp(8), 0);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyText(copy); }
        });
        row.addView(btn);

        box.addView(row);
    }

    private void copyText(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("HappyHome", nz(s)));
            toast("已复制");
        } catch (Throwable t) { toast("复制失败"); }
    }

    // ---------------- 长按菜单 ----------------

    private void menu(final Entry e) {
        final List<String> items = new ArrayList<String>();
        items.add("✏️ 编辑此项");
        items.add("🏷️ 移动分类");
        items.add("⬆️ 上移一位");
        items.add("⬇️ 下移一位");
        items.add(e.iconLocked ? "🔓 解锁图标" : "🔒 锁定图标");
        items.add("🖼️ 更换图标（本地图片）");
        items.add("✨ 抓取原站图标");
        if (!nz(e.url).isEmpty()) items.add("🌐 打开链接");
        items.add("☑️ 进入批量选择");
        items.add("🗑️ 删除此项");

        currentDialog = new AlertDialog.Builder(this)
                .setTitle(nz(e.name))
                .setItems(items.toArray(new String[items.size()]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String act = items.get(which);
                        if (act.startsWith("✏️")) openEdit(e);
                        else if (act.startsWith("🏷️")) categoryDialog(e);
                        else if (act.startsWith("⬆️")) moveEntry(e, -1);
                        else if (act.startsWith("⬇️")) moveEntry(e, 1);
                        else if (act.startsWith("🔒")) { e.iconLocked = true; store.upsert(e); toast("已锁定图标"); refresh(); }
                        else if (act.startsWith("🔓")) { e.iconLocked = false; store.upsert(e); toast("已解锁图标"); refresh(); }
                        else if (act.startsWith("🖼️")) { pendingIconId = e.id; pickIcon(); }
                        else if (act.startsWith("✨")) grabIcon(e);
                        else if (act.startsWith("🌐")) openEntry(e);
                        else if (act.startsWith("☑️")) { batchMode = true; selectedIds.clear(); selectedIds.add(nz(e.id)); refresh(); }
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
                .setTitle("移动分类：" + nz(e.name))
                .setView(form)
                .setPositiveButton("确认修改", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
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
        if (i < 0 || j < 0 || j >= all.size()) { toast("已经到边界了"); return; }
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
                .setMessage("确定删除「" + nz(e.name) + "」吗？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        store.remove(e.id);
                        refresh();
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        currentDialog.show();
    }

    // ---------------- 批量 ----------------

    private void toggleSelect(Entry e) {
        String id = nz(e.id);
        if (selectedIds.contains(id)) selectedIds.remove(id); else selectedIds.add(id);
        refresh();
    }

    private void applyBatchMove() {
        if (selectedIds.isEmpty()) { toast("还没选卡片"); return; }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(12), dp(16), dp(12));
        final EditText author = new EditText(this);
        author.setHint("目标作者分类（留空不改）");
        final EditText func = new EditText(this);
        func.setHint("目标功能分类（留空不改）");
        form.addView(author);
        form.addView(func);

        currentDialog = new AlertDialog.Builder(this)
                .setTitle("批量移动 " + selectedIds.size() + " 张卡片")
                .setView(form)
                .setPositiveButton("确认批量移动", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String a = author.getText().toString().trim();
                        String f = func.getText().toString().trim();
                        for (int i = 0; i < store.entries.size(); i++) {
                            Entry e = store.entries.get(i);
                            if (!selectedIds.contains(nz(e.id))) continue;
                            if (!a.isEmpty()) e.author = a;
                            if (!f.isEmpty()) e.func = f;
                        }
                        store.save();
                        batchMode = false;
                        selectedIds.clear();
                        refresh();
                        toast("已移动分类");
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        currentDialog.show();
    }

    private void applyBatchDelete() {
        if (selectedIds.isEmpty()) { toast("还没选卡片"); return; }
        currentDialog = new AlertDialog.Builder(this)
                .setMessage("确定删除选中的 " + selectedIds.size() + " 张卡片吗？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        for (int i = store.entries.size() - 1; i >= 0; i--) {
                            if (selectedIds.contains(nz(store.entries.get(i).id))) store.entries.remove(i);
                        }
                        store.save();
                        batchMode = false;
                        selectedIds.clear();
                        refresh();
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        currentDialog.show();
    }

    // ---------------- 分类管理 ----------------

    private void catManage() {
        final List<String> authors = store.catAuthors(currentTab);
        final List<String> funcs = store.catFuncs(currentTab);

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(8));
        sv.addView(box);

        TextView ta = new TextView(this);
        ta.setText("作者分类");
        ta.setTextColor(C_TXT2);
        ta.setTextSize(12);
        box.addView(ta);
        for (int i = 0; i < authors.size(); i++) box.addView(catRow(authors, i, true));

        TextView tf = new TextView(this);
        tf.setText("功能分类");
        tf.setTextColor(C_TXT2);
        tf.setTextSize(12);
        tf.setPadding(0, dp(10), 0, 0);
        box.addView(tf);
        for (int i = 0; i < funcs.size(); i++) box.addView(catRow(funcs, i, false));

        Button add = new Button(this);
        add.setText("➕ 新建分类");
        add.setAllCaps(false);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentDialog != null) currentDialog.dismiss();
                newCat();
            }
        });
        box.addView(add);

        currentDialog = new AlertDialog.Builder(this)
                .setTitle("分类管理 · " + tabName(currentTab))
                .setView(sv)
                .setPositiveButton("关闭", null)
                .create();
        currentDialog.show();
    }

    private View catRow(final List<String> list, final int index, final boolean author) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText("· " + list.get(index));
        tv.setTextSize(13);
        tv.setTextColor(C_MAIN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        row.addView(tv);

        Button del = new Button(this);
        del.setText("删除分类");
        del.setAllCaps(false);
        del.setTextSize(11);
        del.setPadding(dp(8), 0, dp(8), 0);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String name = list.get(index);
                store.removeCat(currentTab, author, name);
                if (currentDialog != null) currentDialog.dismiss();
                refresh();
                toast("已删除分类：" + name);
            }
        });
        row.addView(del);
        return row;
    }

    private void newCat() {
        final String[] kinds = {"作者分类", "功能分类"};
        currentDialog = new AlertDialog.Builder(this)
                .setTitle("新建分类")
                .setItems(kinds, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final EditText input = new EditText(MainActivity.this);
                        input.setHint("分类名称");
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("新建" + kinds[which])
                                .setView(input)
                                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dd, int w) {
                                        String name = input.getText().toString().trim();
                                        if (name.isEmpty()) { toast("名称不能为空"); return; }
                                        store.addCat(currentTab, which == 0, name);
                                        refresh();
                                        toast("已新建：" + name);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                })
                .create();
        currentDialog.show();
    }

    private String tabName(String tab) {
        for (int i = 0; i < TABS.length; i++) if (TABS[i][0].equals(tab)) return TABS[i][1];
        return tab;
    }

    // ---------------- 编辑 ----------------

    private static class FormHolder {
        FormHolder(ScrollView s, Map<String, EditText> f, CheckBox p, CheckBox l) {
            scroll = s; fields = f; paid = p; lock = l;
        }
        final ScrollView scroll;
        final Map<String, EditText> fields;
        final CheckBox paid;
        final CheckBox lock;
    }

    private void openEdit(Entry e) {
        final Entry entry = (e == null) ? new Entry() : e;
        final boolean isNew = (e == null);
        final FormHolder holder = buildForm(entry, isNew);

        currentDialog = new AlertDialog.Builder(this)
                .setTitle(isNew ? ("新增 · " + tabName(currentTab)) : ("编辑：" + nz(entry.name)))
                .setView(holder.scroll)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = text(holder, "name");
                        if (name.isEmpty()) { toast("名称不能为空"); return; }
                        entry.id = nz(entry.id).isEmpty() ? UUID.randomUUID().toString() : entry.id;
                        entry.section = currentTab;
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
                        entry.phoneType = holder.paid.isChecked() ? "paid" : "free";
                        entry.iconLocked = holder.lock.isChecked();
                        String iconField = text(holder, "icon");
                        if (!entry.iconLocked && !iconField.isEmpty()) entry.icon = iconField;
                        entry.updatedAt = System.currentTimeMillis();
                        store.upsert(entry);
                        refresh();
                        toast("已保存");

                        String autoUrl = (S_PHONE.equals(currentTab) || S_API.equals(currentTab)) ? entry.url : entry.baseUrl;
                        if (nz(entry.icon).isEmpty() && !entry.iconLocked && !autoUrl.isEmpty()) grabIcon(entry);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        currentDialog.show();
    }

    private String text(FormHolder h, String key) {
        EditText et = h.fields.get(key);
        return et == null ? "" : et.getText().toString().trim();
    }

    private FormHolder buildForm(Entry e, boolean isNew) {
        ScrollView sv = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(12), dp(16), dp(12));
        Map<String, EditText> fields = new HashMap<String, EditText>();

        addField(form, fields, "name", "名称", e.name, false);

        if (S_PHONE.equals(currentTab)) {
            addField(form, fields, "url", "链接", e.url, false);
            addField(form, fields, "entry", "进入方式", e.entry, false);
            addField(form, fields, "pwd", "密码", e.pwd, false);
            addField(form, fields, "email", "邮箱", e.email, false);
        } else if (S_API.equals(currentTab)) {
            addField(form, fields, "baseUrl", "地址链接", e.baseUrl, false);
            addField(form, fields, "email", "登录邮箱", e.email, false);
            addField(form, fields, "pwd", "登录密码", e.pwd, false);
            addField(form, fields, "key", "Key", e.key, false);
        } else if (S_BEAUTY.equals(currentTab)) {
            addField(form, fields, "url", "图片 / 字体 URL（可选）", e.url, false);
            addField(form, fields, "content", "内容（CSS / HTML / 文本）", e.content, true);
        } else if (S_FONT.equals(currentTab)) {
            addField(form, fields, "url", "字体 URL（字体页可用）", e.url, false);
            addField(form, fields, "content", "内容（base64 / 文本）", e.content, true);
        } else {
            addField(form, fields, "content", "文件内容", e.content, true);
        }

        addField(form, fields, "author", "作者分类", e.author, false);
        addField(form, fields, "func", "功能分类", e.func, false);

        if (S_PHONE.equals(currentTab) || S_API.equals(currentTab)) {
            addField(form, fields, "icon", "图标（留空自动抓取原站图标）", nz(e.icon).startsWith("http") ? e.icon : "", false);
        }

        CheckBox paid = new CheckBox(this);
        paid.setText("💎 付费小手机（不勾选＝免费）");
        paid.setChecked("paid".equals(nz(e.phoneType)));
        if (S_PHONE.equals(currentTab)) form.addView(paid);

        CheckBox lock = new CheckBox(this);
        lock.setText("🔒 锁定图标（锁定后不被覆盖）");
        lock.setChecked(e.iconLocked);
        form.addView(lock);

        Button iconBtn = new Button(this);
        iconBtn.setText("🖼️ 用本地图片更换图标");
        iconBtn.setAllCaps(false);
        iconBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pendingIconId = e.id; pickIcon(); }
        });
        form.addView(iconBtn);

        sv.addView(form);
        return new FormHolder(sv, fields, paid, lock);
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

    // ---------------- 图标 ----------------

    private void pickIcon() {
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setType("image/*");
        try { startActivityForResult(i, REQ_ICON); } catch (Throwable t) { toast("没有可用的图片选择器"); }
    }

    private void grabIcon(final Entry e) {
        String target = (S_PHONE.equals(e.section) || S_API.equals(e.section)) ? nz(e.url) : nz(e.baseUrl);
        if (target.isEmpty() && S_API.equals(e.section)) target = nz(e.baseUrl);
        if (target.isEmpty()) { toast("这条没有链接，抓不到图标"); return; }
        final String page = target;
        toast("正在抓取原站图标…");
        new Thread(new Runnable() {
            public void run() {
                String data = Web.iconDataUrl(page);
                final String result = data;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (result == null) { toast("没抓到图标"); return; }
                        e.icon = result;
                        store.upsert(e);
                        refresh();
                        toast("图标已更新");
                    }
                });
            }
        }).start();
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

    private void openEntry(final Entry e) {
        String url = nz(e.url);
        if (url.isEmpty()) url = nz(e.baseUrl);
        if (url.isEmpty()) { toast("这条没有链接"); return; }
        final String target = url;
        String[] items = {"🧭 默认浏览器", "♾️ 永恒浏览器", "🐿️ 夸克浏览器", "🌐 选择其他浏览器"};
        currentDialog = new AlertDialog.Builder(this)
                .setTitle("选择打开方式")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { launch(target, which); }
                })
                .create();
        currentDialog.show();
    }

    private void launch(String url, int which) {
        try {
            if (which == 0) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); return; }
            if (which == 1) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(eternal(url)))); return; }
            if (which == 2) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(quark(url)))); return; }
            chooseBrowser(url);
        } catch (Throwable t) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Throwable t2) { toast("没有可用的浏览器"); }
        }
    }

    private String cleanUrl(String url) {
        String s = url.trim();
        if (s.startsWith("https://")) return s.substring(8);
        if (s.startsWith("http://")) return s.substring(7);
        return s;
    }

    private String eternal(String url) {
        try {
            URL u = new URL(url);
            String path = u.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (u.getQuery() != null) path = path + "?" + u.getQuery();
            String rest = u.getHost() + path;
            return "intent://" + rest + "#Intent;scheme=" + u.getProtocol()
                    + ";package=com.sevensix.everlasting;S.browser_fallback_url="
                    + Uri.encode(url) + ";end";
        } catch (Throwable t) { return url; }
    }

    private String quark(String url) {
        return "quark://" + cleanUrl(url);
    }

    private void chooseBrowser(final String url) {
        final Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(view, 0);
        if (list == null || list.isEmpty()) { toast("没有可用的浏览器"); return; }
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < list.size(); i++) names.add(list.get(i).loadLabel(getPackageManager()).toString());
        currentDialog = new AlertDialog.Builder(this)
                .setTitle("用哪个浏览器打开？")
                .setItems(names.toArray(new String[names.size()]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ResolveInfo r = list.get(w);
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.setPackage(r.activityInfo.packageName);
                        try { startActivity(i); } catch (Throwable t) { toast("打不开这个浏览器"); }
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
        else if (low.endsWith(".html") || low.endsWith(".htm")) { text = Docx.parseText(data); kind = "html"; }
        else if (low.endsWith(".css")) { text = Docx.parseText(data); kind = "css"; }
        else if (low.endsWith(".ttf") || low.endsWith(".otf") || low.endsWith(".woff") || low.endsWith(".woff2")) {
            text = Base64.encodeToString(data, Base64.NO_WRAP); kind = "font";
        } else { text = Docx.parseText(data); kind = low.endsWith(".txt") ? "txt" : "file"; }

        Entry e = new Entry();
        e.id = UUID.randomUUID().toString();
        e.section = currentTab;
        e.name = name.replaceAll("\\.[^.]+$", "");
        e.content = text;
        e.kind = kind;
        e.updatedAt = System.currentTimeMillis();
        store.upsert(e);
        refresh();
        toast("已导入：" + e.name);
    }

    // ---------------- 备份 ----------------

    private void renderBackupPanel() {
        backupPanel.removeAllViews();

        TextView info = new TextView(this);
        info.setText(memoryInfo());
        info.setTextSize(14);
        info.setTextColor(C_MAIN);
        info.setPadding(0, 0, 0, dp(14));
        backupPanel.addView(info);

        Button exp = new Button(this);
        exp.setText("📤 导出完整备份文件");
        exp.setAllCaps(false);
        exp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportBackup(); }
        });
        backupPanel.addView(exp);

        Button imp = new Button(this);
        imp.setText("📥 导入备份文件（JSON）");
        imp.setAllCaps(false);
        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { importBackup(); }
        });
        backupPanel.addView(imp);

        Button arc = new Button(this);
        arc.setText("📱 存入小手机备份");
        arc.setAllCaps(false);
        arc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { archivePhones(); }
        });
        backupPanel.addView(arc);

        TextView title = new TextView(this);
        title.setText("📱 小手机备份存档（" + store.phoneBackups.size() + "）");
        title.setTextSize(13);
        title.setTextColor(C_TXT2);
        title.setPadding(0, dp(16), 0, dp(6));
        backupPanel.addView(title);

        for (int i = 0; i < store.phoneBackups.size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView tv = new TextView(this);
            tv.setText(store.phoneBackups.get(i).name);
            tv.setTextSize(12);
            tv.setTextColor(C_MAIN);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            row.addView(tv);

            Button restore = new Button(this);
            restore.setText("恢复");
            restore.setAllCaps(false);
            restore.setTextSize(11);
            restore.setPadding(dp(6), 0, dp(6), 0);
            restore.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { restoreBackup(index); }
            });
            row.addView(restore);

            Button del = new Button(this);
            del.setText("删除");
            del.setAllCaps(false);
            del.setTextSize(11);
            del.setPadding(dp(6), 0, dp(6), 0);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { deleteBackup(index); }
            });
            row.addView(del);

            backupPanel.addView(row);
        }
    }

    private String memoryInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(store.entries.size()).append(" 条数据");
        sb.append("（小手机 ").append(store.bySection(S_PHONE).size());
        sb.append(" / API ").append(store.bySection(S_API).size());
        sb.append(" / 世界书 ").append(store.bySection(S_WORLD).size());
        sb.append(" / 人设 ").append(store.bySection(S_CHAR).size());
        sb.append(" / 美化 ").append(store.bySection(S_BEAUTY).size());
        sb.append(" / 字体 ").append(store.bySection(S_FONT).size()).append("）\n");
        sb.append("数据占用：").append(store.dataSize()).append(" KB\n");
        sb.append("备份存档：").append(store.phoneBackups.size()).append(" 份\n\n");
        sb.append("备份为 JSON 文件，导入时同 id 覆盖、新条目追加。\n外部浏览器里的聊天记录 App 读不到，也带不走。");
        return sb.toString();
    }

    private void archivePhones() {
        store.archivePhones();
        renderBackupPanel();
        toast("已存入小手机备份");
    }

    private void restoreBackup(final int index) {
        currentDialog = new AlertDialog.Builder(this)
                .setMessage("把「" + store.phoneBackups.get(index).name + "」恢复成当前小手机？\n（当前小手机将被覆盖）")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int n = store.restoreBackup(index);
                        refresh();
                        toast("已恢复 " + n + " 条小手机");
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        currentDialog.show();
    }

    private void deleteBackup(final int index) {
        store.phoneBackups.remove(index);
        store.save();
        renderBackupPanel();
        toast("已删除存档");
    }

    private void exportBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "HappyHome备份.json");
        try { startActivityForResult(i, REQ_EXPORT); } catch (Throwable t) { toast("当前系统不支持导出文件"); }
    }

    private void writeBackup(Uri uri) throws Exception {
        String json = store.exportAll();
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
        int n = store.importAll(text);
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
        if (batchMode) { batchMode = false; selectedIds.clear(); refresh(); return; }
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
        String id, section, name, url, entry, pwd, email, baseUrl, key, author, func, content, kind, icon, phoneType;
        boolean iconLocked;
        long updatedAt;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", nz(id));
            o.put("section", nz(section));
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
            o.put("phoneType", nz(phoneType).isEmpty() ? "free" : phoneType);
            o.put("iconLocked", iconLocked);
            o.put("updatedAt", updatedAt);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optString("id");
            e.section = o.optString("section");
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
            e.phoneType = o.optString("phoneType", "free");
            e.iconLocked = o.optBoolean("iconLocked");
            e.updatedAt = o.optLong("updatedAt");
            return e;
        }

        static String nz(String s) { return s == null ? "" : s; }
    }

    static class PhoneBackup {
        String name = "";
        long time = 0;
        String json = "";
    }

    // =====================================================================
    // 本地存储
    // =====================================================================
    static class Store {
        private final Context ctx;
        final List<Entry> entries = new ArrayList<Entry>();
        final List<PhoneBackup> phoneBackups = new ArrayList<PhoneBackup>();
        private final Map<String, List<String>> authors = new HashMap<String, List<String>>();
        private final Map<String, List<String>> funcs = new HashMap<String, List<String>>();

        Store(Context ctx) { this.ctx = ctx; load(); }

        private File file() { return new File(ctx.getFilesDir(), "hh_data.json"); }

        long dataSize() {
            try { return file().length() / 1024; } catch (Throwable t) { return 0; }
        }

        List<String> catAuthors(String sec) {
            List<String> l = authors.get(sec);
            if (l == null) { l = new ArrayList<String>(); authors.put(sec, l); }
            return l;
        }

        List<String> catFuncs(String sec) {
            List<String> l = funcs.get(sec);
            if (l == null) { l = new ArrayList<String>(); funcs.put(sec, l); }
            return l;
        }

        void addCat(String sec, boolean author, String name) {
            List<String> l = author ? catAuthors(sec) : catFuncs(sec);
            if (!l.contains(name)) l.add(name);
            save();
        }

        void removeCat(String sec, boolean author, String name) {
            List<String> l = author ? catAuthors(sec) : catFuncs(sec);
            for (int i = l.size() - 1; i >= 0; i--) if (name.equals(l.get(i))) l.remove(i);
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (!sec.equals(e.section)) continue;
                if (author && name.equals(nz(e.author))) e.author = "";
                if (!author && name.equals(nz(e.func))) e.func = "";
            }
            save();
        }

        synchronized void load() {
            entries.clear();
            phoneBackups.clear();
            authors.clear();
            funcs.clear();
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
                JSONObject cats = root.optJSONObject("cats");
                if (cats != null) {
                    JSONArray names = cats.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String sec = names.getString(i);
                            JSONObject c = cats.optJSONObject(sec);
                            if (c == null) continue;
                            List<String> a = catAuthors(sec);
                            List<String> f2 = catFuncs(sec);
                            JSONArray ja = c.optJSONArray("authors");
                            if (ja != null) for (int k = 0; k < ja.length(); k++) a.add(ja.optString(k));
                            JSONArray jf = c.optJSONArray("funcs");
                            if (jf != null) for (int k = 0; k < jf.length(); k++) f2.add(jf.optString(k));
                        }
                    }
                }
                JSONArray pb = root.optJSONArray("phoneBackups");
                if (pb != null) {
                    for (int i = 0; i < pb.length(); i++) {
                        JSONObject o = pb.getJSONObject(i);
                        PhoneBackup p = new PhoneBackup();
                        p.name = o.optString("name");
                        p.time = o.optLong("time");
                        p.json = o.optString("json");
                        phoneBackups.add(p);
                    }
                }
            } catch (Throwable t) { t.printStackTrace(); }
        }

        private JSONObject buildRoot() throws Exception {
            JSONObject root = new JSONObject();
            root.put("format", "HappyHomeNative");
            root.put("version", 2);

            JSONArray arr = new JSONArray();
            for (int i = 0; i < entries.size(); i++) arr.put(entries.get(i).toJson());
            root.put("entries", arr);

            JSONObject cats = new JSONObject();
            List<String> secs = new ArrayList<String>();
            for (int i = 0; i < entries.size(); i++) {
                String s = nz(entries.get(i).section);
                if (!s.isEmpty() && !secs.contains(s)) secs.add(s);
            }
            String[] all = {S_PHONE, S_API, S_WORLD, S_CHAR, S_BEAUTY, S_FONT};
            for (int i = 0; i < all.length; i++) if (!secs.contains(all[i])) secs.add(all[i]);
            for (int i = 0; i < secs.size(); i++) {
                JSONObject c = new JSONObject();
                JSONArray a = new JSONArray();
                List<String> al = catAuthors(secs.get(i));
                for (int k = 0; k < al.size(); k++) a.put(al.get(k));
                JSONArray f = new JSONArray();
                List<String> fl = catFuncs(secs.get(i));
                for (int k = 0; k < fl.size(); k++) f.put(fl.get(k));
                c.put("authors", a);
                c.put("funcs", f);
                cats.put(secs.get(i), c);
            }
            root.put("cats", cats);

            JSONArray pb = new JSONArray();
            for (int i = 0; i < phoneBackups.size(); i++) {
                JSONObject o = new JSONObject();
                PhoneBackup p = phoneBackups.get(i);
                o.put("name", p.name);
                o.put("time", p.time);
                o.put("json", p.json);
                pb.put(o);
            }
            root.put("phoneBackups", pb);
            return root;
        }

        synchronized void save() {
            try {
                FileOutputStream out = new FileOutputStream(file());
                out.write(buildRoot().toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.close();
            } catch (Throwable t) { t.printStackTrace(); }
        }

        List<Entry> bySection(String sec) {
            List<Entry> out = new ArrayList<Entry>();
            for (int i = 0; i < entries.size(); i++) if (sec.equals(entries.get(i).section)) out.add(entries.get(i));
            return out;
        }

        Entry find(String id) {
            if (id == null) return null;
            for (int i = 0; i < entries.size(); i++) if (id.equals(entries.get(i).id)) return entries.get(i);
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

        String exportAll() throws Exception {
            return buildRoot().toString();
        }

        int importAll(String text) throws Exception {
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
            JSONObject cats = root.optJSONObject("cats");
            if (cats != null) {
                JSONArray names = cats.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String sec = names.getString(i);
                        JSONObject c = cats.optJSONObject(sec);
                        if (c == null) continue;
                        JSONArray ja = c.optJSONArray("authors");
                        if (ja != null) for (int k = 0; k < ja.length(); k++) {
                            String v = ja.optString(k);
                            if (!catAuthors(sec).contains(v)) catAuthors(sec).add(v);
                        }
                        JSONArray jf = c.optJSONArray("funcs");
                        if (jf != null) for (int k = 0; k < jf.length(); k++) {
                            String v = jf.optString(k);
                            if (!catFuncs(sec).contains(v)) catFuncs(sec).add(v);
                        }
                    }
                }
            }
            save();
            return n;
        }

        void archivePhones() {
            try {
                JSONArray arr = new JSONArray();
                for (int i = 0; i < entries.size(); i++) {
                    if (S_PHONE.equals(entries.get(i).section)) arr.put(entries.get(i).toJson());
                }
                PhoneBackup p = new PhoneBackup();
                p.name = "小手机存档 " + fmtTime(System.currentTimeMillis());
                p.time = System.currentTimeMillis();
                p.json = arr.toString();
                phoneBackups.add(0, p);
                save();
            } catch (Throwable t) { t.printStackTrace(); }
        }

        int restoreBackup(int index) {
            if (index < 0 || index >= phoneBackups.size()) return 0;
            try {
                PhoneBackup p = phoneBackups.get(index);
                JSONArray arr = new JSONArray(nz(p.json));
                for (int i = entries.size() - 1; i >= 0; i--) {
                    if (S_PHONE.equals(entries.get(i).section)) entries.remove(i);
                }
                int n = 0;
                for (int i = 0; i < arr.length(); i++) {
                    Entry e = Entry.fromJson(arr.getJSONObject(i));
                    e.section = S_PHONE;
                    entries.add(e);
                    n++;
                }
                save();
                return n;
            } catch (Throwable t) { t.printStackTrace(); return 0; }
        }

        private String fmtTime(long t) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);
                return f.format(new java.util.Date(t));
            } catch (Throwable e) { return String.valueOf(t); }
        }

        static String nz(String s) { return s == null ? "" : s; }
    }

    // =====================================================================
    // 原站图标抓取
    // =====================================================================
    static class Web {

        static String iconDataUrl(String pageUrl) {
            try {
                String html = httpText(pageUrl);
                List<String> cands = new ArrayList<String>();
                if (html != null) {
                    Matcher m = Pattern.compile("<link[^>]+>", Pattern.CASE_INSENSITIVE).matcher(html);
                    while (m.find()) {
                        String tag = m.group();
                        if (!tag.toLowerCase(Locale.ROOT).contains("icon")) continue;
                        Matcher h = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag);
                        if (h.find()) cands.add(absolute(pageUrl, h.group(1)));
                    }
                }
                cands.add(origin(pageUrl) + "/apple-touch-icon.png");
                cands.add(origin(pageUrl) + "/favicon.ico");

                for (int i = 0; i < cands.size() && i < 8; i++) {
                    String data = imageDataUrl(cands.get(i));
                    if (data != null) return data;
                }
            } catch (Throwable t) { t.printStackTrace(); }
            return null;
        }

        static String imageDataUrl(String imgUrl) {
            try {
                byte[] bytes = httpBytes(imgUrl);
                if (bytes == null || bytes.length < 64) return null;
                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);
                if (opt.outWidth <= 0 || opt.outHeight <= 0) return null;
                Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (raw == null) return null;
                int max = 256;
                int w = raw.getWidth(), h = raw.getHeight();
                if (w > max || h > max) {
                    float ratio = Math.min((float) max / w, (float) max / h);
                    raw = Bitmap.createScaledBitmap(raw,
                            Math.max(1, Math.round(w * ratio)), Math.max(1, Math.round(h * ratio)), true);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                raw.compress(Bitmap.CompressFormat.PNG, 80, out);
                return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Throwable t) { return null; }
        }

        static String httpText(String url) {
            try {
                byte[] b = httpBytes(url);
                if (b == null) return null;
                return new String(b, StandardCharsets.UTF_8);
            } catch (Throwable t) { return null; }
        }

        static byte[] httpBytes(String url) {
            try {
                URL u = new URL(url);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) HappyHome/2.0");
                int code = c.getResponseCode();
                if (code < 200 || code >= 400) { c.disconnect(); return null; }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r, total = 0;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    total += r;
                    if (total > 400000) break;
                }
                in.close();
                c.disconnect();
                return out.toByteArray();
            } catch (Throwable t) { return null; }
        }

        static String origin(String url) {
            try {
                URL u = new URL(url);
                int port = u.getPort();
                return u.getProtocol() + "://" + u.getHost()
                        + (port > 0 && port != 80 && port != 443 ? ":" + port : "");
            } catch (Throwable t) { return url; }
        }

        static String absolute(String base, String href) {
            try {
                if (href.startsWith("http://") || href.startsWith("https://")) return href;
                if (href.startsWith("//")) {
                    URL b = new URL(base);
                    return b.getProtocol() + ":" + href;
                }
                if (href.startsWith("/")) return origin(base) + href;
                String path = new URL(base).getPath();
                int slash = path.lastIndexOf("/");
                return origin(base) + (slash >= 0 ? path.substring(0, slash + 1) : "/") + href;
            } catch (Throwable t) { return base; }
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
            Matcher m = Pattern.compile("<w:t[^>]*>(.*?)</w:t>", Pattern.DOTALL).matcher(x);
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
            String[] list = {"GB18030", "GBK", "BIG5"};
            for (int i = 0; i < list.length; i++) {
                try {
                    String t = new String(data, Charset.forName(list[i]));
                    if (!t.contains("\uFFFD")) return t;
                } catch (Throwable ignored) {}
            }
            return utf8;
        }
    }
}
