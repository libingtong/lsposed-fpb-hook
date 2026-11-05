# fpb-hook —— 发票盒子 VIP 解锁（LSPosed / libxposed API 102）

针对 `com.quickwis.fapiaohezi` 的 Xposed 模块。真机（PJZ110 / KernelSU / LSPosed 2.2.1 / 发票盒子 1.97.7）实测通过。

仓库：https://github.com/libingtong/lsposed-fpb-hook （private）
产物：GitHub Actions → Artifacts → `fpb-hook-release`

## 为什么不用 YukiHookAPI

libxposed API 102 规范明确：

> **Behavior changes: Modules targeting 102 or higher**
> Libxposed modules can not call legacy `de.robv.android.xposed` APIs.

YukiHookAPI 构建在 legacy `de.robv.android.xposed`（API 82）之上，产出的模块是 82 模块，和 102 是两套体系。要真正满足 `targetApiVersion=102`，必须直接实现 `io.github.libxposed.api.XposedModule`。

KavaRef 是纯反射库、不依赖 hook API，本可搭配，但本模块反射量很小，用 JDK 反射即可，少一层版本耦合。

## 依赖

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")
```

## 签名

Release 签名由固定 keystore 完成，密钥以 GitHub Secrets 注入，不入库：

| Secret | 说明 |
|---|---|
| `KEYSTORE_B64` | keystore 的 base64 |
| `KEYSTORE_PASSWORD` | store 密码 |
| `KEY_ALIAS` | 默认 `fpbhook` |
| `KEY_PASSWORD` | key 密码 |

证书指纹（后续所有版本必须用同一密钥，否则覆盖安装失败）：

```
CN=FpbHook, OU=YG, O=YG, L=GD, ST=GD, C=CN
SHA-256: 32:98:33:6E:05:D9:BD:05:1F:55:5A:1E:F6:CA:4C:C2:CC:B7:E1:F6:9A:54:97:34:2E:29:47:AA:4B:D1:FB:2B
SHA-1  : 39:ED:98:A9:6A:71:53:9B:13:ED:9B:6B:C9:DE:FE:C2:4A:8F:F8:22
有效期 : 2026-11-05 ~ 2056-10-28
```

CI 中 `apksigner verify --print-certs` 会打印并校验，构建日志里可查。

## 解锁原理（跨版本，零硬编码混淆名）

R8 每版重新分配短名，实测对照：

| 语义 | 1.75.0 | 1.97.7 |
|---|---|---|
| 宿主类 | `hi.c` | `di.c` |
| isValidVIP | `y` | `E` |
| isVIP | `w` | `C` |
| isVIPExpired | `x` | `D` |
| vip 字段 | `d` | `c` |

模块不写死其中任何一个。四层结构：

### L1 类型锚点

`com.quickwis.fapiaohezi.network.response.VipInfoBean` —— 业务包内实体类，R8 不改名。这是整条链的唯一定位锚点。

### L2 响应层

hook `SystemBaseDataResponse` 中**所有**「无参返回 VipInfoBean」的方法（`getVip_info()`、`component2()` 等），强制返回合成对象。服务端返回 `vip_info: null` 也拿不到 null。

### L3 属性层

`is_expired()` 恒返 0，`getExpires_at()` 恒返远期。这两个方法名来自 JSON 字段名，跨版本稳定。

### L4 深度搜索（应对类名再次变化）

先用已知候选（`di.c`/`hi.c`…）快速命中。**候选全部失效时**，自动启动全量扫描：

```
BaseDexClassLoader.pathList.dexElements[].dexFile.entries()   ← 枚举进程内所有 dex 的全部类名
      ↓ 过滤系统/三方包前缀
Class.forName(name, false, cl)
      ↓
declaredFields 里存在「static + 类型 == VipInfoBean」→ 命中宿主
      ↓
反射写入字段 + hook 所有 (VipInfoBean)V 写入器
```

也就是说，即使下个版本把 `di.c` 改成 `zz.q`，模块也能自己找到它，**不需要改代码**。扫描在后台线程执行，30 秒上限，不阻塞启动。

### 效果

四层叠加后，`isVIP` / `isVIPExpired` / `isValidVIP` 三个判定结果必然成立——不需要知道它们被混淆成什么名字。

## 文件结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/yg/fpbhook/
│   ├── HookEntry.java        # XposedModule 入口 + 四层 hook
│   ├── Reflect.java          # 反射工具 + dex 全量类名枚举
│   └── MainActivity.java     # 模块入口页
└── resources/META-INF/xposed/
    ├── java_init.list        # com.yg.fpbhook.HookEntry
    ├── module.prop           # minApiVersion=101 targetApiVersion=102
    └── scope.list            # com.quickwis.fapiaohezi
```

`app/build.gradle.kts` 里这段必需，否则 `META-INF/xposed/*` 会被打包流程剥离，LSPosed 识别不到模块：

```kotlin
packaging {
    resources {
        merges += "META-INF/xposed/*"
        excludes += "**"
    }
}
```

## 构建

推送到 `main` 自动触发；也可在 Actions 页面 `Run workflow` 手动触发。

```bash
gradle :app:assembleRelease     # 本地（需自行设置 4 个环境变量，否则回退 debug 签名）
```

## 安装与验证

```bash
adb install -r app-release.apk
```

LSPosed 里启用模块（`staticScope=true`，作用域已默认勾选「发票盒子」），然后强制停止目标应用再打开。

查看运行日志：

```bash
adb logcat -s LSPosedFramework | grep FpbHook
# 或 LSPosed 管理器 → 日志
```

正常输出：

```
onModuleLoaded process=com.quickwis.fapiaohezi framework=LSPosed version=2.2.1 api=102
合成 VipInfoBean = VipInfoBean(is_expired=0, ..., expires_at=2099-12-31 23:59:59)
hook SystemBaseDataResponse.getVip_info() -> 合成对象
hook SystemBaseDataResponse.component2() -> 合成对象
hook VipInfoBean.is_expired() -> 0
hook VipInfoBean.getExpires_at() -> 2099-12-31 23:59:59
宿主 di.c 字段 c 已写入（候选名 di.c）
hook 写入器 c.Y(VipInfoBean)
装配完成
```

若出现 `候选宿主未命中，启动深度搜索` + `深度搜索命中宿主 xxx`，说明新版本改了混淆名，但模块已自动适配。

## 覆盖范围

本地可解锁：PDF 导出清晰度、三/四张 A4 拼版、标签数量、批量选择 10 张、附单 20 张、抬头筛选、通用模板保存、默认收票邮箱、会员卡片与到期文案。

服务端判定，模块无法改变：自定义归类数量、Excel 每月 1 次——由服务端返回 `-2000/-2001/-2002`，客户端只透传错误码。

## 已知边界

- L4 依赖 `dexElements` 隐藏字段反射。若某 ROM 限制该反射，日志会出现「深度搜索不可用」，此时退回候选名 + L1/L2/L3，多数功能仍可用。
- 若服务端把 `vip_info` 换成完全不同的字段/类型（不再是 `VipInfoBean`），L1 失效，需要重新定位锚点。
- 若未来要按**调用关系**精确定位 `isValidVIP` 而不是靠字段注入，可引入 DexKit（`org.luckypray:dexkit`）做字节码特征搜索，例如「调用了 `VipInfoBean.is_expired()` 且返回 boolean 的无参方法」。当前实现不需要它。
