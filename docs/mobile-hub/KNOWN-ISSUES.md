# KNOWN-ISSUES

- R3：本地服务端源码快照可能落后四台真机部署版本，施工前需 health 对账（未做，需要设备在线）。
- 服务端无幂等键/无时区接口/任务列表无分页：按降级方案处理，见 Risk-Register。
- fork 版 v121 steering 依赖比本地 repo 快照新的服务端行为（turn 中 prompt 在快照里是 409）：v139 任务操作不依赖 steering，不受影响；v141 交接不用 steering 通道。
- v140 Low 未修项：定时选择过去时间无客户端拦截（服务端会立即触发，属既有语义）；probe 进行中无 UI 指示；文件夹 chips 超长横向滚动可用性一般；IdeaStore/DispatchStore JSON 解码失败静默返回空表为既有 store 模式（下次 upsert 会覆盖旧数据）。
- 回查匹配限制：服务端无幂等键，回查靠 标题+folder+时间窗（-5min 时钟冗余）；同名任务在窗内多条时保持 UNKNOWN 拒绝自动确认（fail-safe）。
- v141：交接包编辑内容进程被杀会丢（未入 SavedStateHandle，接受为已知限制）；交接/收件箱派发 sheet 均不内嵌 probe，UNKNOWN 回查统一在想法页派发记录卡。
- v144（2026-11-01）：v143 报告的动态页闪退已由主人提供的真机堆栈定位为属性初始化顺序问题（Main.immediate 同步执行 init 协程），v144 修复；SessionListViewModel 同款地雷一并拆除。
- 52 处 VM 层错误/状态提示为硬编码中文（默认 locale 英文设备会看到中文错误文案）——主人设备为中文不受影响；迁移 strings.xml 列为下一批技术债。
- 台账上限 50 在全部为 UNKNOWN（无 CONFIRMED 可逐）时不裁剪，理论无界增长（防丢是刻意的；实际需 50 次未确认派发才触发）。
- 交接 sheet 派发中下滑关闭→重开会回放终态结果弹窗（成功/未知），属刻意行为：那是用户唯一能看到结果的地方。
- 编辑器草稿进程被杀丢失（rememberSaveable 槽位在 ideas 异步加载完成前无人认领），接受为已知限制。
- 批v161.3-R 欠账①（R3-4）：VM 层硬编码中文 i18n 迁移——TaskHub/Automation/QuickAction/Inbox/Handoff/ConfigSync 等约几十条错误与回执文案硬编码中文（与上方「52 处」条同源，R3 复核确认仍开放）；English 设备半中半英，迁 strings.xml 双语工程量大另批。
- 批v161.3-R 欠账②（R4-3）：同设备同接口被 3-5 处独立轮询重复打（任务板开着 ≈4.4 次/分/台 workTaskList，3 台前台 ≈33 POST/分）；抽 @Singleton sweep 三消费者共享一轮属架构级重构，另批。
- 批v161.3-R 欠账③（R3-6，已拍板保留现状）：「移出任务板」看板卡有确认弹窗、列表长按/详情菜单无——刻意保留：看板场景防误触，列表/详情是可逆快速路径+toast。
- 批v161.3-R 接受边缘（已留档不修）：pendingEnable 单槽连开两开关只保留最后一个；segment/automation 双 one-shot 10s 窗口窄竞争。
- 批v161.3 通知抑制口径（拍板留档）：P0 授权请求通知在「任务板在顶」时不抑制（琥珀卡在动态页，授权必须突破），仅「该会话详情在顶」时抑制；后台永不抑制。
