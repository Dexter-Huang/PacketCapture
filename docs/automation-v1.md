# ADB 回答接口 v1

安装 v1.1.0，在设置开启「ADB 结果导出」，在界面正常启动抓包。接口只读，不授权 VPN、不改设置、不发送问题、不自动启停。默认关闭；仅本应用 UID、ADB shell UID 2000 和 root UID 0 可读取，其他应用不能读取。

所有资源用 `content read` 返回 UTF-8 JSON，根字段 `schemaVersion=1`。Android 错误可能是退出码 0 的文本，客户端必须校验 JSON 和版本。

| URI | 内容 |
| --- | --- |
| `content://com.packetcapture.automation/v1/status` | `deviceTime`（设备 epoch 毫秒）、版本、采集状态及平台目录 |
| `content://com.packetcapture.automation/v1/sessions` | 本地会话元数据 |
| `content://com.packetcapture.automation/v1/sessions/{sessionId}/answers` | 指定会话的回答候选 |

`answers` 支持 `provider`、`prompt`（全文精确匹配，不裁剪空白）、`startedAfter`（设备 epoch 毫秒，含边界）、`exchangeId`。参数须 URL 编码，建议用客户端。未知/重复参数报错。不存在的会话、读取错误或超时返回 `{"schemaVersion":1,"error":"export_unavailable"}`，不暴露路径或私人报文。

返回示例为人工合成数据：

```json
{
  "schemaVersion": 1,
  "sessionId": "example-session",
  "selectionStatus": "unique_complete",
  "results": [{
    "schemaVersion": 1,
    "source": "android_capture_sse",
    "provider": "yuanbao",
    "sessionId": "example-session",
    "exchangeId": "example-exchange",
    "packageName": "com.tencent.hunyuan.app.chat",
    "startedAt": 1792200000000,
    "endedAt": 1792200010000,
    "decoder": "yuanbao-chat-sse/2",
    "prompt": "示例问题",
    "answer": "示例回答",
    "reasoning": "",
    "searchSources": [],
    "referenceCaptureStatus": "not_collected",
    "status": "complete",
    "diagnostics": [],
    "eventCount": 12
  }]
}
```

`selectionStatus` 为 `none`、`ambiguous` 或 `unique_{status}`。仅单条 `unique_complete` 可视作采集成功。`none` 可能表示尚无匹配协议、未解密、归属未知或筛选不匹配，应核对原始证据。`complete` 要求已识别结束事件、完整 HTTP 生命周期、未截断可读正文、有效问题、非空回答、无错误及未知事件；其他情况下返回 `incomplete/failed/unsupported`。部分答案用于诊断，不能当作完整结果。

Kimi 使用相同 v1 URI、DTO 和精确筛选，provider 为 `kimi`、decoder 为 `kimi-chat-connect/1`。3.1.4 本地 CA 测试版新对话的 Connect JSON 文本与独立 `reasoning` 已验证；该模式无来源时为 `not_collected`。结束还要求 Connect done 与结束帧，未知工具流不报告成功。`eventCount` 包含心跳及结束帧；`source=android_capture_sse` 是保留的 v1 兼容标识，不能用它推断实际传输为 SSE。见 [Kimi 实测](../KIMI-CAPTURE-TEST.md)。

不导出 Cookie、Authorization、完整请求头或完整请求体。问题、回答和来源仍是对话数据，按 GEO 数据策略保存。来源 `collected` 仅说明有来源 URL，不代表引用穷尽；无来源记 `not_collected`。HAR 仍通过界面手动导出，可能含原始登录凭据，勿提交 Git。

## Python 使用

Python 3.10+，仅标准库。在项目根目录运行，设备序列号必填：

```powershell
python -X utf8 clients/python/packetcapture_client.py --serial YOUR_SERIAL status
python -X utf8 clients/python/packetcapture_client.py --serial YOUR_SERIAL sessions
python -X utf8 clients/python/packetcapture_client.py --serial YOUR_SERIAL answers --session SESSION_ID --provider yuanbao --exchange-id EXCHANGE_ID --require-complete
python -X utf8 clients/python/packetcapture_client.py --serial YOUR_SERIAL answers --session SESSION_ID --provider doubao --exchange-id EXCHANGE_ID --require-complete
```

千问也使用同一接口，例如 `answers --session SESSION_ID --provider qianwen --exchange-id EXCHANGE_ID --require-complete`。`decoder=qianwen-chat-sse/1` 目前验证 7.4.7.3058 本地测试版快速文本模式；答案保留原始 Markdown 和交互占位，来源单独输出，推理为空。参考资料 `collected` 不表示逐句引用映射；前提见 [千问实测](../QIANWEN-CAPTURE-TEST.md)。

DeepSeek 使用同一 v1 DTO、精确筛选和完成规则，provider 为 `deepseek`，decoder 为 `deepseek-chat-sse/1`。已验证 2.6.2 本地 CA 测试版默认文本、未实际触发搜索的回答；来源为空、推理为空，其他模式返回 unsupported。命令可使用 `answers --session SESSION_ID --provider deepseek --exchange-id EXCHANGE_ID --require-complete`。TLS 前提与实测范围见 [DeepSeek 实测](../DEEPSEEK-CAPTURE-TEST.md)。

