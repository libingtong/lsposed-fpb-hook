package io.github.libingtong.fpbhook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块设置页：目前提供「隐藏桌面图标」开关。
 *
 * 实现方式：桌面图标由 AndroidManifest 中的 activity-alias（.LauncherEntry）提供，
 * 切换时通过 PackageManager.setComponentEnabledSetting 启用/禁用该 alias。
 * MainActivity 本身始终可用，所以隐藏图标后模块功能完全不受影响，
 * 仍可从 LSPosed 管理器或 adb 启动。
 */
public class MainActivity extends Activity {

    private static final String PREF = "fpb_hook_pref";
    private static final String KEY_HIDDEN = "hide_launcher";

    private Switch hideSwitch = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int pad = dp(24);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad, dp(40), pad, dp(40));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("发票盒子 VIP Hook");
        title.setTextSize(22f);
        title.setTextColor(Color.parseColor("#111111"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("libxposed API 102");
        sub.setTextSize(13f);
        sub.setTextColor(Color.parseColor("#888888"));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        root.addView(sub, subLp);

        // ---- 隐藏桌面图标 ----
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundColor(Color.parseColor("#F5F6F8"));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(24);
        root.addView(card, cardLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        card.addView(texts, textsLp);

        TextView label = new TextView(this);
        label.setText("隐藏桌面图标");
        label.setTextSize(16f);
        label.setTextColor(Color.parseColor("#111111"));
        texts.addView(label);

        TextView hint = new TextView(this);
        hint.setText("隐藏后模块照常工作，只是桌面不再显示入口。");
        hint.setTextSize(12f);
        hint.setTextColor(Color.parseColor("#888888"));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        texts.addView(hint, hintLp);

        hideSwitch = new Switch(this);
        hideSwitch.setChecked(isLauncherHidden());
        hideSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                applyLauncherHidden(isChecked);
            }
        });
        card.addView(hideSwitch);

        // ---- 恢复方式说明 ----
        TextView tip = new TextView(this);
        tip.setText("隐藏后如需再次打开本页：\n"
                + "adb shell am start -n com.yg.fpbhook/.MainActivity\n\n"
                + "或在 LSPosed 管理器中打开本模块。");
        tip.setTextSize(12f);
        tip.setLineSpacing(dp(2), 1f);
        tip.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(20);
        root.addView(tip, tipLp);

        // ---- 使用说明 ----
        TextView usage = new TextView(this);
        usage.setText("使用步骤\n"
                + "1. 在 LSPosed 中启用本模块\n"
                + "2. 作用域勾选「发票盒子」\n"
                + "3. 强制停止发票盒子后重新打开\n\n"
                + "日志\n"
                + "adb logcat -s LSPosedFramework | grep FpbHook");
        usage.setTextSize(13f);
        usage.setLineSpacing(dp(2), 1f);
        usage.setTextColor(Color.parseColor("#444444"));
        LinearLayout.LayoutParams usageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        usageLp.topMargin = dp(28);
        root.addView(usage, usageLp);

        setContentView(scroll);

        handleHideExtra(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleHideExtra(intent);
    }

    /**
     * adb 可控入口（锁屏/无界面场景下也有用）：
     *   adb shell am start -n com.yg.fpbhook/.MainActivity --ez hide true
     *   adb shell am start -n com.yg.fpbhook/.MainActivity --ez hide false
     */
    private void handleHideExtra(android.content.Intent intent) {
        Bundle extra = intent != null ? intent.getExtras() : null;
        if (extra == null || !extra.containsKey("hide")) return;
        boolean hide = extra.getBoolean("hide");
        applyLauncherHidden(hide);
        if (hideSwitch != null) hideSwitch.setChecked(hide);
    }

    private ComponentName launcherAlias() {
        return new ComponentName(getPackageName(), getPackageName() + ".LauncherEntry");
    }

    private boolean isLauncherHidden() {
        int state = getPackageManager()
                .getComponentEnabledSetting(launcherAlias());
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            return true;
        }
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        return sp.getBoolean(KEY_HIDDEN, false);
    }

    private void applyLauncherHidden(boolean hidden) {
        try {
            getPackageManager().setComponentEnabledSetting(
                    launcherAlias(),
                    hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            getSharedPreferences(PREF, MODE_PRIVATE)
                    .edit().putBoolean(KEY_HIDDEN, hidden).apply();
            Toast.makeText(this,
                    hidden ? "桌面图标已隐藏，模块仍正常工作" : "桌面图标已恢复",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "切换失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}

