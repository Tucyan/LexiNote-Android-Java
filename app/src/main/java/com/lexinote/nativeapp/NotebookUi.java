package com.lexinote.nativeapp;

import android.app.*;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

final class NotebookUi {
  final MainActivity a;
  String selected = null, filter = "", typeFilter = "全部", tagFilter = "全部";
  String statusFilter = "全部"; // 全部, 待复习, 已学习
  String featureFilter = "全部"; // 全部, 有批注, 多释义
  String sortOrder = "最新"; // 最新, 最早, A-Z, Z-A
  String viewMode = "compact"; // compact, detailed
  boolean selecting = false;
  final LinkedHashSet<String> selectedVocabularyIds = new LinkedHashSet<>();

  NotebookUi(MainActivity a) {
    this.a = a;
  }

  int activeFilterCount() {
    int count = 0;
    if (!"全部".equals(typeFilter)) count++;
    if (!"全部".equals(tagFilter)) count++;
    if (!"全部".equals(statusFilter)) count++;
    if (!"全部".equals(featureFilter)) count++;
    if (!"最新".equals(sortOrder)) count++;
    return count;
  }

  void resetFilters() {
    typeFilter = "全部";
    tagFilter = "全部";
    statusFilter = "全部";
    featureFilter = "全部";
    sortOrder = "最新";
  }

  boolean hasAnnotations(JSONObject v) {
    if (v == null) return false;
    JSONArray ms = v.optJSONArray("meanings");
    for (int i = 0; ms != null && i < ms.length(); i++) {
      JSONObject m = ms.optJSONObject(i);
      if (m != null) {
        JSONArray notes = m.optJSONArray("annotations");
        if (notes != null && notes.length() > 0) return true;
      }
    }
    return false;
  }

  boolean isMastered(JSONObject v) {
    if (v == null) return false;
    if (contains(v.optJSONArray("tags"), "已学习")) return true;
    Integer level = v.has("review_mastery_level") && !v.isNull("review_mastery_level")
        ? v.optInt("review_mastery_level") : null;
    return ReviewLogic.mastered(level, v.optInt("review_count"), v.optString("review_grade", ""));
  }

  boolean isDue(JSONObject v) {
    if (v == null) return false;
    if (contains(v.optJSONArray("tags"), "待复习")) return true;
    if (isMastered(v)) return false;
    return v.optLong("review_due", 0) <= System.currentTimeMillis();
  }

  List<String> findReferencingNotebooks(String vocabId) {
    List<String> names = new ArrayList<>();
    JSONArray ns = a.library.array("notebooks");
    for (int i = 0; i < ns.length(); i++) {
      JSONObject n = ns.optJSONObject(i);
      if (n == null || Library.systemGlobal(n)) continue;
      if (contains(n.optJSONArray("vocabulary_ids"), vocabId)) {
        names.add(n.optString("name"));
      }
    }
    return names;
  }

  int countMatches(String type, String status, String feature, String tag) {
    JSONObject n = (selected == null || "nb_global_root".equals(selected))
        ? null : a.library.find("notebooks", selected);
    JSONArray refs = n == null ? null : n.optJSONArray("vocabulary_ids");
    JSONArray vs = a.library.array("vocabularies");
    int count = 0;
    for (int i = 0; i < vs.length(); i++) {
      JSONObject v = vs.optJSONObject(i);
      if (v == null) continue;
      if (refs != null && !contains(refs, v.optString("id"))) continue;
      if (!type.equals("全部")
          && !v.optString("type", "word").equals(type.equals("短语") ? "phrase" : "word"))
        continue;
      if (!tag.equals("全部") && !contains(v.optJSONArray("tags"), tag)) continue;
      if (!status.equals("全部")) {
        if ("待复习".equals(status) && !isDue(v)) continue;
        if ("已学习".equals(status) && !isMastered(v)) continue;
      }
      if (!feature.equals("全部")) {
        if ("有批注".equals(feature) && !hasAnnotations(v)) continue;
        if ("多释义".equals(feature)) {
          JSONArray ms = v.optJSONArray("meanings");
          if (ms == null || ms.length() <= 1) continue;
        }
      }
      if (!NotebookSearch.matches(v, filter)) continue;
      count++;
    }
    return count;
  }

  List<JSONObject> visible() {
    List<JSONObject> out = new ArrayList<>();
    JSONObject n = (selected == null || "nb_global_root".equals(selected))
        ? null : a.library.find("notebooks", selected);
    JSONArray refs = n == null ? null : n.optJSONArray("vocabulary_ids");
    JSONArray vs = a.library.array("vocabularies");
    for (int i = 0; i < vs.length(); i++) {
      JSONObject v = vs.optJSONObject(i);
      if (refs != null && !contains(refs, v.optString("id"))) continue;
      if (!typeFilter.equals("全部")
          && !v.optString("type", "word").equals(typeFilter.equals("短语") ? "phrase" : "word"))
        continue;
      if (!tagFilter.equals("全部") && !contains(v.optJSONArray("tags"), tagFilter)) continue;
      if (!statusFilter.equals("全部")) {
        if ("待复习".equals(statusFilter) && !isDue(v)) continue;
        if ("已学习".equals(statusFilter) && !isMastered(v)) continue;
      }
      if (!featureFilter.equals("全部")) {
        if ("有批注".equals(featureFilter) && !hasAnnotations(v)) continue;
        if ("多释义".equals(featureFilter)) {
          JSONArray ms = v.optJSONArray("meanings");
          if (ms == null || ms.length() <= 1) continue;
        }
      }
      if (!NotebookSearch.matches(v, filter)) continue;
      out.add(v);
    }
    if ("最新".equals(sortOrder)) {
      Collections.sort(out, (v1, v2) -> {
        String t1 = v1.optString("created_at", v1.optString("updated_at", ""));
        String t2 = v2.optString("created_at", v2.optString("updated_at", ""));
        return t2.compareTo(t1);
      });
    } else if ("最早".equals(sortOrder)) {
      Collections.sort(out, (v1, v2) -> {
        String t1 = v1.optString("created_at", v1.optString("updated_at", ""));
        String t2 = v2.optString("created_at", v2.optString("updated_at", ""));
        return t1.compareTo(t2);
      });
    } else if ("A-Z".equals(sortOrder)) {
      Collections.sort(out, (v1, v2) ->
        v1.optString("content", "").compareToIgnoreCase(v2.optString("content", "")));
    } else if ("Z-A".equals(sortOrder)) {
      Collections.sort(out, (v1, v2) ->
        v2.optString("content", "").compareToIgnoreCase(v1.optString("content", "")));
    }
    return out;
  }

  static boolean contains(JSONArray a, String id) {
    if (a == null) return false;
    for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return true;
    return false;
  }

  void show() {
    boolean showBookshelf = (selected == null && filter.trim().isEmpty());
    if (showBookshelf) {
      renderBookshelf();
    } else {
      renderNotebookVocabList();
    }
  }

  void renderBookshelf() {
    a.body.removeAllViews();
    a.body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(32));

    // Search & Export Bar
    LinearLayout tools = a.row();
    tools.setPadding(0, a.dp(4), 0, a.dp(10));
    EditText f = a.input("搜索词汇、含义或例句…", filter, false);
    f.setTextSize(13 * a.scale);
    f.setMinHeight(0);
    f.setPadding(a.dp(10), 0, a.dp(8), 0);
    f.setCompoundDrawables(a.new GlyphDrawable("search-outline", 16, a.subtle), null, null, null);
    f.setCompoundDrawablePadding(a.dp(6));
    tools.addView(f, new LinearLayout.LayoutParams(0, a.dp(38), 1));
    Button export = a.chip("导出", true, v -> exportMenu());
    export.setCompoundDrawables(
        a.new GlyphDrawable("share-outline", 16, a.accent), null, null, null);
    export.setCompoundDrawablePadding(a.dp(4));
    LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(-2, a.dp(38));
    exportLp.leftMargin = a.dp(8);
    tools.addView(export, exportLp);
    a.body.addView(tools);

