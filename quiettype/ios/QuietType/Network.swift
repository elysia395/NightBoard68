import Foundation

/// 与 Windows 主机端通信的客户端。协议见 PROTOCOL.md。
final class QuietTypeClient: ObservableObject {
    struct Info: Codable {
        let name: String
        let token: String
        let version: Int
    }

    @Published private(set) var connected = false
    @Published private(set) var serverName = ""

    private var baseURL: URL?
    private var token = ""
    private let session: URLSession

    init() {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpMaximumConnectionsPerHost = 1   // 单连接,保证消息顺序
        cfg.timeoutIntervalForRequest = 4
        cfg.timeoutIntervalForResource = 4
        session = URLSession(configuration: cfg)
    }

    /// 尝试连接: GET /api/info 成功即视为连接成功,并取回本次运行令牌。
    @MainActor
    func connect(host: String, port: String) async -> Bool {
        let h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let p = UInt16(port.trimmingCharacters(in: .whitespacesAndNewlines)),
              !h.isEmpty,
              !h.contains("://"),
              let base = URL(string: "http://\(h):\(p)") else { return false }
        var req = URLRequest(url: base.appendingPathComponent("api/info"))
        req.timeoutInterval = 3
        do {
            let (data, resp) = try await session.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode == 200 else { return false }
            let info = try JSONDecoder().decode(Info.self, from: data)
            baseURL = base
            token = info.token
            serverName = info.name
            connected = true
            return true
        } catch {
            connected = false
            return false
        }
    }

    @MainActor
    func disconnect() {
        connected = false
        baseURL = nil
        token = ""
    }

    /// 发送一条协议消息(异步,顺序由单连接保证)。
    func send(_ dict: [String: Any]) {
        guard let baseURL, connected else { return }
        var d = dict
        d["token"] = token
        guard let body = try? JSONSerialization.data(withJSONObject: d) else { return }
        var req = URLRequest(url: baseURL.appendingPathComponent("api/input"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        session.dataTask(with: req) { [weak self] _, resp, _ in
            guard let self, let resp = resp as? HTTPURLResponse, resp.statusCode == 403 else { return }
            DispatchQueue.main.async { self.connected = false }
        }.resume()
    }
}
