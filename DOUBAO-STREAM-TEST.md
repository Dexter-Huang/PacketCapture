# 豆包 Android 流式抓包测试

2026-10-17（Asia/Shanghai），实体 Android 16 手机，豆包 15.0.0。

**已验证实时 SSE 明文捕获成功。** 用户在抓包运行期间发送带 `DB_STREAM_E` 标记的问题并确认正常回复。答案来自该次实时响应，未使用历史接口替代。

## 验证结果

- `POST https://api5-normal-gl.doubao.com/chat/completion`，请求正文含 `DB_STREAM_E`。
- 开始时间 15:54:48.754，耗时 8961ms；HTTP/2 200，`text/event-stream`。
- 完整响应 17984 字节，`COMPLETE`，无截断；56 个 SSE 事件，包含 42 个 `CHUNK_DELTA` 文本增量。
- 三个 `SSE_REPLY_END` 的 `end_type` 依次为 1、2、3，最后一个事件正常结束响应。
- 按顺序合并初始文本、文本块追加与增量，得到 385 字符 Markdown 答案；去除 Markdown 标题标记和空白排版差异后，与手机显示答案完全一致。
- 本轮 HAR 中证书拒绝错误为 0。导出发生在抓包仍运行时。

## 复用条件与当前状态

本次成功使用本地测试版 `artifacts/doubao-stream/doubao-15.0.0-stream-ca-test.apk`。官方版未通过同样的解密测试，不能据此宣称官方版直接可抓。

该测试版选择原有 Android 证书验证器，并通过 TTNet 现有测试根机制加载原抓包 CA；保留链和域名校验。同时将 TTNet 的 `encrypt_config_v1.enabled` 固定为 false，关闭 HTTPS 之外的应用正文加密。原生库累计只替换三条 ARM64 指令，验证器本身和加解密函数未修改。

复测时选择豆包，开启「HTTPS 解密」和「阻止 UDP 443（测试回退）」，冷启动豆包后发送新问题。此次只验证了上述 TCP 回退条件，未验证 QUIC 明文采集。

本轮完成后已停止抓包；HTTPS 解密保持开启，UDP 443 阻止恢复关闭。登录、原 CA、元宝和抓包历史保留，未再次卸载或清数据；最终测试版 Manifest 非 debuggable。

## 本地证据

以下文件均在本机忽略目录，真实 HAR 和 SSE 元数据含设备、账号及会话信息。

- `artifacts/doubao-stream/doubao-live-E-stream-ca.har`：完整运行中导出。
- `artifacts/doubao-stream/doubao-sse-success/SSE返回报文.txt`：原始 SSE 响应。
- `artifacts/doubao-stream/doubao-sse-success/events.json`：结构化事件。
- `artifacts/doubao-stream/doubao-sse-success/reply.md`：从实时流拼接的答案。
- `artifacts/doubao-stream/doubao-sse-success/verification.json`：验证摘要。
- `artifacts/doubao-stream/doubao-answer-E.xml` / `.png`：手机答案对照。
- `artifacts/doubao-stream/stream-build/verification.json`：APK 逐文件验证。
- `artifacts/doubao-stream/experiment.md`：失败轮次、定位过程和每轮证据。

HAR SHA256：`300dae1a2fe11bd5350bc5e4fb1e45bbe0de798739837a0f928c5c0b9aed1666`。

成功测试 APK SHA256：`86f22e97e65c58ccb005f9f2e5e7d6e79d941aa9ac00c7088089f80ee554ea1f`。

APK 签名、16KiB 对齐、ZIP 完整性与全部 41701 个非签名 payload 的差异验证通过。只变更 `classes32.dex`、`lib/arm64-v8a/libsscronet.so` 与 release 网络信任 XML，其他 41698 项保持一致。DEX 改动另有规范化撤销验证。

本地重新核验命令：`python artifacts/doubao-stream/verify_stream.py`。此命令只检查已保存证据，不向豆包发消息。

## 项目解码器

`llm` 已新增并注册 `DoubaoDecoder`，版本 `doubao-chat-sse/1`，目录状态为 `verified_sample`。它提取当前问题并保留初始文本、追加文本块和 `CHUNK_DELTA`，检查文本结束及三级终止事件；未知协议、错误和不完整正文不会被报告为完整。实测推荐视频不计为搜索引用，推理与搜索来源尚未验证。

13 项新增合成测试通过；真实 HAR 在 Kotlin 解码器中离线回放得到 `complete`、56 个事件、385 个 Unicode 码点、无诊断，与保存的实时答案一致。摘要在 `artifacts/doubao-stream/llm-replay-verification.json`。成功明文响应仍有 `x-tt-e-b: 1`，已用回放验证不能仅凭该头判定正文加密。

上述为接入阶段记录。2026-10-20 已通过同 signer 的 `1.1.0-qw-ech-probe` 真机及隔离 GEO 验证：单次提问自动触发搜索，完整 47 事件、134 码点、38 条显式搜索来源。`doubao-chat-sse/2` 新增观察到的搜索加载生命周期及结果来源；正文与 UI 规范化一致，原 GEO 详情 200、正文和来源 title/url 顺序一致。推荐视频与明确搜索视频来源分别处理，推理仍未验证。修复复用同次流与有效任务，没有重发；范围及收尾见 [手机调度记录](docs/android-scheduler.md#验证记录2026-10-20)。通用经验见 [开发记录](docs/capture-development-notes.md#10-豆包三个独立障碍及解码结果)，本机交接入口为 `HANDOFF-DOUBAO-QIANWEN.md`。

## APK自动发题补充（2026-10-21）

Android16、豆包15.0.0已登录本地CA测试版、移动网络及UDP443阻止条件下，PacketCapture无障碍执行器完成新任务自动打开新对话、覆盖输入、全文核对和单次发送；完整实时回答自动回传，原任务与批次约33秒完成。117事件、715码点正文、12来源，手机与GEO结果逐字段一致。未替换豆包安装包，不表示公司Wi-Fi、官方原版或长期后台执行通过。接手和扩展见[执行器交接](docs/android-executor-handoff.md)。
