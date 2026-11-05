package com.yg.fpbhook;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/**
 * 仅作为模块入口，方便在桌面看到模块并查看接入说明。
 * 真正的 Hook 逻辑在 {@link HookEntry}。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setPadding(64, 160, 64, 64);
        tv.setGravity(Gravity.START);
        tv.setTextSize(15f);
        tv.setText("发票盒子 VIP Hook\n"
                + "libxposed API 102\n\n"
                + "1. 在 LSPosed 中启用本模块\n"
                + "2. 作用域勾选「发票盒子」\n"
                + "3. 强制停止目标应用后重新打开\n\n"
                + "日志：\n adb logcat -s FpbHook");
        setContentView(tv);
    }
}
