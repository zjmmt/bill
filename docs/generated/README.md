# 生成文档目录

- 状态：已确认
- 所有者：项目维护者与仓库生成器
- 最后核验：2026-07-31
- 事实来源：`scripts/generate-repository-facts.ps1` 与本目录的自动生成文件

当前生成事实：

- [repository-facts.md](repository-facts.md)：Gradle 直接项目依赖、已提交 Room schema 版本、源 Manifest 权限/组件声明，以及测试源码文件和 `@Test` 注解计数。

运行 `cmd.exe /d /s /c powershell -NoProfile -ExecutionPolicy Bypass -File scripts\generate-repository-facts.ps1` 更新；运行同一命令并追加 `-Check` 只检查陈旧状态。生成文件包含生成器自身和输入文件的摘要，禁止手工编辑。

仍未生成的事实包括：Room 迁移图、由已注册适配器和脱敏回放证据推导的实际来源支持矩阵，以及定期文档健康报告。在相应生成器存在前，不手工创建这些文件，以免把计划伪装成实现事实。
