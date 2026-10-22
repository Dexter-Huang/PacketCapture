# Changelog

## 安卓执行器里程碑 — 2026-10-21（未发布）

- 新增豆包可选无障碍执行器、界面预检、精确覆盖输入和发送前持久化边界；领取后自动准备、等待完整回答并幂等回传，无需手动点击准备或核对。
- 指定豆包15.0.0测试版、Android16和移动网络下，APK自动发题与回传约33秒完成，715码点正文及12条来源与GEO一致。
- 元宝2.88.0快速文本已复用同一调度器完成APK自动发题及实时回传；先不发送预检，再一条新任务约19秒完成，56事件及164原始码点与GEO一致。原始正文保留两个标注占位。App单测27项通过；其余四平台自动发题、公司Wi-Fi兼容和长期后台运行仍待验证，见[执行器交接](docs/android-executor-handoff.md)。

## Unreleased — 2026-10-19

- 新增 Kimi 3.1.4 新对话 Connect JSON 解码器 `kimi-chat-connect/1`，从原始帧分别提取文本与推理；校验消息身份、连续序号和双重终止，未知工具流仍拒绝完成。正文层仅为该 MIME 保留解压原字节；v1 结果契约保持兼容。真实样本依赖已授权安装的本地 CA 测试版。
- Kimi 完成隔离 GEO 安卓立即监测链路，推理摘要兼容修复复用同次流回传；此前五个平台成功证据只读复核，没有重复发题。六个平台仍受各自测试包、模式与传输条件约束，App 无障碍执行器未实现。
- 2026-10-20：元宝解码升级 `/2`，仅接受与已有可见正文一致的标注快照；豆包解码升级 `/2`，支持已验证的搜索加载生命周期和明确结果卡片来源。新增合成回归，未知结构、截断和缺失终止仍不报告成功。
- DeepSeek、元宝、豆包完成隔离 GEO 真机单次提问链路；元宝/豆包修复复用原流回传，手机操作由用户明确授权的电脑 ADB 完成，App 无障碍执行器与无人值守后台尚未实现。
- 新增文心 5.20.0.11 `wenxin-chat-sse/1` 解码器，提取普通文本问题、Markdown 增量与显式来源；校验新会话/续聊身份、连续序号及终止标记，接入现有回答 JSON 与 ADB 接口。旧客户端取消样本仍为失败；2026-10-20 在本地 CA 与私有目标域名 3 秒交付缓冲条件下取得真实 HTTP 结束，并完成隔离 GEO 单次提问链路，目录提升 verified_sample。普通构建不含该缓冲，完整性门槛未放宽。
- 新增文心合成协议测试及 HTTP/2 结束帧测试；真实报文与本机实验材料保存在忽略目录。

## v1.1.0 — 2026-10-17

- 新增独立 JVM `llm` 模块：统一平台 ID、回答 DTO、精确筛选和完整性判断；元宝原生 SSE 解码与 VPN/TLS 解耦。
- 元宝解析区分文本增量、来源、控制帧和终止事件；错误、截断、未结束和未知协议不会被报告为完整回答。
- 新增豆包 15.0.0 `/chat/completion` SSE 解码器：提取当前问题，按初始文本、追加块和增量还原答案，核对消息身份及三级结束事件；真实明文 HAR 离线回放通过。该样本使用本地测试版及 TCP 回退，官方版直接解密未通过验证。
- 新增 DeepSeek 2.6.2 `deepseek-chat-sse/1` 默认文本解码器和平台预设：保留初始 fragment、继承路径/操作的 APPEND，核对消息身份、FINISHED、正常 close 及完整性；真实 SSE 回放与 UI 答案一致。实际搜索、深度思考和其他未验证协议不报告成功。
- 设置新增大模型应用预设；元宝、豆包、千问、DeepSeek 标为已有实时样本，Kimi、文心一言标明解析待验证，不承诺解密及版本兼容。
- 按千问 7.4.7.3058 真机核对修正预设包名为 `com.aliyun.tongyi`；本地测试版及定向抓包探针取得完整 `/api/v1/chat` SSE、7 条参考资料且 UI 核对通过；新增 `qianwen-chat-sse/1` 解码器，保留初始文本与追加增量、独立来源、核对请求身份/序号/终止与完整性；真实回放及手机已有会话 ADB 导出验证通过，目录为 verified_sample。
- 新增会话回答 JSON 导出、默认关闭且限制调用 UID 的只读 ADB 接口、Python 标准库客户端及 GEO 对接设计。
- 将本地技能、实验抓包、APK、签名材料和设备交接排除出版本控制，纳入可构建工程源码。

## v1.0.6-native-tls — 2026-10-17

