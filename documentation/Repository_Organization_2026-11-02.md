# 仓库整理与文件改动记录 - 2026-11-02

## 整理依据与范围

Organized existing tracked files against Project_Description.pdf section 5 (pages 3-5). The course uses backend/tests/; the singular test/ in some bullets is interpreted consistently with the template and existing runners. Standard Android source roots are preserved. No application behavior is intentionally changed.

## 文件移动清单（38 个）

| Previous path | Current path |
| --- | --- |
| `frontend/app/src/androidTest/java/com/example/cpen321application/AccountAvatarTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/AccountAvatarTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/AccountSettingsUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/AccountSettingsUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/AccountTransactionDefaultsTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/AccountTransactionDefaultsTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/AppLockTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/AppLockTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/AppNavigationUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/AppNavigationUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/AvatarCropUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/AvatarCropUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/CloudSaveNamingUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/CloudSaveNamingUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/ExampleInstrumentedTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/ExampleInstrumentedTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/GroupExpensesUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/GroupExpensesUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/GroupLiveSplitUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/GroupLiveSplitUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/GroupMembershipIntegrationTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/GroupMembershipIntegrationTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/GroupMembershipUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/GroupMembershipUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/GroupSplitPendingStorageTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/GroupSplitPendingStorageTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/GroupSplitProtocolTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/GroupSplitProtocolTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/LedgerCloudSaveRestoreTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerCloudSaveRestoreTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/LedgerLiveFxTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerLiveFxTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/LedgerPersistenceTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerPersistenceTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/EntrySharingUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/EntrySharingUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/GroupSettingsUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/GroupSettingsUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/LedgerRecognitionAuthTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/LedgerRecognitionAuthTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/LedgerUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/e2e/LedgerUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/ReceiptImagesTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/integration/ReceiptImagesTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/RegistrationUiTest.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/ui/RegistrationUiTest.kt` |
| `frontend/app/src/androidTest/java/com/example/cpen321application/TestAppLockProvider.kt` | `frontend/app/src/androidTest/java/com/example/cpen321application/helpers/TestAppLockProvider.kt` |
| `documentation/evidence/CP1-E2E-01-followup-split.png` | `documentation/images/evidence/CP1-E2E-01-followup-split.png` |
| `documentation/evidence/CP1-E2E-01-saved-split.png` | `documentation/images/evidence/CP1-E2E-01-saved-split.png` |
| `documentation/examples/ledger-demo.csv` | `documentation/res/examples/ledger-demo.csv` |
| `documentation/examples/synthetic-receipt-cad.pdf` | `documentation/res/examples/synthetic-receipt-cad.pdf` |
| `documentation/examples/synthetic-receipt-cad.png` | `documentation/images/examples/synthetic-receipt-cad.png` |
| `documentation/examples/synthetic-receipt-expected.json` | `documentation/res/examples/synthetic-receipt-expected.json` |
| `documentation/examples/synthetic-statement-cad.pdf` | `documentation/res/examples/synthetic-statement-cad.pdf` |
| `deploy/activate.sh` | `scripts/deploy/activate.sh` |
| `deploy/Caddyfile` | `scripts/deploy/Caddyfile` |
| `deploy/install-docker.sh` | `scripts/deploy/install-docker.sh` |
| `deploy/provision-gcp.sh` | `scripts/deploy/provision-gcp.sh` |
| `deploy/smoke-check.mjs` | `scripts/deploy/smoke-check.mjs` |
| `backend/tests/Group_API_Tests.md` | `documentation/Group_API_Tests.md` |
| `backend/scripts/membership-test-server.cjs` | `backend/tests/helpers/membership-test-server.cjs` |

## 文件内容改动

- Android test package declarations and imports follow the new directories; test bodies and assertions are preserved.
- Tracked documentation, sample commands, deployment configuration and fixture imports follow moved files.
- Backend interface/unit tests keep their existing course-template categories.
- 课程运行脚本现在按实际类别发现测试；空 NFR 套件返回失败，避免误报通过。前端登录等待改为显式可选，并新增仅列出类名和复用已准备环境的选项。
- 后端脚本串行执行 mock、no-mock、combined 三组，并分别生成覆盖率与 JSON 结果；锁定依赖安装使用 npm ci。
- 所有测试函数、断言和应用业务源码保持原内容；仅调整测试包声明、必要导入和一个准确描述集成测试范围的注释。

## 保留的本地工作

The existing untracked pr68-work/ checkout and three untracked research/audit documents are outside this reorganization. Ignored SDK settings, secrets, build outputs and local reference PDFs are untouched. Historical paths in those untracked notes are not rewritten.

## 验证结果

