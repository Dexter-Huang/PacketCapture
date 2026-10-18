# Packet Capture

- 本地 Windows 命令使用 PowerShell 7（`exec_command` 显式 `shell: "pwsh"`）。
- 修改回答解析、平台目录或自动化接口前，读 [架构](docs/architecture.md) 和 [结果接口](docs/automation-v1.md)。`llm` 解码语义，`capture` 处理传输；单元测试使用合成数据。
- 调试 TLS、流式抓包或新平台前，读 [抓包开发经验](docs/capture-development-notes.md)；千问还需读 [实测前提](QIANWEN-CAPTURE-TEST.md)，普通构建的解码器与私有 ECH/UNet 测试条件分开核实。本机接手豆包/千问还要读 `HANDOFF-DOUBAO-QIANWEN.md`（忽略文件，如缺失则按公共文档重新核实设备状态）。
- 真机操作前核实设备 serial、已安装版本及开关；保留原 CA、signer、登录与历史。破坏性替换需目标明确的用户授权。测试问题由用户发送。
- 接手 DeepSeek 时读 [实测记录](DEEPSEEK-CAPTURE-TEST.md)；本机还读 `HANDOFF-DEEPSEEK.md`（忽略文件），按已安装 signer 覆盖更新并保留登录，实际搜索与深度思考仍待验证。
- HAR、APK、真实事件和签名材料放在忽略的 `artifacts/`；公共文档及日志保持脱敏。支持状态只提升到真实样本验证的范围。
