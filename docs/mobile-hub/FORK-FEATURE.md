# Codex Fork（安卓端）交付说明

> 范围：第一版只做 **Codex**。OpenCode / Pi / ZCode 不接；ZCode 已知 no protocol fork。
> 本功能 v134 已有首版，本文件记录这次的 UX 收口与协议契约，防止后续回退。

## 1. 服务端契约

| 项 | 值 |
|---|---|
| 路由 | `POST /api/acp_fork` |
| Handler | `src-tauri/src/web/handlers/acp.rs::acp_fork` |
| 请求 | camelCase（见下） |
| 响应 | camelCase（见下） |
| 能力判定 | `agent_type != ZCode && session_capabilities.fork.is_some()` |

请求：

```json
{
  "connectionId": "conn-1",
  "conversationId": 123,
  "folderId": 7,
  "forkFromTurnId": "turn-4"
}
```

- `conversationId` / `folderId`：仅当连接还没绑定会话行时用于补链；已绑定则忽略。
- `forkFromTurnId`：已完成的助手轮 id。**省略 = 从会话尾部 Fork**。
- 客户端不发 `_meta`；服务端在内部组装 ACP `session/fork`：
  `_meta.jetbrains.air.fork = { version, messageId, messageFingerprint, messageOccurrence }`。

响应：

```json
{
  "forkedSessionId": "S2",
  "originalSessionId": "S1",
  "siblingConversationId": 124
}
```

服务端语义（不要按“跳到另一个会话”实现）：

1. **当前会话行**改指向新会话 `S2`，标题加 `[Fork] ` 前缀，历史截断到分支点。
2. 另建**同列表 sibling 行**保存原会话 `S1` 的完整历史（`PendingReview`）。
3. 所以客户端 Fork 成功后**留在当前页**、重新拉详情即可看到“继承历史”；
   原会话在会话列表里以 sibling 保留。

## 2. Fork 点解析规则（Codex）

- Codex rollout 文件不存 item id，服务端只能按**助手文本指纹**定位：
  `sha256:<hex>` + 同指纹第 N 次出现。
- 因此锚点必须是“带非空助手文本的 turn”。纯工具调用轮没有可定位文本。
- 锚点解析失败时服务端**降级为尾部 Fork**，不会报错。客户端必须避免在非尾部
  发一个服务端解析不了的 id，否则会“看起来分叉了，其实复制了父会话”。

安卓端对应三条规则（`ForkAvailability`）：

| 情况 | 行为 |
|---|---|
| 锚点存在且是正常 `turn-N` | 发送该 `forkFromTurnId` |
| 该回复是**线程尾部**，且有文本锚点 | 发送锚点（精确分支） |
| 该回复是**线程尾部**，无文本锚点 | 发送 `null`（尾部 Fork；服务端自己的解析就结束在这里，语义等价） |
| `live-*` 锚点（任意位置） | **禁灰**，不发请求：该轮只存在于客户端，服务端尚不能证明它在父会话尾巴里 |
| 非尾部、无文本锚点 | **禁灰**，不发请求 |

`live-*` 解释：APP 自己流式收到、尚未被服务端 transcript 取代的轮。正常回合结束后
`reconcileAfterTurn()` 会用服务端副本替换，id 变回 `turn-N`；未确认的 `live-*`
不允许降级成尾 Fork（否则可能少继承这一轮）。

## 3. 客户端实现位置

| 层 | 文件 |
|---|---|
| RPC | `core/network/CodegClient.kt::forkSession` |
| 请求/响应模型 | `core/model/WireRequests.kt::AcpForkBody` / `ForkResult` |
| 能力来源 | `core/model/SessionSnapshot.kt`、`AcpEvent.kt`（`fork_supported`） |
| Fork 判定 | `feature/sessiondetail/timeline/ForkAvailability.kt` |
| 锚点计算 | `feature/sessiondetail/timeline/TimelineNode.kt::buildPersisted` |
| Footer UI | `feature/sessiondetail/timeline/TimelineNodeBody.kt` |
| 动作/状态 | `feature/sessiondetail/SessionDetailViewModel.kt::forkFromTurn` |
| 屏幕接线 | `feature/sessiondetail/SessionDetailScreen.kt` |

## 4. UI 状态

每个**已完成**的助手回复 Footer 都显示 Fork 入口（不再依赖“有可复制文本”）：

- 可用：正常颜色，点击后请求 `acp_fork`。
- 禁灰：降低透明度；点击弹 Notice 说明原因，不发请求：
  - 当前智能体不支持 / 服务端不支持（`fork_agent_unsupported`、`fork_server_unsupported`）
  - 回合进行中 / 正在分叉（`fork_turn_in_progress`、`fork_already_running`）
  - 这条回复没有可定位文本且不在尾部（`fork_unresolved_turn`）

Fork 成功后：

- `externalId` 切到 `forkedSessionId`，清 `liveBuilder` / `reconcile` / `flushJob` /
  `pendingUserTurns`，再 `load()` 拉新详情；
- `forking` 闩到 reload 落地，防双击与旧时间线二次 Fork；
- Notice：`fork_succeeded`（已分叉，原会话保留在会话列表）。

## 5. 配置

客户端无新增配置项。前置条件：

1. 服务端包含 `/api/acp_fork` 路由（旧服务端 404 → 入口禁灰）。
2. 智能体为 `codex`，且 Codex ACP 适配器声明 fork 能力（`session_capabilities.fork`）。
3. 服务端能读到该会话的 transcript（Fork 点解析依赖它）。

无需、也不要往客户端写任何 token / secret。

## 6. 测试步骤

本地只做静态检查；编译与单测走 GitHub Actions `Verify`（红线：不本地 `./gradlew`）。

自动化：

- `ForkAvailabilityTest`：支持/忙/正常锚点/无文本非尾/无文本尾/`live-*` 尾/`live-*` 非尾。
- `TranscriptTimelineTest`：文本锚点、纯工具轮、非尾纯工具轮、pending 不算尾、live 追加后持久化尾巴让位。
- `CodegJsonTest`：`AcpForkBody` camelCase 且 null 锚点省略；`ForkResult` camelCase 解码；
  `SessionSnapshot` 的 `fork_supported` / `connection_id` snake_case 解码。

真机手测（Codex 会话）：

1. 历史回复 Footer 点 Fork → 新会话标题出现 `[Fork] `，时间线只到分支点，列表多一条原会话。
2. 纯工具调用、且后面还有消息的回复 → Fork 禁灰，点击提示“没有可定位文本”。
3. 线程最后一条纯工具回复 → Fork 可点，结果等于尾部 Fork。
4. 回合进行中 → Fork 禁灰并提示回合进行中。
5. 打开 OpenCode / Pi / ZCode 会话 → Fork 禁灰并提示“当前智能体暂不支持”。
6. Fork 后原会话的 Resume / 消息流 / 历史不受影响。
