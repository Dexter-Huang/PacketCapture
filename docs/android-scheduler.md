# GEO 安卓设备工作台与调度接入

## 本阶段范围

安卓 APK 是 GEO 的手机执行端。GEO 保留品牌监测配置、租户隔离、任务调度、分析与计费；手机负责串行操作平台 App、采集回答与证据。用户在 GEO 创建监测，不在手机重复配置品牌、关键词或分析规则。

本阶段实现界面和本地接入配置，不修改 GEO 仓库，也不发送网络请求或问题。已核对的 GEO 主 worktree（`dev/huangml`）有 `backend/app/rpa/` 浏览器任务调度；`rpa/android-geo-rpa/` 是独立电脑控制手机的元宝 Demo，尚无 APK 领取接口，不能把浏览器账号租约接口当作手机任务接口。

| 页面 | 本阶段可用功能 | 待接入后的职责 |
| --- | --- | --- |
| 工作台 | 设备名称、计划平台数、轮询间隔、真实安装版本、采集状态、配置入口 | 连接/接单状态、当前任务、心跳、待处理事项 |
| 任务 | 明确的未接入空态与执行流程说明 | 当前执行、待回传、需人工处理及本机执行记录；记录详情绑定 taskId/attemptId |
| 采集 | 原实时请求列表、会话历史、详情及 HAR/回答导出 | 任务证据检查和开发诊断 |
| 设置 | 本地持久化接入计划、原抓包/证书/代理/存储设置 | 设备绑定、凭据撤销、操作权限、运行策略 |

`SchedulerSettings` 保存 HTTPS 服务地址、1–40 字设备名、10–300 秒的计划轮询间隔和 provider ID 集合。服务地址拒绝内嵌凭据、查询参数和片段；配置存于既有私有 DataStore，与抓包历史和 CA 分开。尚无凭据输入，也不生成绑定成功、在线、接单或上传成功状态。配置恢复后仍为未绑定。

平台状态分开表达：是否安装候选包、实际安装版本、是否有指定版本的解析样本、是否实现自动操作。当前六个平台的自动操作均待实现；四个平台有解析样本不能据此宣称六平台自动 RPA 可用。安装、回到页面或点击刷新时重读应用列表。登录、目标应用 CA 信任、当前模式是否可采集均不能由安装状态推断。

原应用 ID、存储和采集接口保留，启动页改为工作台。采集流程仍经用户的通知/VPN 授权；设置不会自动改抓包目标或启动采集。已有 ADB 只读导出接口保持原契约。

## 下一阶段的轮询机制（待两端实现）

以下是拟议协议，不是已经存在的 GEO API。路径最终在 GEO 端实现时统一确认。设备令牌绑定租户和设备身份，不使用浏览器账号 Cookie，不由手机传租户 ID 决定授权。令牌落 Android Keystore 保护的存储，不能放进 URL、普通设置或日志。

| 建议接口 | 行为与关键约束 |
| --- | --- |
| `POST /api/android-rpa/v1/devices/bind` | 以短期一次性绑定码换取设备 ID 和设备凭据；绑定码由 GEO 内已授权用户生成 |
| `POST /api/android-rpa/v1/devices/heartbeat` | 设备凭据鉴权；报告 APK/系统/目标 App 版本、经验证的操作与采集能力、忙闲、当前 attemptId；登录异常不伪报可用 |
| `POST /api/android-rpa/v1/tasks/claim` | 带持久化 claimRequestId、设备能力和可接受模式；服务端原子领取一条安卓任务，重复领取请求返回同一租约；无任务返回 204 |
| `POST /api/android-rpa/v1/tasks/{taskId}/heartbeat` | 带 attemptId、租约 token 和当前阶段；返回续租时限、取消指示及服务端时间 |
| `PUT /api/android-rpa/v1/tasks/{taskId}/attempts/{attemptId}/result` | 幂等回传同一已持久化结果；响应明确确认已保存或冲突；不触发重新提问 |
| `POST /api/android-rpa/v1/tasks/{taskId}/attempts/{attemptId}/evidence` | 限定类型和大小的独立证据通道，回传摘要/对象 ID；禁止上传 CA 私钥或将原始 HAR 作为默认证据 |

领取响应至少包含 `schemaVersion/taskId/attemptId/leaseToken/leaseExpiresAt/serverTime/provider/prompt/mode/deadline`。设备级互斥由服务端租约与本地持久化执行记录共同保证；任务只允许稳定 provider 和已支持模式，不下发任意脚本、Selector、URL、证书或平台凭据。正式可领取的平台能力由验证过的执行适配器声明，不能直接使用界面的“计划平台”。

