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
    "decoder": "yuanbao-chat-sse/1",
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

`selectionStatus` 为 `none`、`ambiguous` 或 `unique_{status}`。仅单条 `unique_complete` 可视作采集成功。`none` 可能表示尚无 SSE、未解密、归属未知或筛选不匹配，应核对原始证据。`complete` 要求已识别结束事件、完整 HTTP 生命周期、未截断可读正文、有效问题、非空回答、无错误及未知事件；其他情况下返回 `incomplete/failed/unsupported`。部分答案用于诊断，不能当作完整结果。

不导出 Cookie、Authorization、完整请求头或完整请求体。问题、回答和来源仍是对话数据，按 GEO 数据策略保存。来源 `collected` 仅说明有来源 URL，不代表引用穷尽；无来源记 `not_collected`。HAR 仍通过界面手动导出，可能含原始登录凭据，勿提交 Git。

## Python 使用

Python 3.10+，仅标准库。在项目根目录运行，设备序列号必填：

```powershell
python clients/python/packetcapture_client.py --serial YOUR_SERIAL status
python clients/python/packetcapture_client.py --serial YOUR_SERIAL sessions
python clients/python/packetcapture_client.py --serial YOUR_SERIAL answers --session SESSION_ID --provider yuanbao --exchange-id EXCHANGE_ID --require-complete
```

支持 `--prompt-file`（准确 UTF-8 文件，不删除结尾换行）与 `--started-after`。输出重定向请使用 UTF-8。界面的当前会话和历史会话也支持「回答 JSON」导出，不需开启 ADB。

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

2026-10-17 验证：81 项 JVM 测试、5 项 Python 测试通过，Release APK 与 Android 测试 APK 构建成功。用本机已有元宝 SSE 回放得到 383 个事件、1393 个 Unicode 码点、15 个来源；拼接文本与此前保存的回答一致（比较时统一文本文件换行）。真实报文和回放脚本留在被忽略的 `artifacts/`，不提交。新版本 ADB 接口及设置 UI 尚未在手机上执行验证。
