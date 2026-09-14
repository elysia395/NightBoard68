import SwiftUI

/// 根视图:未连接 -> 连接页;已连接 -> 远程面板。
struct RootView: View {
    @StateObject private var client = QuietTypeClient()
    @State private var connected = false

    var body: some View {
        Group {
            if connected {
                RemoteView(client: client) {
                    client.disconnect()
                    connected = false
                }
            } else {
                ConnectView(client: client) {
                    connected = true
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: connected)
    }
}

/// 连接页:填电脑 IP 与端口(与电脑端 run.bat 显示的一致)。
struct ConnectView: View {
    @ObservedObject var client: QuietTypeClient
    var onConnected: () -> Void

    @AppStorage("qt_host") private var host = ""
    @AppStorage("qt_port") private var port = "8567"
    @State private var busy = false
    @State private var errorMsg: String?

    var body: some View {
        VStack(spacing: 20) {
            Spacer()

            VStack(spacing: 6) {
                Text("QuietType")
                    .font(.largeTitle.bold())
                Text("把 iPhone 变成 Windows 的静音键盘 / 触控板")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }

            VStack(spacing: 12) {
                TextField("电脑局域网 IP,如 192.168.1.5", text: $host)
                    .keyboardType(.numbersAndPunctuation)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                TextField("端口", text: $port)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)
            }
            .padding(.horizontal, 24)

            Button(action: connect) {
                Group {
                    if busy {
                        ProgressView()
                    } else {
                        Text("连接电脑").bold()
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(busy || host.trimmingCharacters(in: .whitespaces).isEmpty)
            .padding(.horizontal, 24)

            if let errorMsg {
                Text(errorMsg)
                    .font(.footnote)
                    .foregroundColor(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }

            Spacer()

            Text("电脑端先运行 run.bat,把提示里的 IP 填到上面。\n手机与电脑需在同一 Wi-Fi/局域网。")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.bottom, 12)
        }
        .padding(.vertical, 16)
    }

    private func connect() {
        busy = true
        errorMsg = nil
        Task { @MainActor in
            let ok = await client.connect(host: host, port: port)
            busy = false
            if ok {
                onConnected()
            } else {
                errorMsg = "连接失败:请检查 IP/端口、是否同一 Wi-Fi、防火墙是否放行端口。"
            }
        }
    }
}