任务需要独立的 `android_app` 执行渠道，与浏览器 worker 隔离领取；结果保存 App/设备/解码器版本及采集来源，品牌分析复用 GEO 现有流程。具体如何复用 GEO 模型、迁移与租户授权留在 GEO 仓库实现，不能仅在手机新建不兼容的队列。

### 串行状态与中断恢复

`received → prepared → sending → sent → collecting → pending_upload → completed`

另有 `failed / needs_review / cancelled / lease_expired`。记录任务租约、设备时间起点、精确问题、目标包及版本、采集 sessionId、exchangeId 和完整性。普通状态字段与发送边界分别持久化：在触发发送动作前先写入 `sending`，动作确认后写入 `sent`。进程重启遇到 `sending` 或未知提交结果，只核对已有证据或转人工，不能重新点击发送。

1. 未绑定、权限/版本/模式不支持时不领取任务。手机同时最多一个任务；未确认回传前也不领取下一条。
2. 空队列采用已配置间隔加抖动；429 遵循 Retry-After，网络和 5xx 指数退避并设上限。认证失败停止领取并提示重新绑定；配置变更不产生重叠轮询。
3. 租约时长、心跳频率与超时由服务端约定，设备使用服务端时间估计与单调计时计算剩余租约，避免设备时钟偏差。任务心跳独立于设备空闲轮询。
4. 领取响应丢失可用同一 claimRequestId 恢复；发送后断网只续采和保存结果。租约失效后禁止开始新的 UI 动作，将证据保留并进入协调/人工流程；服务端不得将已发送或不确定发送的任务自动分配给另一台手机重发。
5. 暂停接单只停止新领取，当前任务按租约继续；取消必须在动作边界检查。发送后的取消仍记录“已发送”，不回滚为可重发状态。
6. 回传重试使用相同 attemptId 和同一结果摘要；服务端只有明确确认保存后，本地才标记 completed。重启后恢复 pending_upload；结果上传的歧义不能转换成再次提问。

以上行为均需真实的持久化执行日志和后台运行实现，当前界面不模拟这些状态。长期跨 App 后台运行与权限恢复需在目标 Android 版本、普通 APK 权限下单独验收。

### 回答和证据边界

采集用进程内 `AnswerReader`，沿用 [结果接口](automation-v1.md) 的精确选择和完整性规则。只允许唯一完整候选进入成功结果；未终止、正文截断、未知模式/事件、重复候选分别保留失败或人工核对原因。任务身份不能靠问题文本替代。

回传包含原始 `answer`，`metadata` 中保存 provider、`source=android_capture_sse`、`channel=android_app`、capture IDs、decoder、完整性、searchSources 和 referenceCaptureStatus。若不能取得引用 URL，明确 not_collected；不从正文猜测完整来源。品牌命中、排名、情感由 GEO 计算。截图独立标记成功/失败及范围；未实现或未验证长图时不声称完整截图。

## 验收顺序

先验证本阶段的页面导航、配置持久化和旧采集入口。下一阶段先完成 GEO 租约及鉴权与合成网络故障测试，再移植一个平台的 APK 无障碍操作，验证单次发送与完整回答关联。最后真机验收断网、杀进程、续租失败、回传响应丢失、取消及人工接管。测试问题由用户发送；保留原 CA、signer、登录和历史；逐平台记录已验证版本与模式。

## 本阶段验证（2026-10-19）

- `:core:test :data:testDebugUnitTest :app:testDebugUnitTest`：56 项通过，包含接入地址拒绝内嵌凭据、不安全协议及参数边界测试。
- `:app:assembleDebug :app:assembleDebugAndroidTest`：构建通过。
- 在本次启动的只读 Android 15 x86_64 模拟器中，`SchedulerWorkbenchTest`、`SettingsStorageTest`、`RequestDetailActivityTest` 共 10 项通过。覆盖合成安装状态、四入口导航、非法地址禁止保存、合法配置展示、DataStore 往返且保留采集设置、320×480 dp 清空确认，以及原请求详情与返回状态。持久化测试首次使用了无 Application 的 instrumentation context，修正为 targetContext 并复测通过；测试结束恢复原设置。
- 页面截图保存在忽略的 `artifacts/scheduler-ui/`，平台安装信息使用合成夹具。未在真实手机安装、未连接 GEO、未发送问题，也未验证后台调度或真实自动操作。
