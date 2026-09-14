import Foundation
import Combine

/// 镜像文本框的模型:维护"已发送到电脑"的文本,并对每次编辑做增量 diff,
/// 只把"删了几个字符 + 插入了什么"发给主机端。
final class KeyboardMirror: ObservableObject {
    @Published var mirrorText = ""

    /// true = 剪贴板粘贴注入(网页/富文本编辑器); false = 逐字注入(原生窗口)
    var usePaste = false

    private var committed = ""
    private let client: QuietTypeClient

    init(client: QuietTypeClient) {
        self.client = client
    }

    /// 电脑端内容变化/切换窗口后,点"镜像同步"调用。
    func reset() {
        mirrorText = ""
        committed = ""
    }

    /// UITextView 每次真实改动(输入法已提交)后回调。
    func commit(_ newText: String) {
        guard newText != committed else { return }
        let (removed, inserted) = Self.diff(committed, newText)
        if removed > 0 { client.send(["t": "bs", "n": removed]) }
        if !inserted.isEmpty {
            client.send(usePaste ? ["t": "paste", "s": inserted] : ["t": "txt", "s": inserted])
        }
        committed = newText
    }

    /// 按 UTF-16 码元做公共前缀/后缀裁剪,得出需要删除的数量与插入文本。
    static func diff(_ a: String, _ b: String) -> (removed: Int, inserted: String) {
        let ao = Array(a.utf16)
        let bo = Array(b.utf16)
        var p = 0
        while p < ao.count && p < bo.count && ao[p] == bo[p] { p += 1 }
        var q = 0
        while q < ao.count - p && q < bo.count - p && ao[ao.count - 1 - q] == bo[bo.count - 1 - q] { q += 1 }
        let removed = (ao.count - p) - q
        let ins = Array(bo[p..<(bo.count - q)])
        return (removed, String(utf16CodeUnits: ins, count: ins.count))
    }
}
