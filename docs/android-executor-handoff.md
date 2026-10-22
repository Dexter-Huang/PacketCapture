# 安卓品牌监测执行器交接

更新：2026-10-22。豆包、元宝快速文本、DeepSeek 普通文本及千问快速模式已完成 APK 自动发题闭环；继续逐个平台加入文心和 Kimi 的无障碍操作。

千问7.4.7.3058原私有CA版，保留ECH/UNet条件、仅选千问并冷启动、自动运行阻止UDP443。APK预检通过后自行输入发送一次，9事件150原始码点11来源，空推理、无诊断，`qianwen-chat-sse/1`；手机/GEO12项字段逐项一致，原详情200，原任务/job/批次完成、单任务、浏览器0、退款0。长图当前独立失败，未重发。该版无发送文字标签，按@节点所在输入工具栏的精确三子控件结构定位第三个按钮；结构不符拒绝发送。

DeepSeek 2.6.2 本地 CA 版在 Android 16 上，深度思考与智能搜索均关闭；APK 不发送预检通过后，自行填写冻结问题并发送一次。新任务唯一完整回答 71 事件、115 码点、空推理和 0 来源，`deepseek-chat-sse/1`；手机与 GEO 的问题、原始正文、推理、来源、decoder、包名及 session/exchange 逐字段一致。原任务、job、批次均完成，单批次任务数 1、浏览器任务 0、退款 0、原详情 HTTP 200。长图当前报渲染或上传失败，须单独核实，未触发重新发题。

该版界面聚焦前仅暴露输入提示文字。适配器按精确提示节点的当前边界聚焦，填写使用编辑节点或包名匹配的系统输入连接，全文回读后才发送。DeepSeek 动态发送按钮曾被无障碍节点缓存遮蔽，读操作前清缓存后同套预检通过；不使用固定坐标或手势发送。已知欢迎文案和两个关闭的模式开关均须确认，未知界面拒绝执行。该输入连接路径要求 Android 13 及以上。

2026-10-22 补充：一条豆包新任务已通过旧版回答长图生成、服务端私有 OSS 上传及原详情读取；已确认的豆包新版、元宝浅色样式仍为本机预览。元宝本轮较长搜索回答仍受未知分段标注事件阻塞，不能沿用旧快速文本成功结论。长图接入见 [移动端回答长图](mobile-answer-longshot.md)，后续执行器任务可使用 [接手提示词](android-rpa-next-agent-prompt.md)。

## 已完成与验证边界

GEO 创建手机监测任务后，手机可以自动领取、准备采集、打开选定的豆包或元宝、进入新对话、覆盖填写问题、核对全文、单次发送、等待完整回答并幂等回传。发题逻辑位于安卓 APK，Python 不参与该流程。

元宝 2.88.0 已加入同一执行状态机的无障碍适配，先通过不发送预检：新建空对话、快速模式、输入框全文回读、发送按钮识别及草稿清空。随后使用本机调试后端创建一条新任务，APK 自行完成输入、单次发送、实时采集及回传，约19秒内 task/job/batch 全部完成，原回答详情200。56事件、164原始码点、空推理及0来源，`yuanbao-chat-sse/2`；问题、正文、推理、来源、decoder、包名和session/exchange与GEO逐字段一致。屏幕只有一条本次问题，可见正文122码点；原始正文额外保留两个 `mark_underline` 标注占位，未改变现有解码语义。

2026-10-21 真机一次完整验证：原“立即检测”入口创建1条手机任务，约33秒内任务和批次完成；本次回答为 `unique_complete`，117事件、715 Unicode码点正文、12条来源，`doubao-chat-sse/2`。手机与GEO保存的正文、推理、来源、问题、provider、decoder及session/exchange身份逐字段相同。测试期间电脑仅创建任务、冷启动目标应用及读取证据，没有代替 APK 输入或点击发送。

验证条件：Android16手机保持解锁、豆包15.0.0已登录本地CA测试版、移动数据、HTTPS采集和本次UDP443阻止。普通官方原版、公司Wi-Fi、锁屏、杀进程恢复及长期无人值守均未据此验收。正常抓包源码不包含本机千问ECH及文心私有缓冲条件，不能用普通构建覆盖后假设六平台实时采集能力保持。

## 代码入口

| 文件 | 职责及扩展点 |
| --- | --- |
| `app/src/main/java/com/packetcapture/execution/PhoneExecutor.kt` | 纯状态与动作接口；`PhoneScreen`、`PhoneAction`、共用阶段决策及平台目录，可用合成数据测试 |
| `app/src/main/java/com/packetcapture/execution/MonitorAccessibilityService.kt` | 系统无障碍服务；切到主线程取新节点、按包名/资源ID识别控件，使用 `ACTION_SET_TEXT` 和 `ACTION_CLICK` |
| `app/src/main/res/xml/monitor_accessibility.xml` | 监听 `com.larus.nova` 与 `com.tencent.hunyuan.app.chat`；新增平台需同步范围和用户可见说明 |
| `app/src/main/java/com/packetcapture/scheduler/AndroidScheduler.kt` | 平台就绪过滤、领取、自动准备、执行阶段持久化、回答读取及上传；自动模式仅领取选定平台 |
| `app/src/main/java/com/packetcapture/scheduler/SchedulerJournal.kt` | Keystore加密任务日志、发送边界、原采集边界、后续session及outbox |
| `app/src/main/java/com/packetcapture/ui/workbench/SchedulerPanel.kt` | 自动发题开关、系统无障碍设置入口、不发送问题的界面预检、实时状态 |
| `app/src/main/java/com/packetcapture/ui/workbench/WorkbenchScreen.kt` | 平台和操作能力展示；扩展平台后同步展示范围 |
| `app/src/test/java/com/packetcapture/execution/DoubaoExecutionTest.kt` | 控件就绪、全文匹配、锁屏/错误应用及不确定发送等决策测试 |
| `app/src/test/java/com/packetcapture/scheduler/AndroidSchedulerTest.kt` | 自动准备、回答关联、幂等上传和发送前持久化/恢复不重发等调度测试 |

