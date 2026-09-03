Type: issue
Status: blocked
Blocked by: 02, 03, 04

# 05 — 接入安全、可安装的 pre-push Hook

## Outcome

让开发者通过显式、幂等且不覆盖既有配置的方式启用 pre-push 门禁，并使每次 push 使用真实远端状态和最严格适用 profile 生成可复现证据。

## Acceptance Criteria

- [ ] 安装和卸载命令只读写当前仓库的 `core.hooksPath`：未配置时安装、已指向项目目录时幂等、指向其他目录时保护性失败；卸载只移除仍指向项目目录的本地配置。
- [ ] Maven 构建、测试和普通开发命令不会隐式安装 Hook，也不会修改全局 Git 配置。
- [ ] Hook 从 pre-push 输入解析所有 ref：已存在远程分支使用远程旧 SHA，新分支使用与远程默认分支的 merge-base，多 ref 合并路径并采用最严格 profile。
- [ ] 远端默认分支、base、ref 或变更集合无法可靠解析时失败关闭；不会退化为 docs-only。
- [ ] 运行前检测已暂存、未暂存和未忽略新文件；工作区不干净时拒绝生成正式 pre-push 证据且不输出文件内容。
- [ ] Hook 调用仓库唯一门禁入口并保持其退出码，安装冲突、卸载保护、现有分支、新分支、多 ref 和脏工作区都有自动化测试。

## Verification

- 在隔离 Git 仓库 fixture 中验证安装、幂等、冲突保护和卸载行为。
- 模拟现有分支、新分支和多 ref push，核对 base/head/ref/profile 摘要。
- 验证脏工作区和无法解析远端状态时 push 被阻断且原配置保持不变。

## Out of Scope

- 实际 push、托管 CI 配置和强制执行双拓扑场景门禁。