文心使用同一接口，provider 为 `wenxin`，decoder 为 `wenxin-chat-sse/1`，普通文本回答和显式来源已验证，推理为空。2026-10-20 在 5.20.0.11 本地 CA 测试版及私有目标域名 3 秒交付缓冲条件下取得三份真实 HTTP 完整样本，返回 `unique_complete`；普通构建不含该缓冲。旧提前结束样本仍返回 `unique_failed`、诊断答案/来源及 `transport_incomplete`、`transport_error`、`body_unavailable_or_limited`，`--require-complete` 必须继续拒绝。重复问题仍返回 ambiguous，须绑定 exchangeId；前提和限制见 [文心实测](../WENXIN-CAPTURE-TEST.md)。

2026-10-19 已在手机同 signer 更新后，通过真实 ADB 接口核对已有三条文心记录：问题、答案与 6/8/14 条来源逐项匹配离线结果，精确问题筛选、空格不匹配、重复问题歧义与失败结果拒绝均通过。未发送新问题，结束后关闭导出并核对读取被拒绝，CA 和历史保留。

Windows 重定向结果时使用上面的 `python -X utf8`，避免本机代码页改变中文；程序内读取可直接将返回对象以 UTF-8 写入文件。

支持 `--prompt-file`（准确 UTF-8 文件，不删除结尾换行）与 `--started-after`。输出重定向请使用 UTF-8。界面的当前会话和历史会话也支持「回答 JSON」导出，不需开启 ADB。

豆包使用同一 v1 DTO 和筛选规则，`decoder=doubao-chat-sse/2`。已验证 15.0.0 本地测试版普通文本及一次自动搜索回答；明确搜索结果卡片提取 text_card/video_card 的有效 URL、标题及显式 index，按 URL 首次出现去重，记 collected。推荐视频块仍不当搜索来源；无来源样本记 not_collected，推理为空。已完成 TLS 解密但实体仍加密时不会输出成功答案。具体前提见 [豆包实测](../DOUBAO-STREAM-TEST.md)。

元宝当前 `decoder=yuanbao-chat-sse/2`，支持已验证的标注快照，保留原始正文及标注占位，未知结构或可见正文变化仍拒绝完成。2026-10-19 至 20 日三个平台的 GEO 链路范围见 [手机调度记录](android-scheduler.md#验证记录2026-10-20)。

调用示意：

```python
from packetcapture_client import PacketCaptureClient

client = PacketCaptureClient(serial="YOUR_SERIAL")
before = client.status()
assert before["capture"]["phase"] == "CAPTURING"
session_id = before["capture"]["sessionId"]
started_after = before["deviceTime"]
# 先持久化 taskId、attemptId、session_id、started_after、prompt 和发送边界。
# 手机端执行一次提问；客户端本身不发送。
batch = client.answers(session_id, provider="yuanbao", prompt=prompt,
                       started_after=started_after)
answer = client.require_complete(batch)
# 持久化命中的 exchangeId 后映射 GEO 结果。
```

执行器可在截止时间前每秒轮询同一条件；失败或未完成只允许继续收集，不代表可以重发。多个候选保留为歧义，转人工或用已持久化的请求 ID 关联。引用从 `searchSources` 映射，不从答案正文猜测完整引用列表。

接口按当前正文重新解析，适合开发轮询，不是高频订阅。大量历史记录请用精确 `exchangeId` 限定。导出 30 秒超时，客户端默认 40 秒；总存储 500 MiB、单正文 5 MiB，截断不会被报告为完整。

## 验证

```powershell
.\gradlew.bat :core:test :llm:test :capture:testDebugUnitTest :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease :app:assembleDebugAndroidTest --max-workers=2
python -m unittest discover -s clients/python -p 'test_*.py' -v
```

自动测试覆盖精确筛选、重复候选、完整性、未知协议、远端错误、来源 URL、超过 UI/事件窗口的回答、调用 UID 和客户端命令引用及错误处理。真机安装、ADB 权限、设置开关和各平台证书信任另外验证；构建成功不代表这些已经通过。

2026-10-17 验证：千问接入后 106 项仓库 JVM 测试、5 项 Python 测试通过；本地定向 ECH 探针另有 1 项测试。普通 Release 与保留定向探针的本地 Release 均构建通过。真实报文离线回放三平台均 `complete`、无诊断：元宝 383 事件/1393 码点/15 来源，豆包 56 事件/385 码点，千问 10 事件/192 码点/7 来源。千问答案与独立保存的协议状态逐字相同，来源 URL 顺序一致。真实报文和回放脚本只在忽略的 `artifacts/`。

千问接入阶段，手机用原 signer 覆盖安装含三个解码器的 `1.1.0-qw-ech-probe`（code 8），保留千问抓包所需的本地定向 ECH 探针。对已保存的千问成功会话通过真实 ADB 接口得到 `unique_complete`，问题、答案及 7 个来源 URL 精确核对通过；精确问题筛选、附加空格不匹配、导出开关开启/关闭与关闭后拒绝读取均实测。未发送新问题；该验证证明设备上已有证据的解析/读取，不扩展到其他聊天模式。已恢复 ADB 导出关闭、VPN 停止，原 CA、历史及目标应用登录保留。标准构建不包含私有 ECH 探针，千问实时抓包仍须满足实测记录中的本地条件。

DeepSeek 接入后新增 13 项合成测试，标准构建的 119 项仓库 JVM 测试与 5 项 Python 测试通过；标准 Release/Android 测试 APK 构建通过，保留千问探针的本地 Release 构建与额外范围测试也通过。四平台真实样本离线回放均 complete、无诊断。手机同原 signer 更新为含四个解码器的构建，读取已有 DeepSeek 会话得到 unique_complete、deepseek-chat-sse/1、36 事件，答案与实时 SSE 原文逐字一致；平台目录识别 deepseek。未另发新问题，ADB 导出已恢复关闭并核对拒绝读取，VPN 停止。
