# fpb-hook —— 发票盒子 VIP 解锁（LSPosed / libxposed API 102）

针对 `com.quickwis.fapiaohezi` 的 Xposed 模块。已在真机（PJZ110 / KernelSU / 1.97.7）用等价逻辑以 Frida 验证通过，本模块是同一套算法的常驻实现。

## 为什么不用 YukiHookAPI

`libxposed` API 102 的规范里写得很明确：

> Behavior changes: Modules targeting 102 or higher
> Libxposed modules can not call legacy `de.robv.android.xposed` APIs.

YukiHookAPI 是构建在 legacy `de.robv.android.xposed`（API 82）之上的封装，产出的模块是 82 模块。两者是两套体系，不能混用。要真正满足 `targetApiVersion=102`，必须直接实现 `io.github.libxposed.api.XposedModule`。

KavaRef 是纯反射库（不依赖 hook API），本可以搭配使用，但本模块的反射量很小，用 JDK 反射即可，少一层版本耦合。

## 依赖

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")
```

## 文件结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/yg/fpbhook/
│   ├── HookEntry.java        # XposedModule 入口 + Hook 逻辑
│   └── Reflect.java          # 纯反射工具
└── resources/META-INF/xposed/
    ├── java_init.list        # 入口类名
    ├── module.prop           # minApiVersion=101 targetApiVersion=102
    └── scope.list            # 默认作用域：com.quickwis.fapiaohezi
```

`app/build.gradle.kts` 里这段是必需的，否则 `META-INF/xposed/*` 会被打包流程剥离，LSPosed 识别不到模块：

```kotlin
packaging {
    resources {
        merges += "META-INF/xposed/*"
        excludes += "**"
    }
}
```

## 解锁原理（跨版本，零硬编码混淆名）

R8 每个版本重新分配短名，实测对照：

| 语义 | 1.75.0 | 1.97.7 |
|---|---|---|
| 宿主类 | `hi.c` | `di.c` |
| isValidVIP | `y` | `E` |
| isVIP | `w` | `C` |
| vip 字段 | `d` | `c` |

所以模块不写死任何一个。三层锚点：

**L1 类型锚点** — `com.quickwis.fapiaohezi.network.response.VipInfoBean`，业务包内的实体类，R8 不改名。

**L2 数据层** — 反射构造一个 `is_expired=0` 的合法 Bean，然后：
- hook `SystemBaseDataResponse` 中所有「无参返回 VipInfoBean」的方法 → 直接返回合成对象；
- 在宿主类里按**类型**（静态 + 类型为 VipInfoBean）定位字段并写入，不按字段名；
- hook 所有 `(VipInfoBean)V` 写入器，服务端返回 `vip_info: null` 后立即覆盖。

**L3 属性层** — `is_expired()` 恒返 0、`getExpires_at()` 恒返远期。这两个方法名来自 JSON 字段名，跨版本稳定。

三者叠加后，`isVIP` / `isVIPExpired` / `isValidVIP` 三个判定的结果必然成立——**不需要知道它们被混淆成了什么名字**。

## 在线编译（GitHub Actions）

仓库已带 `.github/workflows/build.yml`。

```bash
git init
git add .
git commit -m "fpb hook"
git branch -M main
git remote add origin https://github.com/<you>/fpb-hook.git
git push -u origin main
```

推送后 Actions 自动跑，产物在 **Actions → 对应 run → Artifacts → `fpb-hook-apk`**。

也支持手动触发：Actions 页面 → build → Run workflow。

## 安装

```bash
adb install -r app-debug.apk
```

然后在 LSPosed 里：

1. 模块列表会出现「发票盒子 VIP」
2. 启用模块
3. 作用域勾选「发票盒子」（`scope.list` 已默认填好）
4. 强制停止发票盒子并重新打开

## 查看日志

```bash
adb logcat -s FpbHook
```

正常输出形如：

```
onModuleLoaded process=com.quickwis.fapiaohezi framework=LSPosed api=102
合成 VipInfoBean = VipInfoBean(is_expired=0, ..., expires_at=2099-12-31 23:59:59)
hook SystemBaseDataResponse.getVip_info() -> 合成对象
hook VipInfoBean.is_expired() -> 0
hook VipInfoBean.getExpires_at() -> 2099-12-31 23:59:59
宿主 di.c 字段 c 已写入
hook 写入器 di.c.Y(VipInfoBean)
装配完成
```

## 覆盖范围

本地可解锁：PDF 导出清晰度、三/四张 A4 拼版、标签数量、批量选择 10 张、附单 20 张、抬头筛选、通用模板保存、默认收票邮箱、会员卡片与到期文案。

服务端判定，模块无法改变：自定义归类数量、Excel 每月 1 次——由服务端返回 `-2000/-2001/-2002`，客户端只透传错误码。

## 新版本适配

先看日志。若出现「未命中宿主候选」，说明 AccountHelper 换了包名，此时 L1/L3 仍生效（多数功能可用）；把新名字加进 `HOST_CANDIDATES` 即可。

若 `VipInfoBean` 本身改名（日志报「VipInfoBean 未找到」），改 `HookEntry.VIP` 常量。
