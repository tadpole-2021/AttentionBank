# 开发与维护说明

[返回项目首页](../README.md) · [功能说明](FEATURES.md)

## 1. 工程基线

| 项目 | 当前配置 |
| --- | --- |
| 应用 ID / namespace | `com.attentionbank.app` |
| 版本 | `versionName = 0.1.0`，`versionCode = 1` |
| 最低系统 | Android 10 / API 29 |
| 编译与目标 SDK | API 35 |
| JDK / JVM target | 17 |
| Gradle Wrapper | 8.10.2 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / Compose compiler plugin | 2.0.21 |
| Compose BOM | 2024.10.01 |
| Room | 2.6.1 |
| KSP | 2.0.21-1.0.27 |
| 单元测试 | JUnit 4.13.2 |

UI 使用 Jetpack Compose 与 Material 3，异步任务使用 Kotlin Coroutines，界面数据通过 Flow 观察。持久化实际使用 **Room + SharedPreferences**。

Gradle 中还声明了 DataStore、Navigation Compose 等依赖，但当前代码没有用它们实现存储或页面导航；不要仅根据依赖列表推断架构。

## 2. 构建

### Android Studio

1. 安装支持 AGP 8.7 的 Android Studio，例如 Ladybug 2024.2.1 或兼容版本。
2. 以项目根目录打开工程，Gradle JDK 选择 JDK 17。
3. 安装 Android SDK Platform 35，确认 SDK 路径。
4. 等待 Gradle 同步完成后，运行 `app` 到 Android 10 及以上设备。

Gradle Wrapper 使用官方 HTTPS 分发地址，并配置 SHA-256 校验。不依赖原作者电脑路径，也不要求把 `.tools/` 下载目录一起复制。

### 本机配置

`local.properties` 由 Android Studio 创建，或由开发者按本机 SDK 位置设置。例如：

```properties
sdk.dir=C:/Android/Sdk
```

此处仅为格式示例，不应覆盖别人的 SDK 路径。`JAVA_HOME` 应指向 JDK 17。`local.properties`、密钥与环境文件应仅留在本机。

### 命令

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

macOS / Linux：

```bash
sh ./gradlew testDebugUnitTest
sh ./gradlew assembleDebug
```

