# DeepSeek 实时接口抓包实测

验证日期：2026-10-17（Asia/Shanghai）。非 Root Android 16 ARM64 真机，DeepSeek `com.deepseek.chat` 2.6.2（code 281），抓包工具 `1.1.0-qw-ech-probe`（code 8）。本次验证本地 CA 测试版的一条实时文本回答；已接入平台目录及 `deepseek-chat-sse/1` 解码器，使用统一回答 JSON/ADB v1 接口。

## 三轮对照

| 轮次 | 条件 | 用户观察及证据 |
| --- | --- | --- |
| A 原版透传 | HTTPS 解密关、UDP443 阻止关 | 正常回复；50 条连接、0 HTTP 明文，确认透传基线 |
| B 原版解密 | 只开启 HTTPS 解密，重启目标应用 | 提示网络异常；112 条连接、0 HTTP 明文；17 条 CERTIFICATE_UNKNOWN，其中主域名 8 条，失败发生在目标应用侧 TLS 握手 |
| C 本地 CA 测试版 | HTTPS 解密开、UDP443 阻止关、直接上游，仅选 DeepSeek | 正常回复；取得对应当前问题的完整实时 SSE，答案与 UI 及后续历史结果逐字一致 |

各轮问题均由用户发送。C 轮解密 5 条连接、产生 26 个 HTTP 条目。DeepSeek 域名证书拒绝为 0；第三方遥测及风控域名仍有 12 条证书拒绝，不能称为整个应用所有连接均解密。

## 最小测试包改动

原版正式构建的网络信任配置只接受系统 CA，用户 CA 仅位于不生效的 debug-overrides。测试包在同一配置中增加 `deepseek.com` 及其子域名的 system+user 信任，使用手机已有抓包 CA。

1020 个非签名 payload 中只改变 1 份网络信任 XML，其余 1019 项逐字节不变；Manifest、DEX、原生库及资源表保持原值。保留证书链和域名校验，没有更改 UDP、ECH、原生验证器或应用实体加密。签名、ZIP 完整性和 16 KiB 对齐均验证通过。

测试签名与官方原版不同，用户明确授权卸载原版并接受本机数据丢失后才安装；随后由用户重新登录。此结果只适用于已验证的版本和设备条件，不能推断官方原版只装用户证书即可解密。原版 APK、测试 signer 和构建材料仅保存在忽略的 artifacts 目录。

## 实时回答验证

| 项目 | C 轮已验证结果 |
| --- | --- |
| 实时请求 | `POST https://chat.deepseek.com/api/v0/chat/completion`，当前 prompt 精确对应测试问题 |
| 模式 | model_type=default、thinking_enabled=false、search_enabled=true；实际 search_triggered=false |
| 响应 | HTTP/2 200，text/event-stream; charset=utf-8，1719 字节，无 Content-Encoding |
| 生命周期 | COMPLETE，保存字节等于观察字节，未截断、不在接收中、无错误，末帧分隔完整 |
| 事件 | 36 个：ready 1、update_session 2、message 30、auto_tts_capability 1、title 1、close 1 |
| 答案 | 初始 RESPONSE 文本 2 码点，加 27 次 APPEND，得到 56 个 Unicode 码点 |
| 完成 | response/status SET FINISHED，随后 close 且 auto_resume=false |
| 独立核对 | 实时流答案与保存的 UI 文本逐字相同，也与同会话、匹配消息身份的历史最终答案逐字相同 |

初始 `v.response.fragments` 已含答案开头。后续显式给出 `p=response/fragments/-1/content`、`o=APPEND`，随后仅含 v 的事件沿用前述路径与操作。BATCH 中的 token 使用量和 quasi_status、会话时间、标题和 TTS 控制事件不拼入答案。ready 的请求/响应消息身份与初始响应、历史结果对应。

历史接口仅用于独立核验，答案来源始终是本轮实时 SSE。当前没有搜索来源；未验证实际搜索、深度思考、附件、多 fragment、替换操作、恢复流或其他版本。`verify_stream.py` 仅用于独立核验已知样本；生产解码器 `DeepseekDecoder` 已另行实现并注册，目录为 verified_sample。未知事件、补丁、模式及实际搜索不会被报告为完整。

## 本机证据与复查

原始资料位于忽略的 `artifacts/deepseek-stream/`，可能含登录态，不提交 Git：

- 三轮 HAR：deepseek-baseline-A.har、deepseek-decrypt-B-failed.har、deepseek-live-C-domain-ca.har。成功样本在抓包仍运行时导出。
- deepseek-sse-success/：原始 SSE、事件、答案和核验报告。
- ca-C-answer.xml、deepseek-answer-C.png：手机结果证据。
- build-ca-test.ps1、build_ca_test.py、ca-build/verification.json：离线构建与逐文件差异校验。
- verify_stream.py：只读取保存的 C 轮 HAR/UI，核验问题、身份、事件结束、正文完整性及答案一致性，不发送请求。

接入后新增 13 项合成协议测试，覆盖初始文本/继承操作、身份、完成、错误、未知协议、正文与传输边界、超过 UI 窗口、精确筛选及导出隐私。仓库 JVM 测试、5 项 Python 测试及标准 Release/Android 测试 APK 构建通过；元宝、豆包、千问和 DeepSeek 的私有真实样本离线回放均通过。DeepSeek 回放得到 complete、36 事件、56 码点、无诊断，与已核验原文逐字一致。

随后以抓包 App 原 signer 覆盖安装含四个解码器的本地构建，保留原千问定向 ECH 实验条件。读取手机已有 C 轮会话，按 provider=deepseek 和精确 exchangeId 经真实 ADB 接口返回 unique_complete、deepseek-chat-sse/1、36 事件、无诊断；答案与保存的实时 SSE 原文逐字一致。平台目录也返回 DeepSeek verified_sample。此阶段未发送新问题，证明的是设备上已有证据的自动解析/读取，不扩展其他模式的实时采集范围。

测试包构建、实时采集、离线回放和后续手机结果接口验证分别记录。结束后 VPN 停止，原目标千问、HTTPS 解密开、UDP443 阻止关等设置保留；ADB 导出恢复关闭，关闭后的读取被拒绝。原 CA、抓包历史及测试版登录保留。
