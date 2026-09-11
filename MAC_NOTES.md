# NightBoard68-mac 分支说明

本分支用于 mac 相关适配工作：macOS 侧的适配改动先落在本分支，稳定后合回 `main`。

## 为什么之前无法对本分支发起 PR

本分支创建时与 `main` **完全一致**（没有任何提交差异）。GitHub 对没有差异的
分支一律提示 *"There isn't anything to compare"*，无论从哪个方向都建不出 PR。

现在本分支已包含初始化提交（即本文件），PR 通道已恢复。

## 如何向本分支提交 PR

1. Fork 本仓库并 clone 你的 fork
2. 从本分支拉出工作分支（你的 fork 默认只有 main，需要手动从上游取）：

   ```bash
   git remote add upstream https://github.com/elysia395/NightBoard68.git
   git fetch upstream NightBoard68-mac
   git checkout -b my-mac-work upstream/NightBoard68-mac
   # 开发、提交……
   git push -u origin my-mac-work        # 推到你自己的 fork
   ```

3. 在 GitHub 上发起 PR：base 仓库选 `elysia395/NightBoard68`，
   **base 分支选 `NightBoard68-mac`**，head 选你 fork 里的工作分支。

## 状态

- 分支起点：`main` @ `bbac389`（v1.3.2-test2 同源）
- 键盘主体 / 通用改动请仍以 `main` 为 base，详见 `CONTRIBUTING.md`