输出与报告：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/reports/tests/testDebugUnitTest/index.html
app/build/test-results/testDebugUnitTest/
```

首次下载 Gradle、Android 组件和 Maven 依赖需要网络。遇到下载失败应检查网络或本机代理，不要将 Wrapper 改成某个人电脑上的绝对文件路径。

当前 release 构建没有配置发行签名，也没有启用 R8 或资源压缩。不要把构建出的未签名 release 产物或 debug APK 宣称为正式商店版本。

## 3. 代码职责

| 文件或目录 | 职责 |
| --- | --- |
| `AttentionBankApp.kt` | 创建 Room 数据库与共享 Repository |
| `MainActivity.kt` | 首页、应用选择、图标、倍率、权限引导、调试面板 |
| `data/AttentionBankRepository.kt` | 分类持久化、前台包解析、tick、日结、权限查询与诊断摘要 |
| `data/AttentionBankDatabase.kt` | 三张表和 DAO；数据库版本为 1 |
| `domain/AttentionBankModels.kt` | 余额与应用模型、倍率、`applySpend`、`formatDuration` |
| `domain/BlockPagePolicy.kt` | 独立拦截页启动、等待、桌面兜底与重置决策 |
| `domain/OverlayPolicy.kt` | 旧浮层策略保留文件；当前服务不调用此策略 |
| `service/AttentionBankServices.kt` | 前台记账服务、无障碍、通知、开机接收器、独立余额 Activity |
| `res/xml/accessibility_service_config.xml` | 无障碍事件类型与能力声明 |
| `ui/theme/` | 应用主题 |

`app/build/generated/` 下的 Room / KSP 文件是构建产物，不应手工编辑。数据库变更应修改实体与 DAO，并评估版本迁移。

## 4. 运行机制

### 计时与记账

前台服务启动后初始化应用列表和余额，再循环调用 `tick()`，每轮结束后等待约 1 秒。每次读取保存的记账时刻，以设备墙上时间差计算经过秒数，单次最多计入 30 秒。

当前包名解析优先取最近 15 秒 UsageEvents 中最新的前台 / resumed 事件，缺失时使用保存的包名。无障碍服务也会更新保存的前台包名。这个流程是前台识别辅助，**不是历史使用区间重放或漏计对账**。

Repository 内的 Mutex 串行化 tick、日结和即时拦截查询；余额与流水分别写入 DAO，目前尚未用一个数据库事务覆盖完整记账操作。

### 日结

按本地时区计算业务日期：01:00 前视为上一日。发现保存的日结标记落后时逐日迁移余额池，清除旧结转并发放基础额度。

检查由记账、即时拦截查询或首页刷新触发。未使用精确闹钟，不能在服务不运行时承诺 01:00 当秒执行。

### 拦截

无障碍服务监听窗口事件，并约每 500 毫秒检查一次当前应用；前台服务通过应用内非导出接收器发出的广播，也只触发重新检查，不直接信任旧判断。

检查流程只读取应用类型窗口的根包名，排除键盘、系统窗口等非应用类型窗口。查询余额后重新确认包名，防止过时结果干扰新应用。

`BlockPagePolicy` 决策：

- `LAUNCH`：为当前零余额娱乐 App 请求独立余额页面。
- `WAIT`：跳转处理中、窗口未知或查询结果已过时，不重复触发。
- `HOME`：页面请求约 1.5 秒仍未使目标离开前台时，允许单次桌面兜底。
- `RESET`：离开受限应用、恢复余额或屏幕不可用时清除本轮状态。

余额页面使用独立 task affinity，不显示于最近任务，并配置 `noHistory`。页面返回键与按钮控制离开方向，避免直接把娱乐 App 重新露出来。

### 通知

| 通知 | 渠道 / ID | 行为 |
| --- | --- | --- |
| 常驻服务 | `attention_bank_engine` / `1001` | 低重要性，静默，余额变化时更新同一条通知 |
| 余额耗尽 | `attention_bank_balance_alerts` / `1002` | 高重要性，可点击进入独立页面 |

Android 13 及以上需要通知运行时权限。App 还检查耗尽渠道是否关闭，但不会代替用户开启横幅、声音或绕过勿扰模式。

## 5. 数据与隐私边界

Room 文件名为 `attention-bank.db`，包含 `app_selection`、`balance_state`、`usage_ledger`。当前 schema export 为关闭状态，尚无数据库升级迁移用例。

诊断数据保存在 SharedPreferences；`AttentionDebugProbe` 当前仅保存本地最近 8 条事件及摘要，不连接旧调试服务器。代码里的 `PREF_DEBUG_LAST_OVERLAY` 是历史键名，现在对应拦截页面摘要。

正式公开发行前需核对：

- `QUERY_ALL_PACKAGES` 的必要性及分发渠道政策，不能承诺商店必然批准。
- 无障碍用途说明、用户知情授权和所声明能力是否符合最小必要原则。
- `INTERNET` 权限当前未用于业务上传，但仍在 Manifest 中。
- `allowBackup=true` 的系统备份行为和用户数据政策。
- 不要上传包名使用日志、数据库、诊断截图中的个人信息或签名密钥。

## 6. 测试范围

当前 JVM 测试共 25 项：

| 测试类 | 数量 | 范围 |
| --- | --- | --- |
| `BlockPagePolicyTest` | 11 | 跳转去重、过时结果、单次兜底、重新进入、余额恢复 |
| `DurationFormatTest` | 5 | 秒数格式、零值、小时边界与负值防御 |
| `OverlayPolicyTest` | 9 | 旧浮层策略的保留回归测试，不代表当前仍使用浮层 |

这些测试不验证系统权限弹窗、通知是否实际出现、厂商后台启动限制或音频播放器是否暂停。尚未建立完整的设备自动化测试集。

建议真机验证：

1. 分别将应用配置为收集、娱乐与普通应用，核对余额方向。
2. 切换 0.5 / 1 / 2 倍，核对实际消耗与小数余量。
3. 验证余额为零时首次进入、持续使用至零、再次进入的行为。
4. 验证页面启动成功与被拒绝两种路径、通知点击和系统返回键。
5. 撤销通知权限、关闭单个渠道、开启勿扰模式后检查行为。
6. 验证跨 01:00、跨多日恢复、锁屏、后台被杀及设备重启。
7. 检查分屏、画中画、第三方桌面与系统关键 App 是否被误判。

分享实测结果时记录机型、ROM、Android 版本与复现步骤，避免把单台设备成功视为全平台保证。

## 7. 已知技术限制

以下是当前实现边界，本次文档整理未更改这些业务逻辑：

- **锁屏计时**：拦截检查已避开屏幕不可用状态，但记账 tick 尚未统一使用屏幕与锁屏条件，保存的前台包名可能继续影响计时。
- **时间精度与补偿**：使用墙上时间与整数秒差，缺少单调时钟累计、细粒度前台区间切分及完整历史对账。
- **日结异常**：修改设备时间或时区尚无防篡改机制；跨日记账边界仍需专项测试。
- **今日已花统计**：`applySpend` 按计算出的整秒支出累加统计；余额不足以覆盖该次扣减时，统计可能高于实际余额减少量。
- **数据一致性**：余额与流水尚未作为一次事务写入，进程异常退出时一致性需要强化；流水没有自动保留期清理。
- **系统包保护**：目前是常见包名前缀，不包含所有厂商的默认桌面、拨号与短信实现。
- **长期保活**：Manifest 使用 `dataSync` 前台服务类型。Android 15 针对此类型有时长及启动限制，不能把它当作无限后台运行许可；开机恢复也需按目标平台规则复核。
- **后台音频**：独立页面仅申请音频焦点，没有系统级强制停止其他 App 的权限。
- **调试成本**：当前高频记录本地诊断，正式发行前应增加开关或采样并评估性能。
- **发布体积**：仍包含扩展图标库和部分未使用依赖，且没有代码、资源瘦身配置。

## 发布前检查清单

下列项目不是已完成的承诺，也不代表本次会直接执行发布：

- [x] 已采用 MIT License，仓库根目录包含 `LICENSE`。
- [ ] 确定发行签名与密钥保管方案，避免上传 keystore 和密码。
- [ ] 根据发布节奏递增版本号与 `versionCode`。
- [ ] 完善锁屏、前后台切换、历史对账与日结测试。
- [ ] 复核前台服务类型、Android 15 限制和厂商保活行为。
- [ ] 核查包可见性、无障碍、网络和系统备份权限，并提供适当隐私说明。
- [ ] 添加数据库迁移方案及流水保留策略。
- [ ] 收敛调试面板、历史策略代码与未使用依赖。
- [ ] 配置并验证 release 签名、代码压缩与资源瘦身。
- [ ] 验证多品牌真机的计时、拦截、通知与后台播放表现。
- [ ] 单独评估低余额预警、统计报表和数据导出的产品范围。

## 8. 目录维护约定

根目录保留 README、工程配置与 Wrapper；详细说明放在 `docs/`。本地构建产物留在 `app/build/`，不手工搬进源码目录。

`.gitignore` 排除 `.gradle/`、`.kotlin/`、`.tools/`、`.dbg/`、IDE 本机设置、`local.properties`、APK、数据库、日志和签名文件。`gradle/wrapper/gradle-wrapper.jar` 则必须随源码保留。

两份早期构思 / PRD 已由当前文档替代。过时的排错记录与 `.dbg` 环境文件移至工作区外的本机开发归档，避免把旧浮层方案误认为当前实现。

仓库已初始化并推送到 GitHub（`tadpole-2021/AttentionBank`），许可证为 MIT。本地构建产物、`.tools/`、`.dbg/`、`local.properties` 与签名文件不进入版本库。