| 检查 | 实际结果 |
| --- | --- |
| Backend TypeScript | npm run typecheck 通过 |
| Backend unit | 5 suites / 140 tests 通过 |
| Backend mock interface | 9 suites / 173 tests 通过 |
| Backend no-mock interface | 12 suites / 197 tests 通过 |
| Backend combined interface | 21 suites / 370 tests 通过；不含 unit |
| Frontend JVM unit | 13 suites / 102 tests 通过，0 skipped |
| Frontend instrumented APK | 编译成功；最终导入清理后再次编译成功 |
| Frontend 测试发现 | PowerShell/Bash 均发现 e2e 1、ui 12、integration 10 个测试类，helpers 不被执行 |
| Android 设备运行 | 未运行：adb devices 没有已连接设备；编译通过不等于设备测试通过 |
| NFR | 两端均没有专用测试；PowerShell/Bash 四个正常运行入口均以 exit 1 明确报告空套件 |
| PowerShell/Bash | 语法检查通过；列出测试类别正常 |
| 迁移内容完整性 | 资源文件与原 Git 文件逐字节一致；测试主体与原文件一致；包路径匹配 |
| 文档本地链接 / git diff --check | 通过 |

实际控制台摘要：[repository-organization-2026-11-02.txt](res/testing/repository-organization-2026-11-02.txt)。完整本地验证日志位于忽略目录 output/repository-organization/；覆盖率与 JSON 执行报告位于 backend/coverage/{mock,no-mock,interface}/。

后端 combined interface 覆盖率：Statements 74.54%、Branches 68.43%、Functions 80.71%、Lines 74.93%。这不是包含 unit 的全量覆盖率，也不是 100% 覆盖。原有 ts-jest TS151002 警告仍存在，没有关闭诊断。

## 提交前仍需补齐

这次工作整理现有文件与入口，没有新增功能测试。完整后端连接 E2E、两项专用 NFR 验证、每个 API 成对的 describe 组及用例注释、CI workflow/运行链接、最终 main 提交 SHA、覆盖率截图及未覆盖代码解释仍需按 checkpoint 要求补齐。详见 [Testing.md](Testing.md) 和 [Test_Inventory.md](Test_Inventory.md)。初次验证完成时尚未提交或推送；后续按用户要求提交并创建 PR，合并与云端部署不在此次操作范围内。GitHub 公共可见性、模板 fork 来源和 TA 写权限未在本地整理中核验。


## 原地修改文件清单

| 文件 | 改动内容 |
| --- | --- |
| `README.md` | 新增结构/测试入口；修正示例资源与测试类名。 |
| `backend/package.json` | 新增 mock/no-mock/interface/unit/nfr 分类运行命令；依赖不变。 |
| `compose.cloud.yaml` | Caddy 配置挂载指向 scripts/deploy/Caddyfile。 |
| `documentation/Feature1-Ledger.md` | 修正移动后的测试包名、文件路径或部署路径。 |
| `documentation/Feature3-LetsGoDutch.md` | 修正移动后的测试包名、文件路径或部署路径。 |
| `documentation/Google_Cloud_Deployment.md` | 修正移动后的测试包名、文件路径或部署路径。 |
| `documentation/Group-Membership-Live.md` | 修正移动后的测试包名、文件路径或部署路径。 |
| `documentation/Group_Expenses.md` | 修正移动后的测试包名、文件路径或部署路径。 |
| `documentation/Testing.md` | 保留历史 CP1 记录，新增当前分类、运行步骤、提交缺项与验证导航。 |
| `scripts/lib/frontend-instrumented-test-common.ps1` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |
| `scripts/lib/frontend-instrumented-test-common.sh` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |
| `scripts/run-backend-interface-tests.ps1` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |
| `scripts/run-backend-interface-tests.sh` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |
| `scripts/run-backend-nfr-tests.ps1` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |
| `scripts/run-backend-nfr-tests.sh` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |
| `scripts/run-frontend-e2e-tests.ps1` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |
| `scripts/run-frontend-e2e-tests.sh` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |
| `scripts/run-frontend-nfr-tests.ps1` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |
| `scripts/run-frontend-nfr-tests.sh` | 更新分类发现、参数传递、依赖安装、覆盖率输出或空套件处理。 |

移动文件中，Android .kt 同步修改 package/import；membership-test-server.cjs 修正到 dist 的相对 require；provision-gcp.sh 修正仓库根路径；smoke-check.mjs 修正报告输出相对路径。其他移动文件的内容保持原样（文本换行按 Git 设置处理）。

另将 Bash 工作副本统一为 LF 换行，包括 scripts/run-backend.sh 和 scripts/run-frontend.sh；这两个启动脚本没有内容差异，git diff 为空，但 Git 工作区状态可能显示换行变化。

## 新增文件清单

| 文件 | 用途 |
| --- | --- |
| `documentation/Repository_Structure.md` | 第 5 节目录映射与 Git 管理要求。 |
| `documentation/Test_Inventory.md` | 所有现有测试索引、mock 边界、源代码位置和命令。 |
| `documentation/Repository_Organization_2026-11-02.md` | 本改动记录，包含全部迁移与原地修改。 |
| `documentation/res/testing/repository-organization-2026-11-02.txt` | 本次实际运行输出的摘要，不是最终 main 提交证据。 |
| `backend/tests/nfr/README.md` | 说明 NFR 空目录与后续提交规则。 |
| `frontend/app/src/androidTest/java/com/example/cpen321application/nfr/README.md` | 说明前端 NFR 空目录与后续提交规则。 |
| `scripts/run-frontend-regression-tests.ps1` | 独立运行补充 UI / integration 测试。 |
| `scripts/run-frontend-regression-tests.sh` | 对应 Bash 入口。 |
