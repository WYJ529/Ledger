# Issue 修复记录 — 2026-11-02

范围：当前 42 个 open issues；41 项已实现，#72 经用户确认无需改动，保留现有手动云存档行为。所有改动位于 `bundle/issue-resolve` 分支；本记录随修复提交供 PR 审阅。

## 逐项结果

| Issue | 状态 | 实现结果 |
| --- | --- | --- |
| [#67](https://github.com/WYJ529/Ledger/issues/67) | 已实现 | 识别提示明确拒绝模糊、遮挡、不可读和无关图片；返回空交易与提示，不猜测交易。增加模型请求与拒绝响应回归。 |
| [#72](https://github.com/WYJ529/Ledger/issues/72) | 无需改动 | 用户确认保留现状；继续使用三槽手动云存档跨设备保存/加载，不新增自动同步。 |
| [#76](https://github.com/WYJ529/Ledger/issues/76) | 已实现 | 移除注册及其他密码输入的显示密码开关，统一使用遮罩。 |
| [#77](https://github.com/WYJ529/Ledger/issues/77) | 已实现 | 移除刷新群组按钮；列表在可见期间每 5 秒更新，并在回到页面时更新。 |
| [#78](https://github.com/WYJ529/Ledger/issues/78) | 已实现 | 未登录的 Groups 与 Me 登录共用独立 SignInPage。 |
| [#80](https://github.com/WYJ529/Ledger/issues/80) | 已实现 | 移除群组页 Members、成员弹窗及加号邀请入口；邀请操作放入 Group settings。 |
| [#81](https://github.com/WYJ529/Ledger/issues/81) | 已实现 | 移除列出的冗余说明和版本信息；简化导入、空账本及金额错误文案。 |
| [#82](https://github.com/WYJ529/Ledger/issues/82) | 已实现 | 分享邀请附加在 Ledger 的 Group > Join group 粘贴链接的说明，提供三种译文。 |
| [#83](https://github.com/WYJ529/Ledger/issues/83) | 已实现 | 移除 Security 的 Device security settings 按钮。 |
| [#85](https://github.com/WYJ529/Ledger/issues/85) | 已实现 | Rename group 移入 Group settings；群主只显示一个 Delete group 操作。 |
| [#86](https://github.com/WYJ529/Ledger/issues/86) | 已实现 | 头像更新成功显示确认弹窗，点击 OK 关闭。 |
| [#87](https://github.com/WYJ529/Ledger/issues/87) | 已实现 | 移除锁屏的 Device security settings 按钮；保留错误说明。 |
| [#88](https://github.com/WYJ529/Ledger/issues/88) | 已实现 | 冷启动和正常后台返回时自动认证一次；取消后允许手动重试，不自动循环。 |
| [#89](https://github.com/WYJ529/Ledger/issues/89) | 已实现 | 点击 Expenses、Me 标签回到对应首页，清除相应子页面状态。 |
| [#90](https://github.com/WYJ529/Ledger/issues/90) | 已实现 | 移除 My Expenses 的 Refresh，保留交易数量。 |
| [#91](https://github.com/WYJ529/Ledger/issues/91) | 已实现 | 自有文件/照片选择器和相机返回不要求再次解锁；正常后台及无结果的 Recents 返回仍锁定。 |
| [#92](https://github.com/WYJ529/Ledger/issues/92) | 已实现 | 移除切换报表币种的成功通知，保留失败提示。 |
| [#94](https://github.com/WYJ529/Ledger/issues/94) | 已实现 | 超过 5 MiB 的收据图片自动压缩；采样解码、修正 EXIF、调整质量及尺寸；超过 100 MiB 原图拒绝。选择器和相机共用逻辑。 |
| [#95](https://github.com/WYJ529/Ledger/issues/95) | 已实现 | PDF/CSV 上限改为 20 MiB；后端导入 JSON 限额 28 MiB，图片仍限制 5 MiB；增加边界回归。 |
| [#96](https://github.com/WYJ529/Ledger/issues/96) | 已实现 | 个人交易 Merchant/Payee 可空，前后端校验、导入及云存档接受空值。 |
| [#97](https://github.com/WYJ529/Ledger/issues/97) | 已实现 | 账单新增/修改/删除、改名、个人交易分享到群组及最终分账提交后推送最小通知；客户端后台刷新摘要，保留编辑草稿；移除 Refresh expenses。 |
| [#98](https://github.com/WYJ529/Ledger/issues/98) | 已实现 | 月份标题在左右等宽区域之间居中，使用独立前后月份图标。 |
| [#99](https://github.com/WYJ529/Ledger/issues/99) | 已实现 | 内存 ViewModel 保留解锁状态，配置变化不重新上锁；进程重启和正常后台仍锁定。 |
| [#100](https://github.com/WYJ529/Ledger/issues/100) | 已实现 | 币种选择移到 Amount 输入框右侧，点击缩写展开菜单。 |
| [#101](https://github.com/WYJ529/Ledger/issues/101) | 已实现 | 新增应用内圆形头像裁剪，可拖动及双指缩放；确认输出 384×384 JPEG，保持 EXIF 校正和大小上限；取消不上传。 |
| [#102](https://github.com/WYJ529/Ledger/issues/102) | 已实现 | 个人交易必填校验改为弹窗，列出需修正字段。 |
| [#103](https://github.com/WYJ529/Ledger/issues/103) | 已实现 | 全局文本箭头替换为六个矢量图标；统一垂直对齐及方向镜像，删除旧箭头翻译。 |
| [#104](https://github.com/WYJ529/Ledger/issues/104) | 已实现 | 移除整个 Account information 卡片及其未使用实现。 |
| [#105](https://github.com/WYJ529/Ledger/issues/105) | 已实现 | 注册、Google 登录与主要登录按钮统一宽度。 |
| [#106](https://github.com/WYJ529/Ledger/issues/106) | 已实现 | 报表币种按钮直接打开锚定菜单，标注当前币种；重复选择不操作，处理期间禁用，保留汇率失败提示。 |
| [#107](https://github.com/WYJ529/Ledger/issues/107) | 已实现 | 移除 Me 底部版本编号。 |
| [#108](https://github.com/WYJ529/Ledger/issues/108) | 已实现 | 移除 Change photo，头像右上角显示相机图标，整个头像区域可点击。 |
| [#109](https://github.com/WYJ529/Ledger/issues/109) | 已实现 | 移除群组列表 Back to home，内部页面及系统返回行为保留。 |
| [#110](https://github.com/WYJ529/Ledger/issues/110) | 已实现 | Google 账户密码提示通过 t() 翻译。 |
| [#111](https://github.com/WYJ529/Ledger/issues/111) | 已实现 | 补齐分账、邀请、导入、汇率及无障碍文案的简体、繁体、法语翻译；回归检查直接英文 UI 字面量。 |
| [#112](https://github.com/WYJ529/Ledger/issues/112) | 已实现 | 移除编辑账户时区设置及新交易对账户时区的依赖，使用设备日期。 |
| [#113](https://github.com/WYJ529/Ledger/issues/113) | 已实现 | 移除编辑账户默认币种设置及新交易对其依赖，使用当前报表币种。 |
| [#114](https://github.com/WYJ529/Ledger/issues/114) | 已实现 | 群主显示 Delete group，删除说明只在确认弹窗中呈现，明确影响所有成员、邀请失效、不可撤销；普通成员仍可 Leave。 |
| [#115](https://github.com/WYJ529/Ledger/issues/115) | 已实现 | 移除技术连接状态行及其译文；断线时保留简短暂停认领提示、失败信息及 Retry。 |
| [#116](https://github.com/WYJ529/Ledger/issues/116) | 已实现 | Remove document 按钮改为占满可用宽度并调整内边距。 |
| [#117](https://github.com/WYJ529/Ledger/issues/117) | 已实现 | 已打开群组标题显示粗体 group name (N)，姓名换行；人数随实时摘要更新，列表及设置页各自保留标题。 |
| [#118](https://github.com/WYJ529/Ledger/issues/118) | 已实现 | 云存档增加可选名称、40 字符校验、带 revision 的原子重命名及旧数据回退；Android 保存前命名、单独 Rename，并在确认流程展示名称。 |

## 描述冲突的处理

- #94 中 PDF/CSV 保持 5 MiB 的要求被 #95 的 20 MB 新要求覆盖。实现按 20 × 1024 × 1024 字节计算，JSON 上限为 28 MiB，覆盖 Base64 编码和元数据开销。
- #87 要保留 Security 页设备设置入口，但 #83 明确删除该入口，因此两处入口均删除，错误提示保留。
- #106 要保留币种切换成功通知，但 #92 明确删除该通知，因此采用直接菜单并保留错误提示。
- #85 与 #114 合并为 Group settings 中单一群主删除操作。
- 时区、默认币种的旧账户字段/API 为已有客户端及数据兼容保留；本次 UI 和新交易不再使用这些设置。

## #72 用户决定

用户确认此项无需修改，保留现有三槽手动云存档。加载完整账本快照时仍需确认，自动同步不属于本次范围。

## 验证

- Backend：完整 Jest 回归 26 套、509 项通过，包含新增 Socket 隐私/提交后通知用例。Codacy 后续修正的服务接口回归 2 套、46 项通过，TypeScript typecheck 通过。
- Android：13 套、102 项单元测试全部通过；最终设备回归 53 项全部通过，覆盖账户、导航、群组实时刷新与草稿保留、云存档命名、头像裁剪、收据压缩和应用锁。lint 无错误（62 条 warning、7 条 hint），Debug APK 构建通过。
- 模拟器：Pixel 9 / Android API 36。锁屏测试使用确定性的生物认证回调；没有调用付费在线 AI 或在真实指纹/人脸硬件上验证。
- 后端端点测试使用真实 Express、MongoDB 测试实例及 Socket.IO；外部 AI 通过模拟服务验证请求和响应契约，不能据此保证每张真实模糊图片都能被模型正确拒绝。
- `git diff --check` 无空白错误。

## PR #120：Codacy 改进

- 头像裁剪只捕获预期的 I/O、权限和图像参数异常，保留协程取消与意外运行时故障；检查 JPEG 编码是否成功，避免提交空图像。
- 去掉带标签的返回，拆分群组导航的复杂条件，并提取图片质量压缩循环以降低嵌套层级。
- 后端元数据分别公布 20 MiB 文档与 5 MiB 图片上限，与 Android 上传校验一致。
- 群组变更事件复用 JSON 对象/字符串解析，忽略损坏或不属于当前群组的事件；增加协议与不可读头像回归。
- 本轮 Android 针对性设备回归 20/20、单元测试 102/102、lint 和 Debug APK 构建通过；后端接口回归 46/46 及类型检查通过。

## 交付物

- APK：`frontend/app/build/outputs/apk/debug/app-debug.apk`
- Android lint：`frontend/app/build/reports/lint-results-debug.html`
- Android 单元测试：`frontend/app/build/reports/tests/testDebugUnitTest/index.html`
- Android 设备回归：`frontend/app/build/reports/androidTests/connected/debug/index.html`

保留了任务开始前已有的 `documentation/Bank-Import-Research-2026-11-02.md`、`pr68-work/` 和 `tmp/` 日志，未纳入本次交付。

