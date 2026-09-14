import SwiftUI
import UIKit

/// 包一层 UITextView,以拿到真实的 textViewDidChange 与中文输入法组合状态。
/// 输入法组合(markedText)期间不回传,组合结束才把最终文本交给 onCommit。
struct MirrorTextView: UIViewRepresentable {
    @Binding var text: String
    var onCommit: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.delegate = context.coordinator
        tv.font = UIFont.monospacedSystemFont(ofSize: 18, weight: .regular)
        tv.backgroundColor = UIColor.systemBackground
        tv.autocapitalizationType = .none
        tv.autocorrectionType = .no
        tv.spellCheckingType = .no
        tv.smartQuotesType = .no
        tv.smartDashesType = .no
        tv.smartInsertDeleteType = .no
        tv.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)
        tv.alwaysBounceVertical = true
        return tv
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        context.coordinator.parent = self
        if uiView.text != text { uiView.text = text }
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: MirrorTextView

        init(_ parent: MirrorTextView) { self.parent = parent }

        func textViewDidChange(_ tv: UITextView) {
            // markedTextRange != nil 表示还在中文输入法组合中
            guard tv.markedTextRange == nil else { return }
            parent.text = tv.text
            parent.onCommit(tv.text)
        }
    }
}