`llm` 继续只负责回答语义，`capture` 继续只处理网络；新增界面适配不应修改已有解码器或降低完整性要求。`clients/python/packetcapture_client.py` 是只读检查工具。

## 当前执行约束

- 默认关闭自动模式。开启后只领取选定平台，并要求其已安装、被选为HTTPS采集目标、目录有完整样本且无障碍服务已连接。启动已授权VPN；豆包本次临时阻止UDP443，元宝本次关闭UDP443阻止，原设置不变。
- 流程为 `launch → opening → editing/verifying → send_started → sent`；阶段操作拒绝则进入 `blocked`。`PhoneAction` 的等待动作不产生操作。
- 在确认新对话后才覆盖填写；读取全文与任务问题完全一致、发送控件可用且未见相同已提交问题时才允许发送。禁止追加粘贴、模糊匹配或固定坐标作为生产发送逻辑。
- 点击发送之前先加密落盘 `send_started`。`send_started/sent/blocked` 不再点击发送；进程重启或结果不确定只读取已有采集。旧日志无执行阶段时按人工模式处理，不因更新自动重发。
- 自动任务4分钟未完成，按原结果协议保存并提交失败。暂停轮询不会暂停租约；租约失效不重新入队。开始实测前保证无旧活动任务。
- `AnswerReader` 按provider、原文、设备时间和采集session筛选；只有唯一完整回答能成功回传，重复、截断、未知协议或失败不能选最新一条充数。outbox服务端确认后才清除。

## 继续其他平台的步骤

1. 先读根 `AGENTS.md`、[手机调度](android-scheduler.md)和对应平台实测文档；本机再读忽略的 `HANDOFF-ANDROID-GEO-TESTING.md` 获取设备、安装包、私有构建入口及真实证据。
2. 在不发题条件下核实目标包、版本、登录、屏幕和网络；只读观察新对话入口、编辑框、发送控件及已提交问题的实际节点。不要将其他平台的控件ID照搬过来。
3. 在 `execution` 内加入该平台适配。可将控件规则按provider分开，让调度按provider选择执行器；复用现有阶段和持久化规则，避免复制一套领取、续租或上传。当前接口和实现包含豆包假设，不能只添加provider字符串就宣称支持。
4. 同步修改调度领取过滤、自动执行开关、无障碍监听包名和界面能力说明。其他平台的CA、ECH、私有缓冲及UDP策略独立核实，不能统一照搬豆包网络设置。
5. 先完成不发送的预检：新对话识别、覆盖填写、回读全文、识别发送按钮、清空草稿。为真实风险补合成状态测试，重点验证发送前落盘、恢复不重发及原文不一致不发送。
6. 每个平台使用一条明确授权的新任务和冻结问题，发送一次。记录手机实时回答及GEO终态，逐字段比较结果。已有六平台采集样本可复用，不为确认旧解码器重复发题。
7. 收尾停止测试轮询/VPN、关闭临时ADB导出；保留绑定、CA、signer、目标登录和会话。更新本交接及对应平台实测，不扩大未经验证的支持范围。

后续可先做DeepSeek普通文本界面，再处理千问、文心和Kimi各自的模式及传输前提；顺序可按用户需要调整。

## 构建与测试

普通源码可以运行 `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease`；元宝阶段App单测27项通过。六平台本机测试包必须使用本机交接中记录的私有init构建入口，并按手机原signer覆盖安装；签名材料和APK不提交Git。

不要在有效任务持有租约时运行instrumentation或覆盖安装：它们会影响主进程和无障碍连接。无障碍预检使用App任务页按钮；此前instrumentation环境的服务连接失败不等于正常App运行失败。

## 未完成事项

- 文心、Kimi的APK自动发题尚未验收。已有采集/回传证据不能替代这项验证。
- 元宝原始正文保留协议标注占位，展示层清理尚未实现；快速模式以外的界面与模式未验收。
- 公司Wi-Fi下IPv6路由缺失及移动网络切换的可靠回退。移动数据成功不能证明Wi-Fi已修复。
- 手机直连可访问的GEO HTTPS服务。本机仍用USB reverse的localhost桥，当前无需电脑执行RPA，但仍依赖电脑提供GEO连接。
- 锁屏、后台被杀、重启恢复、长期保活、规模并发和网络故障矩阵。
- 新版平台长图自动接入、搜索摘要／追加建议／正文图片采集，以及安卓 STS 直传；旧版豆包服务端上传的通过范围见本文顶部及长图文档。
- 自动处理登录/验证码及未知界面；不确定时继续保留失败或人工处理边界。

根README、[架构](architecture.md)和[手机调度](android-scheduler.md)为公共能力说明；设备serial、任务ID、APK哈希和私有证据路径只放本机交接与忽略的artifacts。全部改动仍在工作区，未提交或推送。