- TLS 解密后识别 HTTP 与非 HTTP 明文，保留原生 TCP 字节；新增可配置的 TLS 域名映射，连接地址与 SNI / 证书验证域名分离。
- 元宝 2.88.0 在本地测试设备的证书信任前提下取得正常逐步回复及完整 SSE。设备 APK、证书和抓包证据不随源码分发。

## v1.0.5-diagnostics — 2026-10-16

- HAR 新增 `log._connections`，保留尚未产生 HTTP 请求的连接及 TLS 失败原因，导出不受界面 200 条上限影响。
- TLS 错误显示异常类型和原因链，避免无 message 的连接关闭只显示 null，或被误判为证书拒绝。

## v1.0.4-quic-test — 2026-10-16

- 识别旧 Google QUIC Q039/Q043/Q046 的公开版本头，并尝试读取 CHLO 中的域名，补齐仅检查 QUIC v1/v2 的提示范围。
- 新增默认关闭的 UDP 443 阻止开关，用于测试应用自己的 HTTPS/TCP 回退；IPv4 / IPv6 与直连 / SOCKS5 在转发前统一判断，保留 DNS 和 TCP。
- 回退结果需在目标应用验证；未新增 QUIC 应用数据解密，也不承诺回退必定发生。

## v1.0.3-transport — 2026-10-16

- 非 HTTP TCP 透传同时保存双向原始字节流，移除普通 HTTP 读超时以保留空闲长连接。
- 原生 UDP 路径保存双向 PCAP（含 IP 头、报文边界和时间戳），适用于 DNS、疑似 QUIC 等协议分析，未新增 HTTP/3 应用数据解密。
- 新增 TCP / UDP 列表筛选、上传 / 下载详情和 BIN / PCAP 导出，保留原有配额、每秒快照和截断标记。
- HAR 通过 `log._transportStreams` 扩展保存原始传输；不伪造 HTTP 请求或生成 cURL。

## v1.0.2-sse — 2026-10-16

- SSE 响应在连接保持打开时约每秒刷新可读正文快照，支持 HTTP/1.1 与 HTTP/2。
- 响应详情新增事件与原文视图、事件计数和“跟随最新”，解析多行 data、event、id、UTF-8 和标准换行分隔。
- 保留事件原文和现有存储上限；接收中导出注明快照，进程中断恢复保留已发布的正文前缀。
- SSE 连接移除普通 HTTP 的读空闲超时，允许服务器等待期间保持连接。
- 未扩展私有长连接、WebSocket 或 HTTP/3 解密能力。

## v1.0.1 — 2026-09-28

### 中文

- 更新应用图标，提供青蓝色数据包设计及圆形、自适应和系统主题单色版本。
- 设置页新增清空抓包存储，确认后删除全部会话、连接、请求及正文，并释放数据库空间；抓包期间禁止清空。
- 导出文件名包含应用名称和导出时间，HAR、cURL 及请求 / 响应文本默认保留原始凭据和头字段。
- 请求详情使用独立页面，返回首页时保留列表位置与筛选状态，并移除首页重复的设置入口。
- 请求 / 响应 ZIP 包含请求文本、响应文本和可独立执行的完整 cURL 命令，支持内嵌文本及二进制请求体。

### English

- Replace the app icon with a teal packet-capture design, including round, adaptive, and monochrome themed icons.
- Add confirmed storage cleanup for all sessions, connections, exchanges, and bodies, reclaiming database space; block cleanup while capturing.
- Include app names and export timestamps in filenames, and preserve original credentials and headers in HAR, cURL, and request / response text by default.
- Open request details in a separate screen while preserving the home list position and filters, and remove the duplicate settings shortcut.
- Export request / response ZIP bundles with request text, response text, and a self-contained cURL command supporting embedded text and binary bodies.

## v1.0.0 — 2026-09-24

首个版本 / Initial version.

### 中文

- 通过 Android VPN 按应用抓包，无需 Root，支持 HTTP/1.1 与 HTTP/2 请求分析。
- 支持配置 CA 信任后的 HTTPS 解密、域名透传和本机 SOCKS5 代理转发。
- 提供 JSON、表单、压缩正文、Base64 解析与图片预览。
- 支持历史会话、请求筛选、HAR 1.2、cURL ZIP 和正文文本导出。
- 提供中英文 README、源码构建说明及第三方组件声明。

### English

- Capture selected apps through Android VPN without root, with HTTP/1.1 and HTTP/2 inspection.
- Decrypt HTTPS when the target app trusts the capture CA; support domain passthrough and local SOCKS5 forwarding.
- Inspect JSON, forms, compressed bodies, Base64, and image previews.
- Browse session history, filter requests, and export HAR 1.2, cURL ZIP bundles, or body text.
- Include Chinese and English READMEs, source build instructions, and third-party notices.