    f.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        filter = s.toString();
        a.render();
      }
      public void afterTextChanged(android.text.Editable s) {}
    });
    f.setOnEditorActionListener((v, action, event) -> {
      filter = f.getText().toString();
      a.render();
      return true;
    });

    // Bookshelf Welcome Header
    LinearLayout shelfHeader = a.row();
    shelfHeader.setGravity(Gravity.CENTER_VERTICAL);
    shelfHeader.setPadding(0, a.dp(6), 0, a.dp(14));

    LinearLayout shelfTitleCol = a.column();
    TextView shelfTitle = a.text("电子书架", 18, true);
    shelfTitleCol.addView(shelfTitle);

    int totalVocab = a.library.array("vocabularies").length();
    int totalNotes = a.library.array("notebooks").length();
    TextView shelfSubtitle = a.text("共 " + totalNotes + " 本笔记本 · " + totalVocab + " 条词汇", 12, false);
    shelfSubtitle.setTextColor(a.muted);
    shelfTitleCol.addView(shelfSubtitle);
    shelfHeader.addView(shelfTitleCol, new LinearLayout.LayoutParams(0, -2, 1));

    Button newNbBtn = a.chip("＋ 新建分类", true, v -> create());
    newNbBtn.setTextSize(12 * a.scale);
    shelfHeader.addView(newNbBtn);
    a.body.addView(shelfHeader);

    // Book Cards Grid (2 columns)
    List<View> bookCards = new ArrayList<>();
    bookCards.add(createGlobalBookCard(totalVocab));

    JSONObject defaultNb = a.library.find("notebooks", "nb_default");
    if (defaultNb != null) {
      bookCards.add(createNotebookBookCard(defaultNb, "#10B981"));
    }

    JSONArray allNb = a.library.array("notebooks");
    String[] spineColors = new String[] {
      "#6366F1", "#8B5CF6", "#EC4899", "#F59E0B", "#06B6D4", "#3B82F6", "#14B8A6"
    };
    int colorIdx = 0;
    for (int i = 0; i < allNb.length(); i++) {
      JSONObject n = allNb.optJSONObject(i);
      if (n == null) continue;
      String id = n.optString("id");
      if ("nb_global_root".equals(id) || "nb_default".equals(id)) continue;
      String color = spineColors[colorIdx % spineColors.length];
      colorIdx++;
      bookCards.add(createNotebookBookCard(n, color));
    }
    bookCards.add(createAddBookCard());

    for (int i = 0; i < bookCards.size(); i += 2) {
      LinearLayout gridRow = a.row();
      gridRow.setPadding(0, 0, 0, a.dp(12));
      gridRow.addView(bookCards.get(i), new LinearLayout.LayoutParams(0, a.dp(130), 1));
      if (i + 1 < bookCards.size()) {
        View spacer = new View(a);
        gridRow.addView(spacer, new LinearLayout.LayoutParams(a.dp(12), 1));
        gridRow.addView(bookCards.get(i + 1), new LinearLayout.LayoutParams(0, a.dp(130), 1));
      } else {
        View placeholder = new View(a);
        gridRow.addView(placeholder, new LinearLayout.LayoutParams(0, 1, 1));
      }
      a.body.addView(gridRow);
    }
  }

  View createGlobalBookCard(int totalVocab) {
    LinearLayout card = a.column();
    card.setBackground(a.shape(a.card, 16, a.border));
    card.setPadding(0, 0, 0, 0);
    card.setClickable(true);
    card.setFocusable(true);
    card.setOnClickListener(v -> {
      selected = "nb_global_root";
      a.render();
    });
    card.setOnLongClickListener(v -> {
      new AlertDialog.Builder(a)
          .setTitle("全部词汇")
          .setMessage("全部词汇 · " + totalVocab + " 条词汇\n系统总词典，跨笔记本实体汇聚。")
          .setPositiveButton("确定", null)
          .show();
      return true;
    });

    LinearLayout inner = a.row();
    inner.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

    View spine = new View(a);
    spine.setBackground(a.shape(a.accent, 4, 0));
    inner.addView(spine, new LinearLayout.LayoutParams(a.dp(6), -1));

    LinearLayout content = a.column();
    content.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
    content.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));

    LinearLayout topRow = a.row();
    topRow.setGravity(Gravity.CENTER_VERTICAL);
    topRow.addView(a.icon("planet-outline", 18, a.accent));
    View spacer = new View(a);
    topRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

    TextView globalBadge = a.text("总词典", 10, true);
    globalBadge.setTextColor(a.accent);
    globalBadge.setPadding(a.dp(4), a.dp(1), a.dp(4), a.dp(1));
    globalBadge.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 6, 0));
    topRow.addView(globalBadge);
    content.addView(topRow);

    TextView titleTv = a.text("全部词汇", 15, true);
    titleTv.setTextColor(a.foreground);
    LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, 0, 1);
    tLp.topMargin = a.dp(6);
    content.addView(titleTv, tLp);

    TextView countTv = a.text("共 " + totalVocab + " 条词汇", 11, false);
    countTv.setTextColor(a.muted);
    content.addView(countTv);

    inner.addView(content);
    card.addView(inner);
    a.passiveLabels(card);
    return card;
  }

  View createNotebookBookCard(JSONObject n, String spineColor) {
    LinearLayout card = a.column();
    card.setBackground(a.shape(a.card, 16, a.border));
    card.setPadding(0, 0, 0, 0);
    card.setClickable(true);
    card.setFocusable(true);

    String id = n.optString("id");
    card.setOnClickListener(v -> {
      selected = id;
      a.render();
    });
    card.setOnLongClickListener(v -> {
      manage(n);
      return true;
    });

    LinearLayout inner = a.row();
    inner.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

    View spine = new View(a);
    spine.setBackground(a.shape(Color.parseColor(spineColor), 4, 0));
    inner.addView(spine, new LinearLayout.LayoutParams(a.dp(6), -1));

    LinearLayout content = a.column();
    content.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
    content.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));

    LinearLayout topRow = a.row();
    topRow.setGravity(Gravity.CENTER_VERTICAL);
    String iconName = n.optBoolean("is_default") ? "bookmark-outline" : "folder-outline";
    topRow.addView(a.icon(iconName, 18, Color.parseColor(spineColor)));

    View metaSpacer = new View(a);
    topRow.addView(metaSpacer, new LinearLayout.LayoutParams(0, 1, 1));

    JSONArray children = n.optJSONArray("child_notebook_ids");
    if (n.optBoolean("is_default")) {
      TextView defBadge = a.text("默认", 10, true);
      defBadge.setTextColor(Color.parseColor(spineColor));
      defBadge.setPadding(a.dp(4), a.dp(1), a.dp(4), a.dp(1));
      defBadge.setBackground(a.shape((Color.parseColor(spineColor) & 0x00FFFFFF) | 0x1A000000, 6, 0));
      topRow.addView(defBadge);
    } else if (children != null && children.length() > 0) {
      TextView childBadge = a.text(children.length() + " 个子分类", 10, false);
      childBadge.setTextColor(a.muted);
      childBadge.setPadding(a.dp(4), a.dp(1), a.dp(4), a.dp(1));
      childBadge.setBackground(a.shape(a.inputBg, 6, a.border));
      topRow.addView(childBadge);
    }
    content.addView(topRow);

    TextView titleTv = a.text(n.optString("name"), 15, true);
    titleTv.setTextColor(a.foreground);
    titleTv.setMaxLines(2);
    titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, 0, 1);
    tLp.topMargin = a.dp(6);
    content.addView(titleTv, tLp);

    JSONArray vocabIds = n.optJSONArray("vocabulary_ids");
    int count = vocabIds == null ? 0 : vocabIds.length();
    TextView countTv = a.text(count + " 条词汇", 11, false);
    countTv.setTextColor(a.muted);
    content.addView(countTv);

    inner.addView(content);
    card.addView(inner);
    a.passiveLabels(card);
    return card;
  }

  View createAddBookCard() {
    LinearLayout card = a.column();
    card.setBackground(a.shape(a.card, 16, a.border));
    card.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
    card.setGravity(Gravity.CENTER);
    card.setClickable(true);
    card.setFocusable(true);
    card.setOnClickListener(v -> create());

    LinearLayout col = a.column();
    col.setGravity(Gravity.CENTER);

    TextView icon = a.icon("add-circle-outline", 28, a.accent);
    col.addView(icon);

    TextView label = a.text("新建分类", 14, true);
    label.setTextColor(a.accent);
    LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(-2, -2);
    lLp.topMargin = a.dp(6);
    col.addView(label, lLp);

    TextView sub = a.text("添加笔记本或子树", 11, false);
    sub.setTextColor(a.muted);
    LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-2, -2);
    sLp.topMargin = a.dp(2);
    col.addView(sub, sLp);

    card.addView(col);
    a.passiveLabels(card);
    return card;
  }

  void renderNotebookVocabList() {
    LinearLayout controls = a.column();
    controls.setBackgroundColor(a.card);
    a.root.addView(controls, a.root.indexOfChild(a.scroll));
    a.body.setPadding(a.dp(14), 0, a.dp(14), a.dp(32));

    // 1. Navigation Top Bar
    LinearLayout navBar = a.row();
    navBar.setGravity(Gravity.CENTER_VERTICAL);
    navBar.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));

    Button backBtn = a.chip("‹ 切换笔记", true, v -> {
      selected = null;
      filter = "";
      a.render();
    });
    navBar.addView(backBtn);

    JSONObject selectedNote = (selected == null || "nb_global_root".equals(selected))
        ? null : a.library.find("notebooks", selected);

    String displayName;
    String iconName;
    if (selected == null || "nb_global_root".equals(selected)) {
      displayName = "全部词汇";
      iconName = "planet-outline";
    } else {
      displayName = path(selected);
      iconName = "folder-outline";
    }

    TextView titleView = a.text(" " + displayName, 16, true);
    titleView.setSingleLine(true);
    titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    navBar.addView(a.icon(iconName, 18, a.accent));
    navBar.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));

    Button manageBtn = a.chip(selectedNote == null ? "信息" : "管理", false, v -> {
      if (selectedNote != null) manage(selectedNote);
      else {
        new AlertDialog.Builder(a)
            .setTitle("分类信息")
            .setMessage("全部词汇 · " + visible().size() + " 条词汇\n词汇实体可被多个笔记共享引用。")
            .setNeutralButton("管理", (dialog, which) -> manage(null))
            .setPositiveButton("确定", null)
            .show();
      }
    });
    navBar.addView(manageBtn);

    Button batchBtn = a.chip(selecting ? "退出多选" : "批量管理", selecting, v -> {
      selecting = !selecting;
      selectedVocabularyIds.clear();
      a.render();
    });
    navBar.addView(batchBtn);
    controls.addView(navBar);

    // 2. Search & Export Bar
    LinearLayout tools = a.row();
    tools.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(6));
    EditText f = a.input("搜索词汇、含义或例句…", filter, false);
    f.setTextSize(13 * a.scale);
    f.setMinHeight(0);
    f.setPadding(a.dp(10), 0, a.dp(8), 0);
    f.setCompoundDrawables(a.new GlyphDrawable("search-outline", 16, a.subtle), null, null, null);
    f.setCompoundDrawablePadding(a.dp(6));
    tools.addView(f, new LinearLayout.LayoutParams(0, a.dp(36), 1));
    Button export = a.chip("导出", true, v -> exportMenu());
    export.setCompoundDrawables(
        a.new GlyphDrawable("share-outline", 16, a.accent), null, null, null);
    export.setCompoundDrawablePadding(a.dp(4));
    LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(-2, a.dp(36));
    exportLp.leftMargin = a.dp(8);
    tools.addView(export, exportLp);
    controls.addView(tools);

    f.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        filter = s.toString();
        renderResults(controls);
      }
      public void afterTextChanged(android.text.Editable s) {}
    });
    f.setOnEditorActionListener((v, action, event) -> {
      filter = f.getText().toString();
      renderResults(controls);
      return true;
    });

    // 3. Child Notebooks sub-navigation
    if (selectedNote != null) {
      JSONArray children = selectedNote.optJSONArray("child_notebook_ids");
      if (children != null && children.length() > 0) {
        LinearLayout childRow = a.row();
        childRow.setPadding(a.dp(14), a.dp(2), a.dp(14), a.dp(4));
        childRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView subLabel = a.text("子分类: ", 11, true);
        subLabel.setTextColor(a.muted);
        childRow.addView(subLabel);
        for (int i = 0; i < children.length(); i++) {
          JSONObject child = a.library.find("notebooks", children.optString(i));
          if (child == null) continue;
          int count = child.optJSONArray("vocabulary_ids") == null ? 0 : child.optJSONArray("vocabulary_ids").length();
          Button subChip = a.chip(child.optString("name") + " (" + count + ")", false, v -> {
            selected = child.optString("id");
            a.render();
          });
          subChip.setTextSize(11 * a.scale);
          subChip.setPadding(a.dp(8), 0, a.dp(8), 0);
          LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-2, a.dp(28));
          sLp.rightMargin = a.dp(4);
          childRow.addView(subChip, sLp);
        }
        HorizontalScrollView subScroll = new HorizontalScrollView(a);
        subScroll.setHorizontalScrollBarEnabled(false);
        subScroll.addView(childRow);
        controls.addView(subScroll);
      }
    }

    // 4. Advanced Filter Bar & View Mode Toggle
    LinearLayout filterBar = a.row();
    filterBar.setGravity(Gravity.CENTER_VERTICAL);
    filterBar.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(6));
    filterBar.setBackgroundColor(a.background);

    int activeCount = activeFilterCount();
    Button filterSheetBtn = a.chip("⚡ 筛选" + (activeCount > 0 ? " (" + activeCount + ")" : "") + " ▾",
        activeCount > 0, v -> showFilterSheet());
    filterSheetBtn.setTextSize(11 * a.scale);
    filterSheetBtn.setPadding(a.dp(8), 0, a.dp(8), 0);
    LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(-2, a.dp(30));
    fsLp.rightMargin = a.dp(6);
    filterBar.addView(filterSheetBtn, fsLp);

    HorizontalScrollView activeChipsScroll = new HorizontalScrollView(a);
    activeChipsScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout activeChips = a.row();
    activeChips.setGravity(Gravity.CENTER_VERTICAL);

    if (!"全部".equals(typeFilter)) {
      Button c = a.chip("形态: " + typeFilter + " ✕", true, v -> { typeFilter = "全部"; a.render(); });
      c.setTextSize(10 * a.scale);
      c.setPadding(a.dp(6), 0, a.dp(6), 0);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(26));
      lp.rightMargin = a.dp(4);
      activeChips.addView(c, lp);
    }
    if (!"全部".equals(tagFilter)) {
      Button c = a.chip("#" + tagFilter + " ✕", true, v -> { tagFilter = "全部"; a.render(); });
      c.setTextSize(10 * a.scale);
      c.setPadding(a.dp(6), 0, a.dp(6), 0);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(26));
      lp.rightMargin = a.dp(4);
      activeChips.addView(c, lp);
    }
    if (!"全部".equals(statusFilter)) {
      Button c = a.chip("状态: " + statusFilter + " ✕", true, v -> { statusFilter = "全部"; a.render(); });
      c.setTextSize(10 * a.scale);
      c.setPadding(a.dp(6), 0, a.dp(6), 0);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(26));
      lp.rightMargin = a.dp(4);
      activeChips.addView(c, lp);
    }
    if (!"全部".equals(featureFilter)) {
      Button c = a.chip("特征: " + featureFilter + " ✕", true, v -> { featureFilter = "全部"; a.render(); });
      c.setTextSize(10 * a.scale);
      c.setPadding(a.dp(6), 0, a.dp(6), 0);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(26));
      lp.rightMargin = a.dp(4);
      activeChips.addView(c, lp);
    }
    if (!"最新".equals(sortOrder)) {
      Button c = a.chip("排序: " + sortOrder + " ✕", true, v -> { sortOrder = "最新"; a.render(); });
      c.setTextSize(10 * a.scale);
      c.setPadding(a.dp(6), 0, a.dp(6), 0);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(26));
      lp.rightMargin = a.dp(4);
      activeChips.addView(c, lp);
    }
    if (activeCount > 1) {
      Button clearAll = a.chip("清空全部 ✕", false, v -> { resetFilters(); a.render(); });
      clearAll.setTextSize(10 * a.scale);
      clearAll.setTextColor(Color.parseColor("#EF4444"));
      clearAll.setPadding(a.dp(6), 0, a.dp(6), 0);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(26));
      lp.rightMargin = a.dp(4);
      activeChips.addView(clearAll, lp);
    }
    activeChipsScroll.addView(activeChips);
    filterBar.addView(activeChipsScroll, new LinearLayout.LayoutParams(0, -2, 1));

    // View Mode Switcher
    boolean isDetailed = "detailed".equals(viewMode);
    Button modeBtn = a.chip(isDetailed ? "⊞ 平铺详细" : "≡ 紧凑模式", isDetailed, v -> {
      viewMode = isDetailed ? "compact" : "detailed";
      a.render();
    });
    modeBtn.setTextSize(11 * a.scale);
    modeBtn.setPadding(a.dp(8), 0, a.dp(8), 0);
    LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(-2, a.dp(30));
    mbLp.leftMargin = a.dp(6);
    filterBar.addView(modeBtn, mbLp);

    controls.addView(filterBar);

    renderResults(controls);
  }

  LinearLayout resultsBar;
  void renderResults(LinearLayout controls) {
    a.body.removeAllViews();
    if (resultsBar != null && resultsBar.getParent() == controls) controls.removeView(resultsBar);
    JSONObject selectedNote = (selected == null || "nb_global_root".equals(selected))
        ? null : a.library.find("notebooks", selected);
    if (selected != null && !"nb_global_root".equals(selected)) {
      JSONObject n = a.library.find("notebooks", selected);
      if (n == null) {
        selected = null;
        return;
      }
      String p = n.optString("parent_id", "");
      if (!p.isEmpty() && !p.equals("null"))
        a.body.addView(
            a.button(
                "↑ 上一级",
                v -> {
                  selected = p;
                  a.render();
                }));
    }
    List<JSONObject> rows = visible();
    pruneSelection(rows);
    LinearLayout selectionBar = a.row();
    selectionBar.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(4));
    if (selecting) {
      selectionBar.addView(
          a.chip(
              "全选当前筛选",
              false,
              view -> {
                for (JSONObject item : visible()) selectedVocabularyIds.add(item.optString("id"));
                a.render();
              }));
      selectionBar.addView(
          a.chip(
              "清空",
              false,
              view -> {
                selectedVocabularyIds.clear();
                a.render();
              }));
      selectionBar.addView(
          a.chip("操作 (" + selectedVocabularyIds.size() + ")", true, view -> batchMenu()));
    }
    resultsBar = selectionBar;
    selectionBar.addView(a.text("结果 " + rows.size() + " 条", 12, false));
    controls.addView(selectionBar);

    for (JSONObject v : rows) {
      JSONObject display = null;
      JSONObject n = (selected == null || "nb_global_root".equals(selected))
          ? null : a.library.find("notebooks", selected);
      if (n != null && n.optJSONObject("vocabulary_display") != null)
        display = n.optJSONObject("vocabulary_display").optJSONObject(v.optString("id"));
      if ("detailed".equals(viewMode)) {
        renderVocabularyDetailedCard(v, display);
      } else {
        renderVocabularyCompactCard(v, display);
      }
    }
    if (rows.isEmpty()) {
      LinearLayout empty = a.column();
      empty.setGravity(Gravity.CENTER);
      empty.addView(a.icon("file-tray-outline", 48, a.subtle));
      empty.addView(a.gap(12));
      JSONArray children = selectedNote == null ? null : selectedNote.optJSONArray("child_notebook_ids");
      String state = NotebookSearch.emptyState(!filter.trim().isEmpty() || activeFilterCount() > 0, children != null && children.length() > 0);
      empty.addView(a.text(state.equals("filtered") ? "未找到匹配内容" : state.equals("parent") ? "请选择子笔记" : "此分类暂无词汇记录", 16, true));
      empty.addView(a.gap(6));
      TextView hint = a.text(state.equals("filtered") ? "请调整或清除筛选条件。" : state.equals("parent") ? "选择上方子笔记查看词汇。" : "在「查询和记录」页面查询后，点击整理笔记即可保存到此处。", 13, false);
      if (state.equals("filtered")) empty.addView(a.chip("清除筛选", false, view -> { filter = ""; resetFilters(); a.render(); }));
      hint.setGravity(Gravity.CENTER);
      hint.setPadding(a.dp(16), 0, a.dp(16), 0);
      empty.addView(hint);
      a.body.addView(empty, new LinearLayout.LayoutParams(-1, a.dp(260)));
    }
  }

  void showFilterSheet() {
    final String[] tempType = {typeFilter};
    final String[] tempStatus = {statusFilter};
    final String[] tempFeature = {featureFilter};
    final String[] tempSort = {sortOrder};
    final String[] tempTag = {tagFilter};

    ScrollView scroll = new ScrollView(a);
    scroll.setFillViewport(true);
    LinearLayout sheet = a.column();
    sheet.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(20));
    scroll.addView(sheet);

    AlertDialog dialog = new AlertDialog.Builder(a)
        .setView(scroll)
        .create();

    LinearLayout titleRow = a.row();
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    titleRow.addView(a.icon("funnel-outline", 20, a.accent));
    TextView titleTv = a.text(" 高级筛选与排序", 18, true);
    titleRow.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1));

    Button resetBtn = a.chip("重置", false, null);
    resetBtn.setTextSize(12 * a.scale);
    titleRow.addView(resetBtn);
    sheet.addView(titleRow);

    View div1 = new View(a);
    div1.setBackgroundColor(a.border);
    LinearLayout.LayoutParams dLp1 = new LinearLayout.LayoutParams(-1, a.dp(1));
    dLp1.topMargin = a.dp(10);
    dLp1.bottomMargin = a.dp(12);
    sheet.addView(div1, dLp1);

    Button applyBtn = a.button("应用筛选 (" + countMatches(tempType[0], tempStatus[0], tempFeature[0], tempTag[0]) + " 条)", null);
    applyBtn.setBackground(a.shape(a.accent, 10, a.accent));
    applyBtn.setTextColor(Color.WHITE);

    Runnable updateApplyCount = () -> {
      int count = countMatches(tempType[0], tempStatus[0], tempFeature[0], tempTag[0]);
      applyBtn.setText("应用筛选 (" + count + " 条结果)");
    };

    // 1. 词汇形态
    sheet.addView(a.text("词汇形态", 13, true));
    LinearLayout typeRow = a.row();
    typeRow.setPadding(0, a.dp(6), 0, a.dp(10));
    List<Button> typeChips = new ArrayList<>();
    for (String opt : new String[] {"全部", "单词", "短语"}) {
      Button chip = a.chip(opt, tempType[0].equals(opt), null);
      chip.setTextSize(12 * a.scale);
      chip.setOnClickListener(v -> {
        tempType[0] = opt;
        for (Button b : typeChips) {
          boolean sel = opt.equals(b.getText().toString());
          b.setTextColor(sel ? Color.WHITE : a.foreground);
          b.setBackground(a.shape(sel ? a.accent : a.inputBg, 16, sel ? a.accent : a.border));
        }
        updateApplyCount.run();
      });
      typeChips.add(chip);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
      lp.rightMargin = a.dp(6);
      typeRow.addView(chip, lp);
    }
    sheet.addView(typeRow);

    // 2. 掌握状态
    sheet.addView(a.text("复习掌握状态", 13, true));
    LinearLayout statusRow = a.row();
    statusRow.setPadding(0, a.dp(6), 0, a.dp(10));
    List<Button> statusChips = new ArrayList<>();
    for (String opt : new String[] {"全部", "待复习", "已学习"}) {
      Button chip = a.chip(opt, tempStatus[0].equals(opt), null);
      chip.setTextSize(12 * a.scale);
      chip.setOnClickListener(v -> {
        tempStatus[0] = opt;
        for (Button b : statusChips) {
          boolean sel = opt.equals(b.getText().toString());
          b.setTextColor(sel ? Color.WHITE : a.foreground);
          b.setBackground(a.shape(sel ? a.accent : a.inputBg, 16, sel ? a.accent : a.border));
        }
        updateApplyCount.run();
      });
      statusChips.add(chip);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
      lp.rightMargin = a.dp(6);
      statusRow.addView(chip, lp);
    }
    sheet.addView(statusRow);

    // 3. 内容特征
    sheet.addView(a.text("内容特征", 13, true));
    LinearLayout featureRow = a.row();
    featureRow.setPadding(0, a.dp(6), 0, a.dp(10));
    List<Button> featureChips = new ArrayList<>();
    for (String opt : new String[] {"全部", "有批注", "多释义"}) {
      Button chip = a.chip(opt, tempFeature[0].equals(opt), null);
      chip.setTextSize(12 * a.scale);
      chip.setOnClickListener(v -> {
        tempFeature[0] = opt;
        for (Button b : featureChips) {
          boolean sel = opt.equals(b.getText().toString());
          b.setTextColor(sel ? Color.WHITE : a.foreground);
          b.setBackground(a.shape(sel ? a.accent : a.inputBg, 16, sel ? a.accent : a.border));
        }
        updateApplyCount.run();
      });
      featureChips.add(chip);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
      lp.rightMargin = a.dp(6);
      featureRow.addView(chip, lp);
    }
    sheet.addView(featureRow);

    // 4. 排序规则
    sheet.addView(a.text("排序规则", 13, true));
    LinearLayout sortRow = a.row();
    sortRow.setPadding(0, a.dp(6), 0, a.dp(10));
    List<Button> sortChips = new ArrayList<>();
    for (String opt : new String[] {"最新", "最早", "A-Z", "Z-A"}) {
      String label = "最新".equals(opt) ? "最新添加" : "最早".equals(opt) ? "最早添加" : "A-Z".equals(opt) ? "字母 A-Z" : "字母 Z-A";
      Button chip = a.chip(label, tempSort[0].equals(opt), null);
      chip.setTag(opt);
      chip.setTextSize(12 * a.scale);
      chip.setOnClickListener(v -> {
        tempSort[0] = opt;
        for (Button b : sortChips) {
          boolean sel = opt.equals(b.getTag());
          b.setTextColor(sel ? Color.WHITE : a.foreground);
          b.setBackground(a.shape(sel ? a.accent : a.inputBg, 16, sel ? a.accent : a.border));
        }
        updateApplyCount.run();
      });
      sortChips.add(chip);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
      lp.rightMargin = a.dp(6);
      sortRow.addView(chip, lp);
    }
    sheet.addView(sortRow);

    // 5. 标签分类 (带快速搜索)
    Map<String, Integer> tagCounts = new LinkedHashMap<>();
    JSONArray allVocab = a.library.array("vocabularies");
    for (int i = 0; i < allVocab.length(); i++) {
      JSONObject v = allVocab.optJSONObject(i);
      if (v == null) continue;
      JSONArray ts = v.optJSONArray("tags");
      for (int j = 0; ts != null && j < ts.length(); j++) {
        String t = ts.optString(j);
        if (!t.isEmpty()) {
          tagCounts.put(t, tagCounts.getOrDefault(t, 0) + 1);
        }
      }
    }

    LinearLayout tagHeaderRow = a.row();
    tagHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
    tagHeaderRow.addView(a.text("标签分类", 13, true));
    TextView tagCountLabel = a.text(" (共 " + tagCounts.size() + " 个)", 12, false);
    tagCountLabel.setTextColor(a.muted);
    tagHeaderRow.addView(tagCountLabel);
    sheet.addView(tagHeaderRow);

    EditText tagSearch = a.input("快速过滤标签...", "", false);
    tagSearch.setTextSize(12 * a.scale);
    tagSearch.setMinHeight(0);
    tagSearch.setPadding(a.dp(8), 0, a.dp(8), 0);
    tagSearch.setCompoundDrawables(a.new GlyphDrawable("search-outline", 14, a.subtle), null, null, null);
    tagSearch.setCompoundDrawablePadding(a.dp(4));
    LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(-1, a.dp(34));
    tsLp.topMargin = a.dp(4);
    tsLp.bottomMargin = a.dp(6);
    sheet.addView(tagSearch, tsLp);

    LinearLayout tagContainer = a.column();
    sheet.addView(tagContainer);

    List<Button> allTagChips = new ArrayList<>();
    Runnable renderTags = () -> {
      tagContainer.removeAllViews();
      allTagChips.clear();
      String query = tagSearch.getText().toString().trim().toLowerCase();

      List<String> filteredTags = new ArrayList<>();
      filteredTags.add("全部");
      for (String tag : tagCounts.keySet()) {
        if (query.isEmpty() || tag.toLowerCase().contains(query)) {
          filteredTags.add(tag);
        }
      }

      int perRow = 3;
      LinearLayout curRow = null;
      for (int i = 0; i < filteredTags.size(); i++) {
        if (i % perRow == 0) {
          curRow = a.row();
          curRow.setPadding(0, a.dp(2), 0, a.dp(2));
          tagContainer.addView(curRow);
        }
        String tag = filteredTags.get(i);
        String label = "全部".equals(tag) ? "全部 (" + allVocab.length() + ")" : "#" + tag + " (" + tagCounts.get(tag) + ")";
        boolean isSel = tempTag[0].equals(tag);
        Button chip = a.chip(label, isSel, null);
        chip.setTag(tag);
        chip.setTextSize(10 * a.scale);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setPadding(a.dp(4), 0, a.dp(4), 0);
        chip.setOnClickListener(v -> {
          tempTag[0] = tag;
          for (Button b : allTagChips) {
            boolean sel = tag.equals(b.getTag());
            b.setTextColor(sel ? Color.WHITE : a.foreground);
            b.setBackground(a.shape(sel ? a.accent : a.inputBg, 12, sel ? a.accent : a.border));
          }
          updateApplyCount.run();
        });
        allTagChips.add(chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(30), 1);
        if (i % perRow > 0) lp.leftMargin = a.dp(4);
        curRow.addView(chip, lp);
      }
      if (curRow != null && curRow.getChildCount() < perRow) {
        int remain = perRow - curRow.getChildCount();
        for (int k = 0; k < remain; k++) {
          View spacer = new View(a);
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
          lp.leftMargin = a.dp(4);
          curRow.addView(spacer, lp);
        }
      }
    };
    renderTags.run();

    tagSearch.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) { renderTags.run(); }
      public void afterTextChanged(android.text.Editable s) {}
    });

    resetBtn.setOnClickListener(v -> {
      tempType[0] = "全部";
      tempStatus[0] = "全部";
      tempFeature[0] = "全部";
      tempSort[0] = "最新";
      tempTag[0] = "全部";
      tagSearch.setText("");
      for (Button b : typeChips) {
        boolean sel = "全部".equals(b.getText().toString());
        b.setTextColor(sel ? Color.WHITE : a.foreground);
        b.setBackground(a.shape(sel ? a.accent : a.inputBg, 16, sel ? a.accent : a.border));
      }
      for (Button b : statusChips) {
        boolean sel = "全部".equals(b.getText().toString());
        b.setTextColor(sel ? Color.WHITE : a.foreground);
        b.setBackground(a.shape(sel ? a.accent : a.inputBg, 16, sel ? a.accent : a.border));
      }
      for (Button b : featureChips) {
        boolean sel = "全部".equals(b.getText().toString());
        b.setTextColor(sel ? Color.WHITE : a.foreground);
        b.setBackground(a.shape(sel ? a.accent : a.inputBg, 16, sel ? a.accent : a.border));
      }
      for (Button b : sortChips) {
        boolean sel = "最新".equals(b.getTag());
        b.setTextColor(sel ? Color.WHITE : a.foreground);
        b.setBackground(a.shape(sel ? a.accent : a.inputBg, 16, sel ? a.accent : a.border));
      }
      renderTags.run();
      updateApplyCount.run();
    });

    View div2 = new View(a);
    div2.setBackgroundColor(a.border);
    LinearLayout.LayoutParams dLp2 = new LinearLayout.LayoutParams(-1, a.dp(1));
    dLp2.topMargin = a.dp(12);
    dLp2.bottomMargin = a.dp(12);
    sheet.addView(div2, dLp2);

    LinearLayout bottomActions = a.row();
    bottomActions.setGravity(Gravity.CENTER_VERTICAL);
    Button cancelBtn = a.button("取消", v -> dialog.dismiss());
    cancelBtn.setTextColor(a.muted);
    bottomActions.addView(cancelBtn, new LinearLayout.LayoutParams(0, a.dp(44), 1));

    applyBtn.setOnClickListener(v -> {
      typeFilter = tempType[0];
      statusFilter = tempStatus[0];
      featureFilter = tempFeature[0];
      sortOrder = tempSort[0];
      tagFilter = tempTag[0];
      dialog.dismiss();
      a.render();
    });
    LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(0, a.dp(44), 2);
    aLp.leftMargin = a.dp(8);
    bottomActions.addView(applyBtn, aLp);
    sheet.addView(bottomActions);

    dialog.show();
  }

  void renderVocabularyListCard(JSONObject vocabulary, JSONObject display) {
    if ("detailed".equals(viewMode)) {
      renderVocabularyDetailedCard(vocabulary, display);
    } else {
      renderVocabularyCompactCard(vocabulary, display);
    }
  }

  void renderVocabularyCompactCard(JSONObject vocabulary, JSONObject display) {
    LinearLayout card = a.card(null);
    card.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
    LinearLayout.LayoutParams cardLp = (LinearLayout.LayoutParams) card.getLayoutParams();
    cardLp.bottomMargin = a.dp(8);
    card.setLayoutParams(cardLp);
    card.setOnClickListener(
        view -> {
          if (selecting) {
            toggleSelected(vocabulary.optString("id"));
            a.render();
          } else detail(vocabulary);
        });
    card.setOnLongClickListener(
        view -> {
          showVocabularyActions(vocabulary);
          return true;
        });

    LinearLayout top = a.row();
    top.setGravity(Gravity.CENTER_VERTICAL);
    if (selecting) {
      CheckBox selectedBox = new CheckBox(a);
      selectedBox.setChecked(selectedVocabularyIds.contains(vocabulary.optString("id")));
      selectedBox.setOnCheckedChangeListener(
          (button, checked) -> {
            toggleSelected(vocabulary.optString("id"));
            a.render();
          });
      top.addView(selectedBox);
    }
    LinearLayout title = a.row();
    title.setGravity(Gravity.CENTER_VERTICAL);
    TextView word = a.text(vocabulary.optString("content"), 16, true);
    word.setOnClickListener(view -> { if (selecting) { toggleSelected(vocabulary.optString("id")); a.render(); } else detail(vocabulary); });
    title.addView(word);
    String type = vocabulary.optString("type", "word").equals("phrase") ? "短语" : "单词";
    TextView typeBadge = a.text(type, 11, true);
    typeBadge.setTextColor(a.muted);
    typeBadge.setPadding(a.dp(7), a.dp(3), a.dp(7), a.dp(3));
    typeBadge.setBackground(a.shape(a.inputBg, 16, 0));
    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
    badgeLp.leftMargin = a.dp(6);
    typeBadge.setOnClickListener(view -> { if (selecting) { toggleSelected(vocabulary.optString("id")); a.render(); } else detail(vocabulary); });
    title.addView(typeBadge, badgeLp);
    JSONArray tags = vocabulary.optJSONArray("tags");
    for (int i = 0; tags != null && i < tags.length(); i++) {
      String tagValue = tags.optString(i);
      TextView tag = a.text("#" + tagValue, 11, true);
      tag.setTextColor(a.accent);
      tag.setPadding(a.dp(7), a.dp(3), a.dp(7), a.dp(3));
      tag.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 16, 0));
      tag.setOnClickListener(view -> { tagFilter = tagValue; a.render(); });
      LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
      tagLp.leftMargin = a.dp(4);
      title.addView(tag, tagLp);
    }
    HorizontalScrollView titleScroll = new HorizontalScrollView(a);
    titleScroll.setHorizontalScrollBarEnabled(false);
    titleScroll.addView(title);
    top.addView(titleScroll, new LinearLayout.LayoutParams(0, -2, 1));
    if (!selecting) {
      ImageButton edit = new ImageButton(a);
      edit.setImageDrawable(a.new GlyphDrawable("create-outline", 18, a.accent));
      edit.setContentDescription("编辑词汇");
      edit.setBackground(a.shape(android.graphics.Color.TRANSPARENT, 8, 0));
      edit.setOnClickListener(view -> detail(vocabulary));
      top.addView(edit, new LinearLayout.LayoutParams(a.dp(32), a.dp(32)));
    }
    card.addView(top);

    JSONArray meanings = vocabulary.optJSONArray("meanings");
    JSONObject fields = display == null ? null : display.optJSONObject("fields");
    JSONObject perMeaning = display == null ? null : display.optJSONObject("meaning_fields");
    JSONArray visibleIds = display == null ? null : display.optJSONArray("meaning_ids");
    int visibleCount = 0, annotationCount = 0;
    JSONObject first = null;
    for (int i = 0; meanings != null && i < meanings.length(); i++) {
      JSONObject meaning = meanings.optJSONObject(i);
      if (visibleIds != null && !contains(visibleIds, meaning.optString("id"))) continue;
      if (first == null) first = meaning;
      visibleCount++;
      JSONArray annotations = meaning.optJSONArray("annotations");
      if (annotations != null && displayField(fields, perMeaning == null ? null :
          perMeaning.optJSONObject(meaning.optString("id")), "annotations"))
        annotationCount += annotations.length();
    }
    if (first != null) {
      JSONObject local = perMeaning == null ? null : perMeaning.optJSONObject(first.optString("id"));
      android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
      String phonetic = displayField(fields, local, "phonetic") ? first.optString("phonetic").trim() : "";
      if (!phonetic.isEmpty()) line.append(phonetic).append("  ");
      String pos = first.optString("part_of_speech").trim();
      if (!pos.isEmpty()) {
        int start = line.length();
        line.append(pos);
        line.setSpan(new android.text.style.ForegroundColorSpan(a.accent), start, line.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start,
            line.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.append("  ");
      }
      String definition = displayField(fields, local, "zh_definition") ? first.optString("zh_definition").trim() : "";
      if (definition.isEmpty() && displayField(fields, local, "en_definition"))
        definition = first.optString("en_definition").trim();
      line.append(definition);
      TextView summary = a.text("", 13, false);
      summary.setText(line);
      summary.setSingleLine(true);
      summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.topMargin = a.dp(4);
      card.addView(summary, lp);
    }
    LinearLayout footer = a.row();
    LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(-1, -2);
    footerLp.topMargin = a.dp(5);
    footer.addView(a.text("共 " + visibleCount + " 条释义", 11, false));
    if (annotationCount > 0) {
      TextView annotations = a.text("  ·  " + annotationCount + " 条批注", 11, false);
      annotations.setTextColor(a.accent);
      footer.addView(annotations);
    }
    card.addView(footer, footerLp);
    a.passiveLabels(card);
    View.OnClickListener open = view -> { if (selecting) { toggleSelected(vocabulary.optString("id")); a.render(); } else detail(vocabulary); };
    word.setOnClickListener(open);
    typeBadge.setOnClickListener(open);
    for (int i = 0; tags != null && i < tags.length(); i++) {
      final String tagValue = tags.optString(i);
      title.getChildAt(i + 2).setOnClickListener(view -> { tagFilter = tagValue; a.render(); });
    }
  }

  void renderVocabularyDetailedCard(JSONObject vocabulary, JSONObject display) {
    LinearLayout card = a.card(null);
    card.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14));
    LinearLayout.LayoutParams cardLp = (LinearLayout.LayoutParams) card.getLayoutParams();
    cardLp.bottomMargin = a.dp(10);
    card.setLayoutParams(cardLp);

    String id = vocabulary.optString("id");
    card.setOnClickListener(view -> {
      if (selecting) {
        toggleSelected(id);
        a.render();
      } else detail(vocabulary);
    });
    card.setOnLongClickListener(view -> {
      showVocabularyActions(vocabulary);
      return true;
    });

    // 1. Top row: Selection check + Word + Type badge + Edit icon
    LinearLayout top = a.row();
    top.setGravity(Gravity.CENTER_VERTICAL);
    if (selecting) {
      CheckBox selectedBox = new CheckBox(a);
      selectedBox.setChecked(selectedVocabularyIds.contains(id));
      selectedBox.setOnCheckedChangeListener((button, checked) -> {
        toggleSelected(id);
        a.render();
      });
      top.addView(selectedBox);
    }
    TextView word = a.text(vocabulary.optString("content"), 18, true);
    word.setTextColor(a.foreground);
    top.addView(word, new LinearLayout.LayoutParams(0, -2, 1));

    String type = vocabulary.optString("type", "word").equals("phrase") ? "短语" : "单词";
    TextView typeBadge = a.text(type, 11, true);
    typeBadge.setTextColor(a.accent);
    typeBadge.setPadding(a.dp(7), a.dp(3), a.dp(7), a.dp(3));
    typeBadge.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 16, 0));
    LinearLayout.LayoutParams typeLp = new LinearLayout.LayoutParams(-2, -2);
    typeLp.leftMargin = a.dp(6);
    typeLp.rightMargin = a.dp(8);
    top.addView(typeBadge, typeLp);

    if (!selecting) {
      ImageButton edit = new ImageButton(a);
      edit.setImageDrawable(a.new GlyphDrawable("create-outline", 18, a.accent));
      edit.setContentDescription("编辑词汇");
      edit.setBackground(a.shape(android.graphics.Color.TRANSPARENT, 8, 0));
      edit.setOnClickListener(view -> detail(vocabulary));
      top.addView(edit, new LinearLayout.LayoutParams(a.dp(32), a.dp(32)));
    }
    card.addView(top);

    // 2. Phonetic
    JSONObject fields = display == null ? null : display.optJSONObject("fields");
    JSONObject perMeaning = display == null ? null : display.optJSONObject("meaning_fields");
    JSONArray visibleIds = display == null ? null : display.optJSONArray("meaning_ids");

    String phonetic = vocabulary.optString("phonetic");
    if (phonetic.isEmpty()
        && vocabulary.optJSONArray("meanings") != null
        && vocabulary.optJSONArray("meanings").length() > 0) {
      phonetic = vocabulary.optJSONArray("meanings").optJSONObject(0).optString("phonetic");
    }
    if (!phonetic.isEmpty() && displayField(fields, null, "phonetic")) {
      TextView phoneticText = a.text(phonetic.startsWith("/") ? phonetic : "/" + phonetic + "/", 13, false);
      phoneticText.setTextColor(a.muted);
      LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, -2);
      pLp.topMargin = a.dp(2);
      pLp.bottomMargin = a.dp(4);
      card.addView(phoneticText, pLp);
    }

    // 3. Tags & Reference Notebooks
    JSONArray tags = vocabulary.optJSONArray("tags");
    List<String> referencingNotes = findReferencingNotebooks(id);
    if ((tags != null && tags.length() > 0) || !referencingNotes.isEmpty()) {
      HorizontalScrollView metaScroll = new HorizontalScrollView(a);
      metaScroll.setHorizontalScrollBarEnabled(false);
      LinearLayout metaRow = a.row();
      metaRow.setGravity(Gravity.CENTER_VERTICAL);
      metaRow.setPadding(0, a.dp(4), 0, a.dp(6));

      for (int i = 0; tags != null && i < tags.length(); i++) {
        String tagValue = tags.optString(i);
        TextView tag = a.text("#" + tagValue, 11, true);
        tag.setTextColor(a.accent);
        tag.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
        tag.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 10, 0));
        tag.setOnClickListener(view -> { tagFilter = tagValue; a.render(); });
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
        tagLp.rightMargin = a.dp(4);
        metaRow.addView(tag, tagLp);
      }

      for (String nbName : referencingNotes) {
        TextView nbChip = a.text("📁 " + nbName, 11, false);
        nbChip.setTextColor(a.muted);
        nbChip.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
        nbChip.setBackground(a.shape(a.inputBg, 10, a.border));
        LinearLayout.LayoutParams nbLp = new LinearLayout.LayoutParams(-2, -2);
        nbLp.rightMargin = a.dp(4);
        metaRow.addView(nbChip, nbLp);
      }
      metaScroll.addView(metaRow);
      card.addView(metaScroll);
    }

    // Divider
    View divider = new View(a);
    divider.setBackgroundColor(a.border);
    LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, a.dp(1));
    divLp.topMargin = a.dp(4);
    divLp.bottomMargin = a.dp(8);
    card.addView(divider, divLp);

    // 4. Meanings平铺详细
    JSONArray meanings = vocabulary.optJSONArray("meanings");
    int visibleMeaningIndex = 0;
    int totalAnnotations = 0;
    for (int i = 0; meanings != null && i < meanings.length(); i++) {
      JSONObject m = meanings.optJSONObject(i);
      if (m == null) continue;
      String mId = m.optString("id");
      if (visibleIds != null && !contains(visibleIds, mId)) continue;
      visibleMeaningIndex++;
      JSONObject local = perMeaning == null ? null : perMeaning.optJSONObject(mId);

      LinearLayout mBox = a.column();
      mBox.setPadding(0, a.dp(4), 0, a.dp(6));

      LinearLayout mHeader = a.row();
      mHeader.setGravity(Gravity.CENTER_VERTICAL);

      TextView idxBubble = a.text(String.valueOf(visibleMeaningIndex), 11, true);
      idxBubble.setTextColor(a.accent);
      idxBubble.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
      idxBubble.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 8, 0));
      mHeader.addView(idxBubble);

      String pos = m.optString("part_of_speech").trim();
      if (!pos.isEmpty() && displayField(fields, local, "pos")) {
        TextView posBadge = a.text(pos, 11, true);
        posBadge.setTextColor(a.accent);
        posBadge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
        posBadge.setBackground(a.shape(a.inputBg, 6, a.border));
        LinearLayout.LayoutParams posLp = new LinearLayout.LayoutParams(-2, -2);
        posLp.leftMargin = a.dp(6);
        mHeader.addView(posBadge, posLp);
      }

      String mPhonetic = m.optString("phonetic").trim();
      if (!mPhonetic.isEmpty() && displayField(fields, local, "phonetic")) {
        TextView ph = a.text(mPhonetic, 11, false);
        ph.setTextColor(a.muted);
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(-2, -2);
        phLp.leftMargin = a.dp(6);
        mHeader.addView(ph, phLp);
      }
      mBox.addView(mHeader);

      String zhDef = m.optString("zh_definition").trim();
      if (!zhDef.isEmpty() && displayField(fields, local, "zh_definition")) {
        TextView zhTv = a.text(zhDef, 14, true);
        zhTv.setTextColor(a.foreground);
        LinearLayout.LayoutParams zhLp = new LinearLayout.LayoutParams(-1, -2);
        zhLp.topMargin = a.dp(4);
        mBox.addView(zhTv, zhLp);
      }

      String enDef = m.optString("en_definition").trim();
      if (!enDef.isEmpty() && displayField(fields, local, "en_definition")) {
        TextView enTv = a.text(enDef, 13, false);
        enTv.setTextColor(a.muted);
        LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(-1, -2);
        enLp.topMargin = a.dp(2);
        mBox.addView(enTv, enLp);
      }

      String eg = m.optString("example").trim();
      if (!eg.isEmpty() && displayField(fields, local, "example")) {
        LinearLayout egBox = a.column();
        egBox.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        egBox.setBackground(a.shape(a.inputBg, 6, a.border));
        LinearLayout.LayoutParams egLp = new LinearLayout.LayoutParams(-1, -2);
        egLp.topMargin = a.dp(6);

        TextView egLabel = a.text("例句：", 11, true);
        egLabel.setTextColor(a.accent);
        egBox.addView(egLabel);

        TextView egTv = a.text(eg, 12, false);
        egTv.setTextColor(a.foreground);
        egBox.addView(egTv);
        mBox.addView(egBox, egLp);
      }

      if (displayField(fields, local, "annotations")) {
        JSONArray notes = m.optJSONArray("annotations");
        if (notes != null && notes.length() > 0) {
          totalAnnotations += notes.length();
          for (int j = 0; j < notes.length(); j++) {
            JSONObject note = notes.optJSONObject(j);
            if (note == null) continue;
            LinearLayout noteBox = a.row();
            noteBox.setGravity(Gravity.CENTER_VERTICAL);
            noteBox.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
            noteBox.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x14000000, 6, 0));
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
            noteLp.topMargin = a.dp(4);

            String target = note.optString("target_text");
            String content = note.optString("content");
            TextView noteTv = a.text((target.isEmpty() ? "" : "「" + target + "」 ") + content, 12, false);
            noteTv.setTextColor(a.foreground);
            noteBox.addView(noteTv);
            mBox.addView(noteBox, noteLp);
          }
        }
      }

      card.addView(mBox);
    }

    LinearLayout cardFooter = a.row();
    cardFooter.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams cfLp = new LinearLayout.LayoutParams(-1, -2);
    cfLp.topMargin = a.dp(6);
    cardFooter.setLayoutParams(cfLp);

    cardFooter.addView(a.text("共 " + visibleMeaningIndex + " 条释义", 11, false));
    if (totalAnnotations > 0) {
      TextView annoTv = a.text("  ·  " + totalAnnotations + " 条批注", 11, false);
      annoTv.setTextColor(a.accent);
      cardFooter.addView(annoTv);
    }

    View spacer = new View(a);
    cardFooter.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

    if (isMastered(vocabulary)) {
      TextView st = a.text("✓ 已学习", 11, true);
      st.setTextColor(Color.parseColor("#10B981"));
      cardFooter.addView(st);
    } else if (isDue(vocabulary)) {
      TextView st = a.text("⏳ 待复习", 11, true);
      st.setTextColor(Color.parseColor("#F59E0B"));
      cardFooter.addView(st);
    }
    card.addView(cardFooter);
    a.passiveLabels(card);
  }

  boolean displayField(JSONObject fields, JSONObject localFields, String key) {
    return localFields != null
        ? localFields.optBoolean(key, fields == null || fields.optBoolean(key, true))
        : fields == null || fields.optBoolean(key, true);
  }

  void showVocabularyActions(JSONObject vocabulary) {
    String noteId = (selected == null || "nb_global_root".equals(selected)) ? null : selected;
    String[] actions =
        noteId == null ? new String[] {"查看详情与管理"} : new String[] {"查看详情与管理", "从此笔记移除引用"};
    new AlertDialog.Builder(a)
        .setItems(
            actions,
            (dialog, which) -> {
              if (which == 0) {
                detail(vocabulary);
              } else if (noteId != null) {
                new AlertDialog.Builder(a)
                    .setMessage("仅从此笔记移除此词汇引用，词汇本身和其他笔记不受影响。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "移除",
                        (confirmation, button) ->
                            a.change(
                                () -> {
                                  JSONObject note = a.library.find("notebooks", noteId);
                                  if (note != null)
                                    Library.removeValue(
                                        note.optJSONArray("vocabulary_ids"),
                                        vocabulary.optString("id"));
                                }))
                    .show();
              }
            })
        .show();
  }

  void exportMenu() {
    new AlertDialog.Builder(a)
        .setTitle("导出当前筛选")
        .setItems(
            new String[] {"Markdown", "JSON", "CSV"},
            (d, w) -> {
              List<JSONObject> rows = visible();
              if (w == 0) {
                JSONObject note = (selected == null || "nb_global_root".equals(selected)) ? null : a.library.find("notebooks", selected);
                a.export(
                    "lexinote-filter.md",
                    "text/markdown",
                    NotebookExport.markdownForNotebook(rows, note));
              } else if (w == 1) {
                JSONArray arr = new JSONArray();
                for (JSONObject r : rows) arr.put(r);
                a.export("lexinote-filter.json", "application/json", arr.toString());
              } else a.export("lexinote-filter.csv", "text/csv", "\uFEFF" + a.library.csv(rows));
            })
        .show();
  }

  void toggleSelected(String id) {
    if (!selectedVocabularyIds.add(id)) selectedVocabularyIds.remove(id);
  }

  void pruneSelection(List<JSONObject> scope) {
    HashSet<String> visibleIds = new HashSet<>();
    for (JSONObject vocab : scope) visibleIds.add(vocab.optString("id"));
    NotebookSelection.pruneToScope(selectedVocabularyIds, visibleIds);
  }

  boolean selectionInCurrentScope() {
    HashSet<String> visibleIds = new HashSet<>();
    for (JSONObject vocab : visible()) visibleIds.add(vocab.optString("id"));
    return NotebookSelection.isWithinScope(selectedVocabularyIds, visibleIds);
  }

  void batchMenu() {
    if (selectedVocabularyIds.isEmpty()) {
      a.say("请先选择词汇");
      return;
    }
    if (!selectionInCurrentScope()) {
      a.say("筛选范围已变化，请重新选择词汇");
      selectedVocabularyIds.clear();
      a.render();
      return;
    }
    List<String> options = new ArrayList<>();
    if (selected != null && !"nb_global_root".equals(selected)) {
      options.add("移动到笔记");
      options.add("复制引用到笔记");
      options.add("从当前笔记移除引用");
    } else {
      options.add("复制引用到笔记");
    }
    options.add("全局删除词汇（所有笔记）");
    new AlertDialog.Builder(a)
        .setTitle("批量操作 · " + selectedVocabularyIds.size() + " 项")
        .setItems(
            options.toArray(new String[0]),
            (dialog, which) -> {
              String action = options.get(which);
              if (action.startsWith("移动") || action.startsWith("复制"))
                chooseBatchTargets(action.startsWith("复制"));
              else if (action.startsWith("从当前"))
                confirmBatch(
                    "仅移除这些词汇在当前笔记中的引用，实体和其他笔记保持不变。",
                    () -> a.library.removeReferences(selected, ids()));
              else
                confirmBatch(
                    "永久删除所选词汇实体，并从所有笔记中移除引用。此操作无法恢复。",
                    () -> a.library.deleteVocabularyEverywhere(ids()));
            })
        .show();
  }

  String[] ids() {
    if (!selectionInCurrentScope()) throw new IllegalStateException("所选词汇已不在当前筛选范围");
    return selectedVocabularyIds.toArray(new String[0]);
  }

  void confirmBatch(String message, Runnable action) {
    new AlertDialog.Builder(a)
        .setMessage(message)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "确认",
            (d, w) -> {
              if (a.change(action::run)) {
                selectedVocabularyIds.clear();
                selecting = false;
              }
            })
        .show();
  }

  void chooseBatchTargets(boolean copy) {
    List<JSONObject> targets = leaves();
    String[] names = new String[targets.size()];
    boolean[] checked = new boolean[targets.size()];
    for (int i = 0; i < targets.size(); i++) {
      names[i] = targets.get(i).optString("name");
      checked[i] = targets.get(i).optString("id").equals(selected);
    }
    new AlertDialog.Builder(a)
        .setTitle(copy ? "复制引用到目标笔记" : "移动到目标笔记")
        .setMultiChoiceItems(names, checked, (dlg, index, value) -> checked[index] = value)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "执行",
            (dlg, w) -> {
              List<String> targetIds = new ArrayList<>();
              for (int i = 0; i < checked.length; i++)
                if (checked[i]) targetIds.add(targets.get(i).optString("id"));
              if (targetIds.isEmpty()) {
                a.say("至少选择一个目标笔记");
                return;
              }
              String sourceId = selected == null ? "nb_global_root" : selected;
              confirmBatch(
                  copy ? "将共享词汇引用复制到所选笔记，并保留各笔记显示设置。" : "将引用移动到所选笔记，并保留显示设置。",
                  () ->
                      a.library.batchReferences(
                          sourceId, ids(), targetIds.toArray(new String[0]), copy));
            })
        .show();
  }

  void manage(JSONObject n) {
    List<String> options = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    if (n != null) {
      options.add("重命名");
      actions.add(() -> rename(n));
      options.add("删除笔记树");
      actions.add(
          () ->
              new AlertDialog.Builder(a)
                  .setMessage("删除当前笔记及其全部子笔记？词汇仍在全局词库。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton(
                      "删除",
                      (d, w) ->
                          a.change(
                              () -> {
                                a.library.deleteNotebook(selected);
                                selected = null;
                              }))
                  .show());
      options.add("创建子分类");
      actions.add(() -> create());
    } else {
      options.add("创建分类");
      actions.add(() -> create());
    }
    options.add("离线添加词汇");
    actions.add(() -> manual());
    new AlertDialog.Builder(a)
        .setTitle("分类管理")
        .setItems(options.toArray(new String[0]), (d, w) -> actions.get(w).run())
        .show();
  }

  String path(String id) {
    JSONObject n = a.library.find("notebooks", id);
    return n == null ? "笔记" : n.optString("name");
  }

  void create() {
    EditText name = a.input("笔记名称", "", false);
    new AlertDialog.Builder(a)
        .setTitle("创建笔记")
        .setView(name)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "创建",
            (d, w) -> a.change(() -> a.library.createNotebook(name.getText().toString(), selected)))
        .show();
  }

  void rename(JSONObject n) {
    if (n.optBoolean("is_default") || n.optBoolean("is_global_root")) {
      a.say("系统笔记不能重命名");
      return;
    }
    EditText name = a.input("笔记名称", n.optString("name"), false);
    new AlertDialog.Builder(a)
        .setTitle("重命名")
        .setView(name)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              if (name.getText().toString().trim().isEmpty()) {
                a.say("名称不能为空");
                return;
              }
              a.change(() -> Library.put(n, "name", name.getText().toString().trim()));
            })
        .show();
  }

  void manual() {
    LinearLayout box = a.column();
    EditText word = a.input("单词或短语", "", false),
        zh = a.input("中文释义", "", true),
        en = a.input("英文释义（可选）", "", true),
        example = a.input("例句（可选）", "", true);
    box.addView(word);
    box.addView(zh);
    box.addView(en);
    box.addView(example);
    new AlertDialog.Builder(a)
        .setTitle("离线添加词汇")
        .setView(box)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "选择笔记",
            (d, w) -> {
              JSONObject result = new JSONObject(), meaning = new JSONObject();
              Library.put(result, "word", word.getText().toString().trim());
              Library.put(
                  result,
                  "type",
                  word.getText().toString().trim().contains(" ") ? "phrase" : "word");
              Library.put(meaning, "zh_definition", zh.getText().toString());
              Library.put(meaning, "en_definition", en.getText().toString());
              Library.put(meaning, "example", example.getText().toString());
              Library.put(result, "meanings", new JSONArray().put(meaning));
              saveDialog(result);
            })
        .show();
  }

  List<JSONObject> leaves() {
    List<JSONObject> out = new ArrayList<>();
    JSONArray ns = a.library.array("notebooks");
    for (int i = 0; i < ns.length(); i++) {
      JSONObject n = ns.optJSONObject(i);
      if (!Library.systemGlobal(n) && n.optJSONArray("child_notebook_ids").length() == 0)
        out.add(n);
    }
    return out;
  }

  void saveDialog(JSONObject result) {
    List<JSONObject> leaves = leaves();
    if (leaves.isEmpty()) {
      a.say("请先创建一个末级笔记");
      return;
    }
    LinearLayout box = a.column();
    List<CheckBox> checks = new ArrayList<>();
    box.addView(a.text("保存全部释义。下面的字段选择仅控制这些笔记中的显示。", 14, false));
    for (JSONObject n : leaves) {
      CheckBox cb = new CheckBox(a);
      cb.setText(n.optString("name"));
      cb.setTextColor(a.foreground);
      cb.setChecked(
          n.optString("id").equals(a.library.settings().optString("default_notebook_id")));
      box.addView(cb);
      checks.add(cb);
    }
    String[] fields = {
      "zh_definition",
      "en_definition",
      "phonetic",
      "example",
      "source",
      "source_images",
      "annotations",
      "remarks"
    };
    String[] names = {"中文释义", "英文释义", "音标", "例句", "来源", "图片", "批注", "备注"};
    List<CheckBox> fs = new ArrayList<>();
    for (int i = 0; i < fields.length; i++) {
      CheckBox cb = new CheckBox(a);
      cb.setText(names[i]);
      cb.setChecked(true);
      cb.setTextColor(a.foreground);
      box.addView(cb);
      fs.add(cb);
    }
    ScrollView scroll = new ScrollView(a);
    scroll.addView(box);
    new AlertDialog.Builder(a)
        .setTitle("保存到多个笔记")
        .setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              try {
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < checks.size(); i++)
                  if (checks.get(i).isChecked()) ids.add(leaves.get(i).optString("id"));
                if (ids.isEmpty()) {
                  a.say("至少选择一个笔记");
                  return;
                }
                JSONObject display = new JSONObject(), selection = new JSONObject();
                for (int i = 0; i < fields.length; i++)
                  Library.put(selection, fields[i], fs.get(i).isChecked());
                Library.put(display, "fields", selection);
                String id = a.library.saveResult(result, ids.toArray(new String[0]), display);
                if (a.session != null && a.session.optJSONObject("query_results") == result) {
                  JSONObject saved = new JSONObject();
                  Library.put(saved, "saved", true);
                  Library.put(saved, "vocabulary_id", id);
                  Library.put(saved, "notebook_ids", new JSONArray(ids));
                  Library.put(a.session, "saved_content", saved);
                  a.library.touchSession(a.session, true);
                }
                a.persist();
                a.say("已保存完整词汇，共享引用到 " + ids.size() + " 个笔记");
                a.render();
              } catch (Exception e) {
                a.error(e);
              }
            })
        .show();
  }

  void saveDefault(JSONObject result) {
    String id = a.library.settings().optString("default_notebook_id", "");
    JSONObject n = a.library.find("notebooks", id);
    if (n == null || Library.systemGlobal(n) || n.optJSONArray("child_notebook_ids").length() > 0) {
      a.say("请先在设置中选择默认末级笔记");
      return;
    }
    try {
      JSONObject fields = new JSONObject();
      for (String f :
          new String[] {
            "zh_definition",
            "en_definition",
            "phonetic",
            "example",
            "source",
            "source_images",
            "annotations",
            "remarks"
          }) Library.put(fields, f, true);
      JSONObject display = new JSONObject();
      Library.put(display, "fields", fields);
      String vocabId = a.library.saveResult(result, new String[] {id}, display);
      if (a.session != null && a.session.optJSONObject("query_results") == result) {
        JSONObject saved = new JSONObject();
        Library.put(saved, "saved", true);
        Library.put(saved, "vocabulary_id", vocabId);
        Library.put(saved, "notebook_ids", new JSONArray().put(id));
        Library.put(a.session, "saved_content", saved);
        a.library.touchSession(a.session, true);
      }
      a.persist();
      a.say("已保存到" + n.optString("name"));
      a.render();
    } catch (Exception e) {
      a.error(e);
    }
  }

  void renderMeanings(LinearLayout box, JSONObject v, JSONObject display, boolean full) {
    JSONArray ms = v.optJSONArray("meanings");
    JSONObject fields = display == null ? null : display.optJSONObject("fields");
    JSONArray ids = display == null ? null : display.optJSONArray("meaning_ids");
    for (int i = 0; i < ms.length(); i++) {
      JSONObject m = ms.optJSONObject(i);
      if (!full && ids != null && !contains(ids, m.optString("id"))) continue;
      StringBuilder text = new StringBuilder(m.optString("part_of_speech"));
      for (String f :
          new String[] {
            "phonetic", "zh_definition", "en_definition", "example", "source", "remarks"
          })
        if ((full || fields == null || fields.optBoolean(f, true)) && !m.optString(f).isEmpty())
          text.append("\n").append(m.optString(f));
      box.addView(a.text(text.toString().trim(), 16, false));
      if (full || fields == null || fields.optBoolean("source_images", true)) {
        JSONArray images = m.optJSONArray("source_images");
        if (images != null)
          for (int j = 0; j < images.length(); j++) {
            ImageView image = new ImageView(a);
            image.setImageURI(android.net.Uri.parse(images.optString(j)));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(image, new LinearLayout.LayoutParams(-1, a.dp(140)));
          }
      }
      if (full || fields == null || fields.optBoolean("annotations", true)) {
        JSONArray notes = m.optJSONArray("annotations");
        if (notes != null)
          for (int j = 0; j < notes.length(); j++)
            box.addView(a.text("批注：" + notes.optJSONObject(j).optString("content"), 14, false));
      }
    }
    if (v.optJSONArray("tags") != null)
      box.addView(a.text("标签 " + v.optJSONArray("tags").toString(), 13, false));
  }

  void detail(JSONObject v) {
    a.activeVocabulary = v;
    a.screen = "vocabulary";
    a.render();
  }

  TextView createSelectableText(
      JSONObject meaning, String field, String text, int sizeSp, boolean bold, int color) {
    TextView tv = a.text(text, sizeSp, bold);
    tv.setTextColor(color);
    tv.setTextIsSelectable(true);
    tv.setCustomSelectionActionModeCallback(
        new ActionMode.Callback() {
          final int annotationActionId = View.generateViewId();

          @Override
          public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            menu.add(Menu.NONE, annotationActionId, Menu.NONE, "批注选中文字");
            return true;
          }

          @Override
          public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
          }

          @Override
          public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (!AnnotationActionIds.isAnnotationAction(item.getItemId(), annotationActionId))
              return false;
            int start = tv.getSelectionStart(), end = tv.getSelectionEnd();
            String selectedText =
                start >= 0 && end > start ? tv.getText().subSequence(start, end).toString() : "";
            mode.finish();
            annotationDialog(meaning, field, selectedText, null);
            return true;
          }

          @Override
          public void onDestroyActionMode(ActionMode mode) {}
        });
    return tv;
  }

  void editWord(JSONObject v) {
    LinearLayout box = a.column();
    box.setPadding(0, a.dp(4), 0, a.dp(4));

    EditText contentInput = a.input("词汇拼写", v.optString("content"), false);
    box.addView(contentInput);

    final String[] selectedType = {v.optString("type", "word")};
    LinearLayout typeRow = a.row();
    LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
    trLp.topMargin = a.dp(8);
    trLp.bottomMargin = a.dp(8);
    typeRow.setLayoutParams(trLp);

    Button wordBtn = a.chip("单词", "word".equals(selectedType[0]), null);
    Button phraseBtn = a.chip("短语", "phrase".equals(selectedType[0]), null);
    wordBtn.setOnClickListener(
        view -> {
          selectedType[0] = "word";
          wordBtn.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 10, a.accent));
          wordBtn.setTextColor(a.accent);
          phraseBtn.setBackground(a.shape(a.inputBg, 10, a.border));
          phraseBtn.setTextColor(a.muted);
        });
    phraseBtn.setOnClickListener(
        view -> {
          selectedType[0] = "phrase";
          phraseBtn.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 10, a.accent));
          phraseBtn.setTextColor(a.accent);
          wordBtn.setBackground(a.shape(a.inputBg, 10, a.border));
          wordBtn.setTextColor(a.muted);
        });
    typeRow.addView(wordBtn);
    typeRow.addView(phraseBtn);
    box.addView(typeRow);

    EditText phoneticInput = a.input("音标 (选填)", v.optString("phonetic"), false);
    box.addView(phoneticInput);

    new AlertDialog.Builder(a)
        .setTitle("编辑词汇与类型")
        .setView(box)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              String newContent = contentInput.getText().toString().trim();
              if (newContent.isEmpty()) {
                a.say("词汇不能为空");
                return;
              }
              a.change(
                  () -> {
                    a.library.renameVocabulary(v.optString("id"), newContent);
                    Library.put(v, "type", selectedType[0]);
                    Library.put(v, "phonetic", phoneticInput.getText().toString().trim());
                    Library.put(v, "updated_at", Library.now());
                  });
            })
        .show();
  }

  void confirmDeleteVocabulary(JSONObject v) {
    String word = v.optString("content");
    new AlertDialog.Builder(a)
        .setTitle("彻底删除词汇")
        .setMessage("确定要彻底从词库中删除「" + word + "」吗？\n所有笔记分类中的引用也将一并清除。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "彻底删除",
            (d, w) ->
                a.change(
                    () -> {
                      a.library.deleteVocabularyEverywhere(
                          new String[] {v.optString("id")});
                      a.activeVocabulary = null;
                      a.screen = "tabs";
                    }))
        .show();
  }

  void confirmDeleteMeaning(JSONObject v, JSONObject m) {
    JSONArray ms = v.optJSONArray("meanings");
    if (ms == null || ms.length() <= 1) {
      a.say("词汇必须至少保留一条释义");
      return;
    }
    new AlertDialog.Builder(a)
        .setTitle("删除释义")
        .setMessage("确定删除该条释义记录吗？")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "删除",
            (d, w) ->
                a.change(
                    () -> {
                      Library.removeValue(ms, m.optString("id"));
                      Library.put(v, "updated_at", Library.now());
                    }))
        .show();
  }

  void addTagDialog(JSONObject v) {
    EditText tagInput = a.input("新标签名称", "", false);
    new AlertDialog.Builder(a)
        .setTitle("添加标签")
        .setView(tagInput)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "添加",
            (d, w) -> {
              String tag = tagInput.getText().toString().trim();
              if (tag.isEmpty()) return;
              a.change(
                  () -> {
                    JSONArray tags = v.optJSONArray("tags");
                    if (tags == null) {
                      tags = new JSONArray();
                      Library.put(v, "tags", tags);
                    }
                    boolean exists = false;
                    for (int i = 0; i < tags.length(); i++) {
                      if (tags.optString(i).equals(tag)) {
                        exists = true;
                        break;
                      }
                    }
                    if (!exists) {
                      tags.put(tag);
                      Library.put(v, "updated_at", Library.now());
                    }
                  });
            })
        .show();
  }

  void renderVocabularyDetail() {
    JSONObject raw = a.activeVocabulary;
    if (raw == null) {
      a.body.addView(a.text("词汇不存在或已被删除", 15, false));
      Button backBtn =
          a.button(
              "返回笔记",
              view -> {
                a.screen = "tabs";
                a.render();
              });
      a.body.addView(backBtn);
      return;
    }
    JSONObject latest = a.library.find("vocabularies", raw.optString("id"));
    final JSONObject v = latest != null ? latest : raw;
    a.activeVocabulary = v;

    LinearLayout wordCard = a.card(null);
    wordCard.setTag("vocabulary-header-card");

    LinearLayout titleRow = a.row();
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView wordText = a.text(v.optString("content"), 24, true);
    wordText.setTextIsSelectable(true);
    wordText.setTextColor(a.foreground);
    titleRow.addView(wordText, new LinearLayout.LayoutParams(0, -2, 1));

    boolean isPhrase = "phrase".equals(v.optString("type"));
    TextView typeBadge = a.text(isPhrase ? "短语" : "单词", 11, true);
    typeBadge.setTextColor(a.accent);
    typeBadge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
    typeBadge.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 6, 0));
    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
    badgeLp.rightMargin = a.dp(4);
    titleRow.addView(typeBadge, badgeLp);

    Button editWordBtn = a.button("编辑", view -> editWord(v));
    editWordBtn.setTextSize(13 * a.scale);
    titleRow.addView(editWordBtn);

    Button delWordBtn = a.button("删除", view -> confirmDeleteVocabulary(v));
    delWordBtn.setTextSize(13 * a.scale);
    delWordBtn.setTextColor(Color.parseColor("#EF4444"));
    titleRow.addView(delWordBtn);

    wordCard.addView(titleRow);

    String phonetic = v.optString("phonetic");
    if (phonetic.isEmpty()
        && v.optJSONArray("meanings") != null
        && v.optJSONArray("meanings").length() > 0) {
      phonetic = v.optJSONArray("meanings").optJSONObject(0).optString("phonetic");
    }
    if (!phonetic.isEmpty()) {
      TextView phoneticText =
          a.text(phonetic.startsWith("/") ? phonetic : "/" + phonetic + "/", 14, false);
      phoneticText.setTextColor(a.muted);
      LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, -2);
      pLp.topMargin = a.dp(2);
      pLp.bottomMargin = a.dp(4);
      wordCard.addView(phoneticText, pLp);
    }

    View divider1 = new View(a);
    divider1.setBackgroundColor(a.border);
    LinearLayout.LayoutParams divLp1 = new LinearLayout.LayoutParams(-1, a.dp(1));
    divLp1.topMargin = a.dp(8);
    divLp1.bottomMargin = a.dp(10);
    wordCard.addView(divider1, divLp1);

    TextView tagsLabel = a.text("标签归类：", 12, true);
    tagsLabel.setTextColor(a.muted);
    wordCard.addView(tagsLabel);

    HorizontalScrollView tagScroll = new HorizontalScrollView(a);
    tagScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout tagList = a.row();
    tagList.setGravity(Gravity.CENTER_VERTICAL);
    tagList.setPadding(0, a.dp(4), 0, a.dp(4));

    JSONArray tags = v.optJSONArray("tags");
    if (tags != null) {
      for (int i = 0; i < tags.length(); i++) {
        String tag = tags.optString(i);
        if (tag.isEmpty()) continue;
        LinearLayout tagPill = a.row();
        tagPill.setGravity(Gravity.CENTER_VERTICAL);
        tagPill.setPadding(a.dp(8), a.dp(4), a.dp(6), a.dp(4));
        tagPill.setBackground(a.shape(a.inputBg, 12, a.border));
        TextView tagT = a.text("#" + tag, 12, false);
        tagT.setTextColor(a.foreground);
        tagPill.addView(tagT);
        TextView closeT = a.text(" ✕", 11, false);
        closeT.setTextColor(a.subtle);
        tagPill.addView(closeT);
        tagPill.setOnClickListener(
            view -> {
              Library.removeValue(tags, tag);
              Library.put(v, "updated_at", Library.now());
              a.persist();
              a.render();
            });
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.rightMargin = a.dp(6);
        tagList.addView(tagPill, pillLp);
      }
    }

    Button addTagBtn = a.button("＋ 标签", view -> addTagDialog(v));
    addTagBtn.setTextSize(12 * a.scale);
    addTagBtn.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
    addTagBtn.setMinHeight(a.dp(28));
    addTagBtn.setMinimumHeight(a.dp(28));
    tagList.addView(addTagBtn);
    tagScroll.addView(tagList);
    wordCard.addView(tagScroll);

    View divider2 = new View(a);
    divider2.setBackgroundColor(a.border);
    LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(-1, a.dp(1));
    divLp2.topMargin = a.dp(8);
    divLp2.bottomMargin = a.dp(10);
    wordCard.addView(divider2, divLp2);

    TextView nbLabel = a.text("归属笔记（点击切换多重引用）：", 12, true);
    nbLabel.setTextColor(a.muted);
    wordCard.addView(nbLabel);

    HorizontalScrollView nbScroll = new HorizontalScrollView(a);
    nbScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout nbList = a.row();
    nbList.setGravity(Gravity.CENTER_VERTICAL);
    nbList.setPadding(0, a.dp(4), 0, a.dp(4));

    List<JSONObject> leafList = leaves();
    for (JSONObject nb : leafList) {
      String nbName = nb.optString("name");
      JSONArray vocabIds = nb.optJSONArray("vocabulary_ids");
      boolean isMember = contains(vocabIds, v.optString("id"));

      Button nbChip =
          a.button(
              (isMember ? "✓ " : "+ ") + nbName,
              view -> {
                if (isMember) {
                  int count = 0;
                  for (JSONObject other : leafList) {
                    if (contains(other.optJSONArray("vocabulary_ids"), v.optString("id"))) count++;
                  }
                  if (count <= 1) {
                    a.say("词汇必须至少归属于一个末级笔记");
                    return;
                  }
                  Library.removeValue(vocabIds, v.optString("id"));
                } else {
                  if (vocabIds == null) {
                    JSONArray newArr = new JSONArray();
                    Library.put(nb, "vocabulary_ids", newArr);
                    newArr.put(v.optString("id"));
                  } else {
                    vocabIds.put(v.optString("id"));
                  }
                }
                a.persist();
                a.render();
              });
      nbChip.setTextSize(12 * a.scale);
      nbChip.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
      nbChip.setMinHeight(a.dp(30));
      nbChip.setMinimumHeight(a.dp(30));
      if (isMember) {
        nbChip.setTextColor(a.accent);
        nbChip.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 12, a.accent));
      } else {
        nbChip.setTextColor(a.muted);
        nbChip.setBackground(a.shape(a.inputBg, 12, a.border));
      }
      LinearLayout.LayoutParams nbLp = new LinearLayout.LayoutParams(-2, -2);
      nbLp.rightMargin = a.dp(6);
      nbList.addView(nbChip, nbLp);
    }
    nbScroll.addView(nbList);
    wordCard.addView(nbScroll);

    LinearLayout meaningsHeader = a.row();
    meaningsHeader.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams mhLp = new LinearLayout.LayoutParams(-1, -2);
    mhLp.topMargin = a.dp(6);
    mhLp.bottomMargin = a.dp(8);
    meaningsHeader.setLayoutParams(mhLp);

    JSONArray ms = v.optJSONArray("meanings");
    int mCount = ms == null ? 0 : ms.length();
    TextView mTitle = a.text("释义与批注 (" + mCount + ")", 16, true);
    meaningsHeader.addView(mTitle, new LinearLayout.LayoutParams(0, -2, 1));

    Button addMeaningBtn = a.button("＋ 新增释义", view -> editMeaning(v, null));
    addMeaningBtn.setTextColor(a.accent);
    meaningsHeader.addView(addMeaningBtn);
    a.body.addView(meaningsHeader);

    if (ms != null) {
      for (int i = 0; i < ms.length(); i++) {
        JSONObject meaning = ms.optJSONObject(i);
        if (meaning == null) continue;
        LinearLayout mCard = a.card(null);
        mCard.setTag("meaning-card-" + i);

        LinearLayout mTop = a.row();
        mTop.setGravity(Gravity.CENTER_VERTICAL);

        TextView idxBubble = a.text(String.valueOf(i + 1), 12, true);
        idxBubble.setTextColor(a.accent);
        idxBubble.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
        idxBubble.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 10, 0));
        mTop.addView(idxBubble);

        String pos = meaning.optString("part_of_speech");
        if (!pos.isEmpty()) {
          TextView posBadge = a.text(pos, 12, true);
          posBadge.setTextColor(a.foreground);
          posBadge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
          posBadge.setBackground(a.shape(a.inputBg, 6, a.border));
          LinearLayout.LayoutParams posLp = new LinearLayout.LayoutParams(-2, -2);
          posLp.leftMargin = a.dp(6);
          mTop.addView(posBadge, posLp);
        }

        String mPhonetic = meaning.optString("phonetic");
        if (!mPhonetic.isEmpty()) {
          TextView ph = a.text(mPhonetic, 12, false);
          ph.setTextColor(a.muted);
          LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(-2, -2);
          phLp.leftMargin = a.dp(6);
          mTop.addView(ph, phLp);
        }

        mTop.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));

        Button editM = a.button("编辑", view -> editMeaning(v, meaning));
        editM.setTextSize(13 * a.scale);
        mTop.addView(editM);

        Button delM = a.button("删除", view -> confirmDeleteMeaning(v, meaning));
        delM.setTextSize(13 * a.scale);
        delM.setTextColor(Color.parseColor("#EF4444"));
        mTop.addView(delM);

        mCard.addView(mTop);

        String zh = meaning.optString("zh_definition");
        if (!zh.isEmpty()) {
          TextView zhTv = createSelectableText(meaning, "zh_definition", zh, 16, true, a.foreground);
          LinearLayout.LayoutParams zhLp = new LinearLayout.LayoutParams(-1, -2);
          zhLp.topMargin = a.dp(8);
          mCard.addView(zhTv, zhLp);
        }

        String en = meaning.optString("en_definition");
        if (!en.isEmpty()) {
          TextView enTv = createSelectableText(meaning, "en_definition", en, 14, false, a.muted);
          LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(-1, -2);
          enLp.topMargin = a.dp(4);
          mCard.addView(enTv, enLp);
        }

        String example = meaning.optString("example");
        if (!example.isEmpty()) {
          LinearLayout egBox = a.column();
          egBox.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
          egBox.setBackground(a.shape(a.inputBg, 8, a.border));
          LinearLayout.LayoutParams egLp = new LinearLayout.LayoutParams(-1, -2);
          egLp.topMargin = a.dp(8);

          TextView egLabel = a.text("例句：", 11, true);
          egLabel.setTextColor(a.accent);
          egBox.addView(egLabel);

          TextView egTv = createSelectableText(meaning, "example", example, 13, false, a.foreground);
          egBox.addView(egTv);
          mCard.addView(egBox, egLp);
        }

        String source = meaning.optString("source");
        if (!source.isEmpty()) {
          TextView srcTv = createSelectableText(meaning, "source", "来源：" + source, 12, false, a.subtle);
          LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
          sLp.topMargin = a.dp(4);
          mCard.addView(srcTv, sLp);
        }

        String remarks = meaning.optString("remarks");
        if (!remarks.isEmpty()) {
          TextView remTv = createSelectableText(meaning, "remarks", "备注：" + remarks, 12, false, a.subtle);
          LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
          rLp.topMargin = a.dp(4);
          mCard.addView(remTv, rLp);
        }

        JSONArray images = meaning.optJSONArray("source_images");
        if (images != null) {
          for (int j = 0; j < images.length(); j++) {
            ImageView img = new ImageView(a);
            img.setImageURI(Uri.parse(images.optString(j)));
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(-1, a.dp(140));
            imgLp.topMargin = a.dp(8);
            mCard.addView(img, imgLp);
          }
        }

        JSONArray notes = meaning.optJSONArray("annotations");
        if (notes != null && notes.length() > 0) {
          LinearLayout notesContainer = a.column();
          notesContainer.setPadding(0, a.dp(8), 0, 0);
          TextView notesHeading = a.text("批注 (" + notes.length() + ")", 12, true);
          notesHeading.setTextColor(a.muted);
          notesContainer.addView(notesHeading);

          for (int j = 0; j < notes.length(); j++) {
            JSONObject note = notes.optJSONObject(j);
            if (note == null) continue;
            LinearLayout noteItem = a.row();
            noteItem.setGravity(Gravity.CENTER_VERTICAL);
            noteItem.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
            noteItem.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x14000000, 6, 0));

            String targetText = note.optString("target_text");
            String content = note.optString("content");
            String noteDisplay = (targetText.isEmpty() ? "" : "「" + targetText + "」 ") + content;

            TextView noteTv = a.text(noteDisplay, 12, false);
            noteTv.setTextColor(a.foreground);
            noteItem.addView(noteTv, new LinearLayout.LayoutParams(0, -2, 1));

            TextView editIcon = a.text(" ✎", 12, false);
            editIcon.setTextColor(a.accent);
            noteItem.addView(editIcon);

            noteItem.setOnClickListener(
                view ->
                    annotationDialog(
                        meaning,
                        note.optString("target_field", "zh_definition"),
                        note.optString("target_text"),
                        note));

            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, -2);
            nLp.topMargin = a.dp(4);
            notesContainer.addView(noteItem, nLp);
          }
          mCard.addView(notesContainer);
        }

        LinearLayout cardBottom = a.row();
        cardBottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(-1, -2);
        cbLp.topMargin = a.dp(8);
        cardBottom.setLayoutParams(cbLp);

        TextView tip = a.text("长按文字可选中批注", 11, false);
        tip.setTextColor(a.subtle);
        cardBottom.addView(tip, new LinearLayout.LayoutParams(0, -2, 1));

        Button addNoteBtn =
            a.button("＋ 批注", view -> annotationDialog(meaning, "zh_definition", "", null));
        addNoteBtn.setTextSize(12 * a.scale);
        addNoteBtn.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
        addNoteBtn.setMinHeight(a.dp(28));
        addNoteBtn.setMinimumHeight(a.dp(28));
        cardBottom.addView(addNoteBtn);

        mCard.addView(cardBottom);
      }
    }

    LinearLayout bottomCard = a.card(null);
    bottomCard.setTag("vocabulary-bottom-actions");
    LinearLayout bRow = a.row();
    LinearLayout.LayoutParams bLp1 = new LinearLayout.LayoutParams(0, a.dp(42), 1);
    bLp1.rightMargin = a.dp(8);
    bRow.addView(a.outlineButton("配置各笔记显示", view -> displayConfig(v)), bLp1);
    bRow.addView(
        a.outlineButton("调整多个笔记归属", view -> membership(v)),
        new LinearLayout.LayoutParams(0, a.dp(42), 1));
    bottomCard.addView(bRow);

    Button backToList =
        a.primaryButton(
            "返回笔记列表",
            view -> {
              a.screen = "tabs";
              a.render();
            });
    LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, a.dp(42));
    backLp.topMargin = a.dp(8);
    bottomCard.addView(backToList, backLp);
  }

  String tags(JSONArray a) {
    if (a == null) return "";
    List<String> s = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
    return String.join(", ", s);
  }

  void meanings(JSONObject v) {
    JSONArray ms = v.optJSONArray("meanings");
    String[] options = new String[ms.length() + 1];
    for (int i = 0; i < ms.length(); i++)
      options[i] =
          (i + 1)
              + " "
              + ms.optJSONObject(i)
                  .optString("zh_definition", ms.optJSONObject(i).optString("en_definition"));
    options[ms.length()] = "+ 添加释义";
    new AlertDialog.Builder(a)
        .setTitle("选择释义")
        .setItems(options, (d, w) -> editMeaning(v, w == ms.length() ? null : ms.optJSONObject(w)))
        .show();
  }

  void editMeaning(JSONObject vocab, JSONObject original) {
    JSONObject m = original == null ? new JSONObject() : original;
    LinearLayout box = a.column();
    String[] fields = {
      "part_of_speech", "zh_definition", "en_definition", "phonetic", "example", "source", "remarks"
    };
    String[] labels = {"词性", "中文释义", "英文释义", "音标", "例句", "来源", "备注"};
    List<EditText> inputs = new ArrayList<>();
    for (int i = 0; i < fields.length; i++) {
      box.addView(a.text(labels[i], 13, true));
      EditText e = a.input(labels[i], m.optString(fields[i]), i > 0);
      box.addView(e);
      inputs.add(e);
    }
    JSONArray annotations = m.optJSONArray("annotations");
    if (annotations != null)
      for (int i = 0; i < annotations.length(); i++) {
        JSONObject note = annotations.optJSONObject(i);
        box.addView(
            a.button(
                "删除批注：" + note.optString("content"),
                view -> a.change(() -> Library.removeValue(annotations, note.optString("id")))));
      }
    EditText annotation = a.input("添加一条批注（绑定当前释义）", "", true);
    box.addView(annotation);
    ScrollView scroll = new ScrollView(a);
    scroll.addView(box);
    new AlertDialog.Builder(a)
        .setTitle("编辑释义")
        .setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) ->
                a.change(
                    () -> {
                      if (inputs.get(1).getText().toString().trim().isEmpty()
                          && inputs.get(2).getText().toString().trim().isEmpty())
                        throw new IllegalArgumentException("至少填写中文或英文释义");
                      for (int i = 0; i < fields.length; i++)
                        Library.put(m, fields[i], inputs.get(i).getText().toString());
                      if (original == null) {
                        Library.put(m, "id", Library.id());
                        Library.put(m, "created_at", Library.now());
                        vocab.optJSONArray("meanings").put(m);
                      }
                      Library.put(m, "updated_at", Library.now());
                      if (!annotation.getText().toString().trim().isEmpty()) {
                        JSONArray notes = m.optJSONArray("annotations");
                        if (notes == null) {
                          notes = new JSONArray();
                          Library.put(m, "annotations", notes);
                        }
                        JSONObject note = new JSONObject();
                        Library.put(note, "id", Library.id());
                        Library.put(note, "meaning_id", m.optString("id"));
                        Library.put(note, "content", annotation.getText().toString());
                        Library.put(note, "created_at", Library.now());
                        Library.put(note, "updated_at", Library.now());
                        notes.put(note);
                      }
                    }))
        .show();
  }

  void annotationDialog(
      JSONObject meaning, String defaultField, String targetText, JSONObject existing) {
    String[] fields = {"zh_definition", "en_definition", "example", "source", "remarks"};
    String[] labels = {"中文释义", "英文释义", "例句", "来源", "备注"};
    int selectedField = 0;
    for (int i = 0; i < fields.length; i++) if (fields[i].equals(defaultField)) selectedField = i;
    Spinner spinner = new Spinner(a);
    spinner.setAdapter(
        new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, labels));
    spinner.setSelection(selectedField);
    EditText target = a.input("目标字词 / 片段", targetText, false);
    EditText content = a.input("批注内容", existing == null ? "" : existing.optString("content"), true);
    LinearLayout box = a.column();
    box.addView(spinner);
    box.addView(target);
    box.addView(content);
    AlertDialog.Builder builder =
        new AlertDialog.Builder(a)
            .setTitle(existing == null ? "添加字段批注" : "编辑字段批注")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "保存",
                (d, w) ->
                    a.change(
                        () -> {
                          int index = spinner.getSelectedItemPosition();
                          if (existing == null)
                            Annotations.add(
                                meaning,
                                fields[index],
                                target.getText().toString(),
                                content.getText().toString());
                          else
                            Annotations.edit(
                                meaning,
                                existing.optString("id"),
                                fields[index],
                                target.getText().toString(),
                                content.getText().toString());
                        }));
    if (existing != null)
      builder.setNeutralButton(
          "删除", (d, w) -> a.change(() -> Annotations.remove(meaning, existing.optString("id"))));
    builder.show();
  }

  void membership(JSONObject v) {
    List<JSONObject> leaves = leaves();
    String[] names = new String[leaves.size()];
    boolean[] checked = new boolean[leaves.size()];
    for (int i = 0; i < names.length; i++) {
      names[i] = leaves.get(i).optString("name");
      checked[i] = contains(leaves.get(i).optJSONArray("vocabulary_ids"), v.optString("id"));
    }
    new AlertDialog.Builder(a)
        .setTitle("笔记归属（共享实体）")
        .setMultiChoiceItems(names, checked, (d, w, c) -> checked[w] = c)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) ->
                a.change(
                    () -> {
                      for (int i = 0; i < names.length; i++) {
                        JSONArray ids = leaves.get(i).optJSONArray("vocabulary_ids");
                        Library.removeValue(ids, v.optString("id"));
                        if (checked[i]) ids.put(v.optString("id"));
                      }
                    }))
        .show();
  }

  void displayConfig(JSONObject v) {
    List<JSONObject> refs = new ArrayList<>();
    for (JSONObject n : leaves())
      if (contains(n.optJSONArray("vocabulary_ids"), v.optString("id"))) refs.add(n);
    String[] names = new String[refs.size()];
    for (int i = 0; i < names.length; i++) names[i] = refs.get(i).optString("name");
    new AlertDialog.Builder(a)
        .setTitle("选择笔记的独立显示配置")
        .setItems(
            names,
            (d, w) -> {
              JSONObject n = refs.get(w);
              LinearLayout box = a.column();
              String[] fields = {
                "zh_definition",
                "en_definition",
                "phonetic",
                "example",
                "source",
                "source_images",
                "annotations",
                "remarks"
              };
              String[] labels = {"中文释义", "英文释义", "音标", "例句", "来源", "图片", "批注", "备注"};
              List<CheckBox> checks = new ArrayList<>();
              JSONObject displays = n.optJSONObject("vocabulary_display");
              JSONObject current =
                  displays == null ? null : displays.optJSONObject(v.optString("id"));
              JSONObject fs = current == null ? null : current.optJSONObject("fields");
              for (int i = 0; i < fields.length; i++) {
                CheckBox cb = new CheckBox(a);
                cb.setText(labels[i]);
                cb.setTextColor(a.foreground);
                cb.setChecked(fs == null || fs.optBoolean(fields[i], true));
                box.addView(cb);
                checks.add(cb);
              }
              List<CheckBox> meanings = new ArrayList<>();
              JSONArray ms = v.optJSONArray("meanings");
              for (int i = 0; i < ms.length(); i++) {
                JSONObject m = ms.optJSONObject(i);
                CheckBox cb = new CheckBox(a);
                cb.setText(
                    "释义 "
                        + (i + 1)
                        + " "
                        + m.optString("zh_definition", m.optString("en_definition")));
                cb.setTextColor(a.foreground);
                cb.setChecked(
                    current == null
                        || current.optJSONArray("meaning_ids") == null
                        || contains(current.optJSONArray("meaning_ids"), m.optString("id")));
                box.addView(cb);
                meanings.add(cb);
              }
              ScrollView scroll = new ScrollView(a);
              scroll.addView(box);
              new AlertDialog.Builder(a)
                  .setTitle(n.optString("name") + " · 显示内容")
                  .setView(scroll)
                  .setNegativeButton("取消", null)
                  .setPositiveButton(
                      "保存",
                      (dialog, which) ->
                          a.change(
                              () -> {
                                JSONObject config = new JSONObject(), newFields = new JSONObject();
                                for (int i = 0; i < fields.length; i++)
                                  Library.put(newFields, fields[i], checks.get(i).isChecked());
                                Library.put(config, "fields", newFields);
                                JSONArray ids = new JSONArray();
                                for (int i = 0; i < meanings.size(); i++)
                                  if (meanings.get(i).isChecked())
                                    ids.put(ms.optJSONObject(i).optString("id"));
                                Library.put(config, "meaning_ids", ids);
                                if (n.optJSONObject("vocabulary_display") == null)
                                  Library.put(n, "vocabulary_display", new JSONObject());
                                Library.put(
                                    n.optJSONObject("vocabulary_display"),
                                    v.optString("id"),
                                    config);
                              }))
                  .show();
            })
        .show();
  }
}
