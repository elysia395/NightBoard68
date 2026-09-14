import SwiftUI

/// 连接成功后的主界面:键盘 / 触控板两个面板。
struct RemoteView: View {
    @ObservedObject var client: QuietTypeClient
    var onDisconnect: () -> Void

    @State private var tab: Tab = .keyboard
    @StateObject private var mirror: KeyboardMirror
    @State private var gain: Double = 2.5

    enum Tab: String, CaseIterable, Identifiable {
        case keyboard = "⌨️ 键盘"
        case touchpad = "🖱️ 触控板"
        var id: String { rawValue }
    }

    init(client: QuietTypeClient, onDisconnect: @escaping () -> Void) {
        self.client = client
        self.onDisconnect = onDisconnect
        _mirror = StateObject(wrappedValue: KeyboardMirror(client: client))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Picker("面板", selection: $tab) {
                ForEach(Tab.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            if tab == .keyboard { keyboardPane } else { touchpadPane }
        }
    }

    private var header: some View {
        HStack {
            Circle()
                .fill(client.connected ? Color.green : Color.red)
                .frame(width: 9, height: 9)
            Text(client.connected ? "已连接 \(client.serverName)" : "未连接")
                .font(.footnote)
                .foregroundColor(.secondary)
            Spacer()
            Button("断开") { onDisconnect() }
                .font(.footnote)
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
    }

    // MARK: 键盘面板

    private var keyboardPane: some View {
        ScrollView {
            VStack(spacing: 8) {
                HStack {
                    Text("在此输入,实时敲进电脑")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                    Button(mirror.usePaste ? "文本:粘贴" : "文本:逐字") {
                        mirror.usePaste.toggle()
                    }
                    .font(.caption)
                    Button("镜像同步") { mirror.reset() }
                        .font(.caption)
                }

                MirrorTextView(text: $mirror.mirrorText,
                               onCommit: { mirror.commit($0) })
                    .frame(height: 190)
                    .overlay(RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.secondary.opacity(0.4), lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                Text("电脑端文本被改动(撤销/切窗口)后,先点「镜像同步」再继续输入")
                    .font(.caption2)
                    .foregroundColor(.secondary)

                VStack(spacing: 8) {
                    ForEach(Array(keyRows.enumerated()), id: \.offset) { _, row in
                        HStack(spacing: 6) {
                            ForEach(row) { item in
                                Button {
                                    switch item.kind {
                                    case .direct(let k):
                                        client.send(["t": "key", "k": k, "mods": []])
                                    case .combo(let k):
                                        client.send(["t": "key", "k": k, "mods": ["ctrl"]])
                                    }
                                } label: {
                                    Text(item.label)
                                        .font(.system(size: 14))
                                        .frame(maxWidth: .infinity, minHeight: 40)
                                }
                                .buttonStyle(.bordered)
                                .tint(item.kind.isCombo ? .green : .blue)
                            }
                        }
                    }
                }
                .padding(.vertical, 4)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .scrollIndicators(.hidden)
    }

    private struct KeyItem: Identifiable {
        let id = UUID()
        let label: String
        let kind: Kind
        enum Kind {
            case direct(String)
            case combo(String)
            var isCombo: Bool {
                if case .combo = self { return true }
                return false
            }
        }
    }

    private var keyRows: [[KeyItem]] {
        [
            [.init(label: "⇥ Tab", kind: .direct("tab")),
             .init(label: "⎋ Esc", kind: .direct("esc")),
             .init(label: "⏎ 回车", kind: .direct("enter")),
             .init(label: "⌫ 删除", kind: .direct("backspace")),
             .init(label: "Del", kind: .direct("delete"))],
            [.init(label: "↖ Home", kind: .direct("home")),
             .init(label: "↑", kind: .direct("up")),
             .init(label: "↘ End", kind: .direct("end")),
             .init(label: "空格", kind: .direct("space"))],
            [.init(label: "←", kind: .direct("left")),
             .init(label: "↓", kind: .direct("down")),
             .init(label: "→", kind: .direct("right")),
             .init(label: "⇞ PgUp", kind: .direct("pgup")),
             .init(label: "⇟ PgDn", kind: .direct("pgdn"))],
            [.init(label: "↶ 撤销", kind: .combo("z")),
             .init(label: "↷ 重做", kind: .combo("y")),
             .init(label: "全选", kind: .combo("a")),
             .init(label: "保存", kind: .combo("s"))],
            [.init(label: "复制", kind: .combo("c")),
             .init(label: "剪切", kind: .combo("x")),
             .init(label: "粘贴", kind: .combo("v"))],
        ]
    }

    // MARK: 触控板面板

    private var touchpadPane: some View {
        VStack(spacing: 10) {
            TouchpadView(client: client, gain: gain)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.secondary.opacity(0.4), lineWidth: 1))
                .padding(.horizontal, 16)

            HStack(spacing: 8) {
                padButton("左键") { mouseClick("left") }
                padButton("右键") { mouseClick("right") }
                padButton("滚轮上") { client.send(["t": "wl", "v": 3]) }
                padButton("滚轮下") { client.send(["t": "wl", "v": -3]) }
            }
            .padding(.horizontal, 16)

            VStack(spacing: 2) {
                Text("灵敏度 \(String(format: "%.1f", gain))")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Slider(value: $gain, in: 0.5...8.0, step: 0.5)
            }
            .padding(.horizontal, 16)

            Text("单指滑动=移动 · 轻点=左键 · 双指轻点=右键 · 双指上下滑=滚轮")
                .font(.caption2)
                .foregroundColor(.secondary)

            Spacer(minLength: 0)
        }
        .padding(.top, 6)
    }

    private func padButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14))
                .frame(maxWidth: .infinity, minHeight: 42)
        }
        .buttonStyle(.bordered)
    }

    private func mouseClick(_ button: String) {
        client.send(["t": "mb", "b": button, "d": true])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.04) {
            client.send(["t": "mb", "b": button, "d": false])
        }
    }
}
