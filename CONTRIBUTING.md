# 贡献指南

## 提交 PR 的标准流程

1. Fork 本仓库并 clone 你的 fork
2. 从目标分支拉出 topic 分支开发（不要直接在 `main` / `NightBoard68-mac` 上改）：

   ```bash
   git remote add upstream https://github.com/elysia395/NightBoard68.git
   git fetch upstream
   git checkout -b my-topic upstream/main        # 或 upstream/NightBoard68-mac
   # 开发、提交……
   git push -u origin my-topic                   # 推到你自己的 fork
   ```

3. 在 GitHub 上发起 PR：**base 仓库选 `elysia395/NightBoard68`**，按改动内容选 base 分支：

   | 改动内容 | base 分支 |
   |---|---|
   | 键盘主体 / 触控板 / 通用改动 | `main` |
   | mac 相关适配 | `NightBoard68-mac`（分支说明见该分支的 `MAC_NOTES.md`） |

## 常见问题

- **"There isn't anything to compare"**：两边没有提交差异时 GitHub 拒绝建 PR。
  确认 head 分支里有新提交、base 分支选对了。
- **你的 fork 里没有 `NightBoard68-mac`**：fork 默认只带 main 分支，按上面的
  `git fetch upstream NightBoard68-mac` 方式把工作分支基到上游分支再推回 fork。

## 构建与签名

- 纯 Kotlin + Android framework，零第三方依赖；提交前 `gradle assembleDebug`
  通过即可
- 签名策略见 [#4](https://github.com/elysia395/NightBoard68/issues/4)：
  协作者本地构建与官方 Release 构建签名不同，互相覆盖安装需卸载重装
