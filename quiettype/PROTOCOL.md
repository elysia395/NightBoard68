# QuietType 通信协议 v1

手机端(网页或 iOS App)与 Windows 主机端通过 **HTTP POST JSON** 通信,
主机监听 `TCP 8567`(可 `--port` 改)。每条消息都必须带 `token`
(启动时随机生成;客户端先 `GET /api/info` 拿回)。

## 握手

```
GET /api/info
-> 200 {"name":"QuietType","token":"ab12cd","version":1}
```

## 注入消息 (POST /api/input)

通用外壳:`{"token":"ab12cd", ...消息字段...}`

| t     | 含义                     | 附带字段                          | 主机端动作                    |
|-------|--------------------------|-----------------------------------|-------------------------------|
| txt   | 逐字插入文本             | `s`: 要“打出来”的字符串           | ASCII 逐字符注入;\n→回车,\t→Tab;非 ASCII 段走剪贴板粘贴(保证中文) |
| paste | 整段粘贴(网页编辑器用)  | `s`: 文本                         | 写剪贴板 + Ctrl+V;网页/富文本编辑器(Lexical 等)只认粘贴 |
| bs    | 删除                     | `n`: 退格次数                     | Backspace × n                |
| key   | 按键(直发)              | `k`: 键名,`mods`: ["ctrl",...]    | 带修饰键按下松开              |
| mv    | 鼠标相对移动             | `x`,`y`: 像素                     | SendInput 相对移动           |
| mb    | 鼠标按键                 | `b`: left/right/middle,`d`: down  | 按下或抬起                    |
| wl    | 滚轮                     | `v`: 正=上,负=下;一格=120 的倍数  | 滚动                          |

键名 `k` 支持:`backspace tab enter esc space pgup pgdn home end left right up
down delete shift ctrl alt win`、`f1..f24`、单字母 `a..z`。
修饰键 `mods` 支持:`ctrl alt shift win`。

## 客户端增量规则(重要)

手机端维护一个“镜像文本框” `M`,并记住上一次已发送文本 `C`。
每次真实编辑后:

1. 对 `C -> M` 做 UTF-16 公共前缀/后缀裁剪:
   - `removed` = 中间被替换/删除的旧字符数
   - `inserted` = 新插入的字符串
2. 依次发送 `{"t":"bs","n":removed}`(removed>0 时)与
   `{"t":"txt","s":inserted}`(非空时),然后 `C = M`。

这样电脑端编辑器里每次改动表现为“退格若干字符 + 敲入若干字符”,
撤销历史基本保持自然。

## 镜像不同步的处理

主机端是无状态的;若电脑端文本被**手机以外的方式**改动(撤销、切窗口、
别处粘贴),镜像 `M` 与电脑真实内容会脱节。此时客户端提供「镜像同步」:
把 `M` 与 `C` 一起清空,之后从电脑当前光标处继续追加输入。

## 备注

- 客户端应保持同一 HTTP 连接(单连接)以免乱序;TCP 保序。
- token 仅防局域网误连,不做强安全;本工具面向自用局域网场景。
