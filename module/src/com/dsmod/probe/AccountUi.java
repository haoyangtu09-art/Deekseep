package com.dsmod.probe;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多账号管理滑入屏：列出已保存的账号槽，点击切换（重启生效），底部“添加账号”。
 * 数据全部走 AccountManager；切换/添加均以重启 DeepSeek 进程让新凭证冷启动生效。
 */
final class AccountUi {

    private static volatile String pendingExportJson;
    private static volatile String pendingExportFileName;
    private static WeakReference<Dialog> activeDialog = new WeakReference<>(null);
    private static WeakReference<LinearLayout> activeContent = new WeakReference<>(null);

    private AccountUi() {}

    static int dp(Activity a, float v) { return DeekseepUi.dp(a, v); }

    static void show(final Activity act) {
        final boolean dark = DeekseepUi.isDark(act);
        final int bg    = dark ? 0xFF1B1B1D : 0xFFF5F6F8;
        final int card  = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int text  = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int sub   = dark ? 0xFF9A9A9E : 0xFF888888;
        final int div   = dark ? 0xFF3A3A3D : 0xFFEEEEEE;

        final Dialog dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        // 顶栏：返回 + 标题
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(act, 8), dp(act, 10), dp(act, 16), dp(act, 10));
        TextView back = new TextView(act);
        back.setText("\u2039");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setTextColor(text);
        back.setPadding(dp(act, 12), 0, dp(act, 12), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { DeekseepUi.slideOutAndDismiss(dlg, root); }
        });
        bar.addView(back);
        TextView title = new TextView(act);
        title.setText("多账号");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(text);
        bar.addView(title);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(act);
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 24));
        sv.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rebuild(act, dlg, content, dark, card, text, sub, div);

        activeDialog = new WeakReference<>(dlg);
        activeContent = new WeakReference<>(content);

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(bg));
        }
        DeekseepUi.openWithSlide(dlg, root);
        final View rootRef = root;
        dlg.setOnKeyListener(new Dialog.OnKeyListener() {
            public boolean onKey(DialogInterface d, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
                    DeekseepUi.slideOutAndDismiss(dlg, rootRef);
                    return true;
                }
                return false;
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                if (activeDialog.get() == dlg) {
                    activeDialog = new WeakReference<>(null);
                    activeContent = new WeakReference<>(null);
                }
            }
        });
    }

    // 重新构建账号列表（切换/删除后可复用；本版切换即重启，故主要用于首次构建）。
    private static void rebuild(final Activity act, final Dialog dlg, final LinearLayout content,
                               boolean dark, int card, int text, int sub, int div) {
        content.removeAllViews();

        final List<AccountManager.Account> accounts =
                AccountManager.accountsForUi(act.getClassLoader());

        LinearLayout listCard = makeCard(act, card);

        if (accounts.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("未检测到已登录账号。请先在 DeepSeek 正常登录一个账号。");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setTextColor(sub);
            empty.setPadding(dp(act, 16), dp(act, 20), dp(act, 16), dp(act, 20));
            listCard.addView(empty);
        } else {
            for (int i = 0; i < accounts.size(); i++) {
                if (i > 0) listCard.addView(makeDivider(act, div));
                listCard.addView(accountRow(act, dlg, accounts.get(i), text, sub));
            }
        }
        content.addView(listCard, cardLp(act, 0));

        // 添加账号按钮
        TextView add = new TextView(act);
        add.setText("＋  添加账号");
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setTextColor(0xFFFFFFFF);
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, dp(act, 16), 0, dp(act, 16));
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(DeekseepUi.BRAND);
        ab.setCornerRadius(dp(act, 14));
        add.setBackground(ab);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmAdd(act); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(act, 16);
        content.addView(add, alp);

        // 凭证导入 / 导出：均使用 SAF，不向模块申请公共存储权限。
        LinearLayout transferRow = new LinearLayout(act);
        transferRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView importButton = actionButton(act, "导入账号", text,
                dark ? 0xFF343438 : 0xFFFFFFFF, div);
        importButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmImport(act); }
        });
        TextView exportButton = actionButton(act, "导出账号", text,
                dark ? 0xFF343438 : 0xFFFFFFFF, div);
        exportButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showExportPicker(act); }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, dp(act, 48), 1f);
        ilp.rightMargin = dp(act, 8);
        transferRow.addView(importButton, ilp);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, dp(act, 48), 1f);
        elp.leftMargin = dp(act, 8);
        transferRow.addView(exportButton, elp);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.topMargin = dp(act, 12);
        content.addView(transferRow, trlp);

        TextView hint = new TextView(act);
        hint.setText("点击账号切换（会重启 DeepSeek）。切换前当前账号自动备份，长按可移除已保存账号。"
                + "添加账号会登出当前账号进入登录页。导入会先严格校验 JSON，再逐个请求 DeepSeek "
                + "确认凭证和账号 ID；请求身份与当前安装的宿主版本一致，批量校验会自动控制频率。"
                + "全部有效后才一次性写入。导出文件含明文登录凭证，请勿分享。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(sub);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(act, 12);
        content.addView(hint, hlp);
    }

    private static View accountRow(final Activity act, final Dialog dlg,
                                   final AccountManager.Account a, int text, int sub) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 14));
        row.setClickable(true);
        row.setFocusable(true);

        // 字母头像圆
        TextView avatar = new TextView(act);
        String letter = a.label != null && a.label.length() > 0
                ? a.label.substring(0, 1).toUpperCase() : "?";
        avatar.setText(letter);
        int[] avc = avatarColors(a.provider);   // {底色, 字色}
        avatar.setTextColor(avc[1]);
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable ac = new GradientDrawable();
        ac.setShape(GradientDrawable.OVAL);
        ac.setColor(avc[0]);
        if (avc[0] == GOOGLE_BG) {   // 灰白头像加一圈淡边，暗色背景下不至于糊
            ac.setStroke(dp(act, 1), 0x22000000);
        }
        avatar.setBackground(ac);
        LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(dp(act, 40), dp(act, 40));
        avlp.rightMargin = dp(act, 12);
        row.addView(avatar, avlp);

        // 名字 + 副标题
        LinearLayout labels = new LinearLayout(act);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(act);
        name.setText(a.label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(text);
        labels.addView(name);
        TextView subtv = new TextView(act);
        subtv.setText(subtitleOf(a));
        subtv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtv.setTextColor(sub);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(act, 2);
        labels.addView(subtv, stlp);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧：当前号显“当前”，其它显箭头
        TextView tail = new TextView(act);
        if (a.current) {
            tail.setText("当前");
            tail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tail.setTextColor(0xFFFFFFFF);
            tail.setPadding(dp(act, 10), dp(act, 4), dp(act, 10), dp(act, 4));
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(0xFF2ECC71);
            bb.setCornerRadius(dp(act, 10));
            tail.setBackground(bb);
        } else {
            tail.setText("\u203A");
            tail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            tail.setTextColor(sub);
        }
        row.addView(tail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!a.current) {
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmSwitch(act, a); }
            });
        }
        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (a.current) {
                    Toast.makeText(act, "不能移除当前登录账号", Toast.LENGTH_SHORT).show();
                    return true;
                }
                confirmRemove(act, dlg, a);
                return true;
            }
        });
        return row;
    }

    private static void confirmSwitch(final Activity act, final AccountManager.Account a) {
        DeekseepUi.showCustomConfirm(act, "切换账号",
                "切换到「" + a.label + "」？\n将重启 DeepSeek 以新账号启动，当前账号已自动备份。",
                "取消", "切换并重启", true, null, new Runnable() {
                    public void run() {
                    boolean ok = AccountManager.switchTo(act.getClassLoader(), a.id);
                    if (!ok) {
                        showResult(act, "切换失败", "无法写入 DeepSeek 登录态，未执行重启。");
                        return;
                    }
                    Toast.makeText(act, "正在切换…", Toast.LENGTH_SHORT).show();
                    AccountManager.restartApp(act);
                }
        });
    }

    private static void confirmAdd(final Activity act) {
        DeekseepUi.showCustomConfirm(act, "添加账号",
                "将登出当前账号并进入原生登录页以登录新账号（支持微信、手机号等；若已开启“解锁 Google 登录”，"
                        + "登录页也会显示宿主原生 Google 入口）。\n"
                        + "当前账号已自动备份，登录新号后可在多账号里切回。是否继续？",
                "取消", "登出并登录新号", true, null, new Runnable() {
                    public void run() {
                    boolean ok = AccountManager.prepareAddAccount(act.getClassLoader());
                    if (!ok) {
                        showResult(act, "操作失败", "无法清除当前登录态，未执行重启。");
                        return;
                    }
                    Toast.makeText(act, "正在进入登录页…", Toast.LENGTH_SHORT).show();
                    AccountManager.restartApp(act);
                }
        });
    }

    private static void confirmRemove(final Activity act, final Dialog dlg,
                                      final AccountManager.Account a) {
        DeekseepUi.showCustomConfirm(act, "移除已保存账号",
                "从多账号列表移除「" + a.label + "」？\n"
                        + "仅删除本模块保存的凭证备份，不影响服务器数据和本地聊天记录。",
                "取消", "移除", true, null, new Runnable() {
                    public void run() {
                    if (AccountManager.removeSlot(a.id)) {
                        Toast.makeText(act, "已移除", Toast.LENGTH_SHORT).show();
                        refreshOpen(act);
                    } else {
                        showResult(act, "移除失败", "账号槽文件未能更新，请稍后重试。");
                    }
                }
        });
    }

    private static void confirmImport(final Activity act) {
        DeekseepUi.showCustomConfirm(act, "导入账号凭证",
                "只接受完整 JSON。模块会先检查全部账号的字段和类型，再使用每个候选 token 请求 "
                        + "DeepSeek 当前用户接口；请求会使用当前安装版本的宿主身份并自动限速，且必须同时通过外层和业务层校验。"
                        + "服务器若返回账号 ID，还必须与文件一致，"
                        + "之后才会一次性加入多账号列表。\n\n"
                        + "校验前不会写入 MMKV、数据库或账号槽。请只导入你本人合法持有的凭证。",
                "取消", "选择 JSON/TXT", true, null, new Runnable() {
                    public void run() { openImportPicker(act); }
                });
    }

    private static void openImportPicker(Activity act) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/json", "text/json", "text/plain"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            act.startActivityForResult(intent, Main.ACCOUNT_IMPORT_REQUEST);
        } catch (Throwable t) {
            showResult(act, "无法打开文件选择器", "请确认系统文件选择器可用后重试。");
        }
    }

    /** 自绘账号勾选弹窗；单账号和多账号使用同一条可审计导出路径。 */
    private static void showExportPicker(final Activity act) {
        final List<AccountManager.Account> accounts =
                AccountManager.accountsForUi(act.getClassLoader());
        if (accounts.isEmpty()) {
            showResult(act, "没有可导出的账号", "请先登录或添加至少一个账号。");
            return;
        }

        final boolean dark = DeekseepUi.isDark(act);
        final int card = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int text = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int sub = dark ? 0xFFAAAAAE : 0xFF707070;
        final int div = dark ? 0xFF414145 : 0xFFE8E9EC;
        final Set<String> selected = new HashSet<>();
        for (AccountManager.Account account : accounts) {
            if (account.current) { selected.add(account.id); break; }
        }
        if (selected.isEmpty()) selected.add(accounts.get(0).id);

        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = makeCard(act, card);
        root.setPadding(dp(act, 20), dp(act, 18), dp(act, 20), dp(act, 16));

        TextView title = new TextView(act);
        title.setText("选择要导出的账号");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(text);
        root.addView(title);

        TextView warning = new TextView(act);
        warning.setText("导出的是可登录账号的明文凭证。拿到文件的人可能直接使用你的账号，请勿分享，使用后及时删除。");
        warning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        warning.setTextColor(0xFFE45555);
        warning.setPadding(0, dp(act, 8), 0, dp(act, 8));
        root.addView(warning);

        ScrollView scroll = new ScrollView(act);
        LinearLayout rows = new LinearLayout(act);
        rows.setOrientation(LinearLayout.VERTICAL);
        final List<TextView> checks = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i++) {
            final AccountManager.Account account = accounts.get(i);
            if (i > 0) rows.addView(makeDivider(act, div));
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(act, 4), dp(act, 12), dp(act, 4), dp(act, 12));
            LinearLayout labels = new LinearLayout(act);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(act);
            name.setText(account.label + (account.current ? "  · 当前" : ""));
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            name.setTextColor(text);
            labels.addView(name);
            TextView detail = new TextView(act);
            detail.setText(subtitleOf(account));
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            detail.setTextColor(sub);
            labels.addView(detail);
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final TextView check = new TextView(act);
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            check.setGravity(Gravity.CENTER);
            check.setTextColor(DeekseepUi.BRAND);
            setCheckedGlyph(check, selected.contains(account.id));
            checks.add(check);
            row.addView(check, new LinearLayout.LayoutParams(dp(act, 42), dp(act, 42)));
            final int index = i;
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (selected.contains(account.id)) selected.remove(account.id);
                    else selected.add(account.id);
                    setCheckedGlyph(checks.get(index), selected.contains(account.id));
                }
            });
            rows.addView(row);
        }
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dp(act, 360), dp(act, 64) * accounts.size()));
        root.addView(scroll, slp);

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = actionButton(act, "取消", text,
                dark ? 0xFF3A3A3E : 0xFFF0F1F4, div);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); }
        });
        TextView export = actionButton(act, "导出所选", 0xFFFFFFFF, DeekseepUi.BRAND,
                DeekseepUi.BRAND);
        export.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (selected.isEmpty()) {
                    Toast.makeText(act, "请至少勾选一个账号", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<AccountCredentialCodec.Entry> entries = new ArrayList<>();
                for (AccountManager.Account account : accounts) {
                    if (selected.contains(account.id)) {
                        entries.add(new AccountCredentialCodec.Entry(
                                account.label, account.id, account.credJson));
                    }
                }
                try {
                    pendingExportJson = AccountCredentialCodec.buildExport(entries);
                    pendingExportFileName = AccountCredentialCodec.suggestFileName(entries);
                    dialog.dismiss();
                    openExportDestination(act, pendingExportFileName);
                } catch (AccountCredentialCodec.FormatException e) {
                    pendingExportJson = null;
                    pendingExportFileName = null;
                    showResult(act, "无法导出", e.getMessage());
                }
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(act, 44), 1f);
        bl.rightMargin = dp(act, 8);
        buttons.addView(cancel, bl);
        LinearLayout.LayoutParams br = new LinearLayout.LayoutParams(0, dp(act, 44), 1f);
        br.leftMargin = dp(act, 8);
        buttons.addView(export, br);
        LinearLayout.LayoutParams bRow = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bRow.topMargin = dp(act, 14);
        root.addView(buttons, bRow);

        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.48f;
            window.setAttributes(attrs);
            window.setLayout(act.getResources().getDisplayMetrics().widthPixels - dp(act, 32),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void setCheckedGlyph(TextView view, boolean checked) {
        view.setText(checked ? "●" : "○");
        view.setContentDescription(checked ? "已选择" : "未选择");
    }

    private static void openExportDestination(Activity act, String fileName) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            act.startActivityForResult(intent, Main.ACCOUNT_EXPORT_REQUEST);
        } catch (Throwable t) {
            pendingExportJson = null;
            pendingExportFileName = null;
            showResult(act, "无法打开保存位置", "请确认系统文件选择器可用后重试。");
        }
    }

    static void handleExportResult(final Activity act, int resultCode, Intent data) {
        final String json = pendingExportJson;
        final String name = pendingExportFileName;
        pendingExportJson = null;
        pendingExportFileName = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            public void run() {
                OutputStream out = null;
                String error = null;
                try {
                    if (json == null) throw new Exception("导出内容已失效，请重新选择账号");
                    out = act.getContentResolver().openOutputStream(uri, "w");
                    if (out == null) throw new Exception("目标文件不可写");
                    out.write(json.getBytes("UTF-8"));
                    out.flush();
                } catch (Throwable t) {
                    error = t.getMessage() == null ? "目标文件写入失败" : t.getMessage();
                } finally {
                    if (out != null) try { out.close(); } catch (Throwable ignored) {}
                }
                final String finalError = error;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (finalError == null) {
                            showResult(act, "导出完成", "已保存：" + name
                                    + "\n\n文件含明文登录凭证，请妥善保管且不要分享。");
                        } else {
                            showResult(act, "导出失败", finalError);
                        }
                    }
                });
            }
        }, "Deekseep-account-export").start();
    }

    static void handleImportResult(final Activity act, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            act.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Throwable ignored) {}

        final ProgressPopup progress = showProgress(act, "正在读取并校验账号文件…");
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                List<AccountCredentialCodec.Entry> entries = null;
                try {
                    String raw = readUriStrictUtf8(act, uri);
                    entries = AccountCredentialCodec.parseImport(raw);
                    final int count = entries.size();
                    for (int i = 0; i < count; i++) {
                        final int at = i + 1;
                        final String label = entries.get(i).name;
                        progress.update(act, "正在向 DeepSeek 验证 " + at + "/" + count
                                + "：" + label);
                        AccountManager.ServerValidation result = AccountManager.validateWithServer(
                                act.getApplicationContext(), entries.get(i).credentialJson);
                        if (!result.valid) {
                            throw new Exception("账号「" + label + "」验证失败：" + result.error);
                        }
                    }
                    progress.update(act, "全部凭证有效，正在写入账号列表…");
                    if (!AccountManager.importValidated(entries)) {
                        throw new Exception("账号槽文件写入失败，未完成导入");
                    }
                } catch (AccountCredentialCodec.FormatException e) {
                    error = "格式错误：" + e.getMessage();
                } catch (CharacterCodingException e) {
                    error = "文件不是完整有效的 UTF-8 文本";
                } catch (Throwable t) {
                    error = t.getMessage() == null ? "导入失败" : t.getMessage();
                }

                final String finalError = error;
                final int imported = entries == null ? 0 : entries.size();
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        progress.dismiss();
                        if (finalError == null) {
                            refreshOpen(act);
                            showResult(act, "导入完成", "已验证并加入 " + imported
                                    + " 个账号。当前登录账号未改变，可在列表中随时切换。");
                        } else {
                            showResult(act, "导入失败", finalError
                                    + "\n\n没有写入任何候选登录凭证。");
                        }
                    }
                });
            }
        }, "Deekseep-account-import").start();
    }

    private static String readUriStrictUtf8(Activity act, Uri uri) throws Exception {
        InputStream in = null;
        try {
            in = act.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("无法读取所选文件");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > AccountCredentialCodec.MAX_IMPORT_BYTES) {
                    throw new Exception("文件超过 1 MiB 上限");
                }
                out.write(buffer, 0, read);
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray())).toString();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static final class ProgressPopup {
        final Dialog dialog;
        final TextView message;

        ProgressPopup(Dialog dialog, TextView message) {
            this.dialog = dialog;
            this.message = message;
        }

        void update(Activity act, final String value) {
            act.runOnUiThread(new Runnable() {
                public void run() { message.setText(value); }
            });
        }

        void dismiss() {
            try { dialog.dismiss(); } catch (Throwable ignored) {}
        }
    }

    private static ProgressPopup showProgress(Activity act, String value) {
        boolean dark = DeekseepUi.isDark(act);
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        LinearLayout root = makeCard(act, dark ? 0xFF2A2A2D : 0xFFFFFFFF);
        root.setPadding(dp(act, 22), dp(act, 22), dp(act, 22), dp(act, 22));
        TextView title = new TextView(act);
        title.setText("导入账号");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(dark ? 0xFFECECEC : 0xFF1A1A1A);
        root.addView(title);
        TextView message = new TextView(act);
        message.setText(value);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        message.setTextColor(dark ? 0xFFB0B0B4 : 0xFF666666);
        message.setPadding(0, dp(act, 12), 0, 0);
        root.addView(message);
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.48f;
            window.setAttributes(attrs);
            window.setLayout(act.getResources().getDisplayMetrics().widthPixels - dp(act, 48),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return new ProgressPopup(dialog, message);
    }

    private static void refreshOpen(Activity act) {
        Dialog dialog = activeDialog.get();
        LinearLayout content = activeContent.get();
        if (dialog == null || content == null || !dialog.isShowing()) return;
        boolean dark = DeekseepUi.isDark(act);
        rebuild(act, dialog, content, dark,
                dark ? 0xFF2A2A2D : 0xFFFFFFFF,
                dark ? 0xFFECECEC : 0xFF1A1A1A,
                dark ? 0xFF9A9A9E : 0xFF888888,
                dark ? 0xFF3A3A3D : 0xFFEEEEEE);
    }

    private static void showResult(Activity act, String title, String message) {
        DeekseepUi.showCustomConfirm(act, title, message, null, "知道了", true, null, null);
    }

    private static String subtitleOf(AccountManager.Account a) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(a.credJson);
            String mobile = o.isNull("mobile_number") ? null : o.optString("mobile_number", null);
            String email = o.isNull("email") ? null : o.optString("email", null);
            if (mobile != null && mobile.length() > 0 && !mobile.equals(a.label)) return mobile;
            if (email != null && email.length() > 0 && !email.equals(a.label)) return email;
        } catch (Throwable ignored) {}
        return a.id != null && a.id.length() > 8 ? a.id.substring(0, 8) : a.id;
    }

    // 头像配色：微信=微信绿底白字；谷歌/苹果/邮箱=灰白底深字；其它按 provider 落到灰白。
    private static final int WECHAT_BG = 0xFF07C160;   // 微信品牌绿
    private static final int GOOGLE_BG = 0xFFEDEFF2;   // 灰白
    private static final int GOOGLE_FG = 0xFF5F6368;   // 深灰字

    // 返回 {底色, 字色}
    private static int[] avatarColors(String provider) {
        String p = provider == null ? "" : provider.toUpperCase();
        if (p.equals("WECHAT")) return new int[]{WECHAT_BG, 0xFFFFFFFF};
        // GOOGLE / APPLE / 邮箱 / 手机 / 未知 → 灰白
        return new int[]{GOOGLE_BG, GOOGLE_FG};
    }

    // ── UI helpers ───────────────────────────────────────────
    private static TextView actionButton(Activity act, String label, int textColor,
                                         int backgroundColor, int strokeColor) {
        TextView button = new TextView(act);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(act, 12));
        background.setStroke(dp(act, 1), strokeColor);
        button.setBackground(background);
        return button;
    }

    private static LinearLayout makeCard(Activity act, int card) {
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card);
        bg.setCornerRadius(dp(act, 14));
        ll.setBackground(bg);
        return ll;
    }

    private static LinearLayout.LayoutParams cardLp(Activity act, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private static View makeDivider(Activity act, int color) {
        View v = new View(act);
        v.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(act, 14), 0, dp(act, 14), 0);
        v.setLayoutParams(lp);
        return v;
    }
}
