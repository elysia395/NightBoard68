import SwiftUI
import UIKit

/// 触控板:UIKit 层自己处理多点触摸(和网页版同样的手势语义)。
struct TouchpadView: UIViewRepresentable {
    let client: QuietTypeClient
    var gain: Double

    func makeUIView(context: Context) -> TouchpadUIView {
        let v = TouchpadUIView(client: client)
        v.gain = CGFloat(gain)
        return v
    }

    func updateUIView(_ uiView: TouchpadUIView, context: Context) {
        uiView.gain = CGFloat(gain)
    }
}

final class TouchpadUIView: UIView {
    private let client: QuietTypeClient
    var gain: CGFloat = 2.5

    private var active: [UITouch: CGPoint] = [:]
    private var started: [UITouch: TimeInterval] = [:]
    private var movedTotal: [UITouch: CGFloat] = [:]
    private var lastSingle: CGPoint?
    private var twoCentroid: CGPoint?
    private var wheelAcc: CGFloat = 0

    init(client: QuietTypeClient) {
        self.client = client
        super.init(frame: .zero)
        isMultipleTouchEnabled = true
        backgroundColor = UIColor.secondarySystemBackground
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    // MARK: touches

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            active[t] = t.location(in: self)
            started[t] = t.timestamp
            movedTotal[t] = 0
        }
        updateTwoCentroid()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            guard var p = active[t] else { continue }
            let np = t.location(in: self)
            let dx = np.x - p.x, dy = np.y - p.y
            movedTotal[t, default: 0] += abs(dx) + abs(dy)
            p = np
            active[t] = p
        }
        if active.count == 1 {
            // 单指 -> 鼠标相对移动
            guard let p = active.first?.value else { return }
            if let last = lastSingle {
                let dx = (p.x - last.x) * gain
                let dy = (p.y - last.y) * gain
                client.send(["t": "mv", "x": Int(dx.rounded()), "y": Int(dy.rounded())])
            }
            lastSingle = p
            twoCentroid = nil
        } else if active.count == 2 {
            // 双指纵向 -> 滚轮
            lastSingle = nil
            let c = centroid()
            if let prev = twoCentroid {
                wheelAcc += c.y - prev.y
                let step = Int(wheelAcc / 40)
                if step != 0 {
                    wheelAcc -= CGFloat(step) * 40
                    client.send(["t": "wl", "v": step])
                }
            }
            twoCentroid = c
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        let was = active.count
        var ended: [(dt: TimeInterval, moved: CGFloat)] = []
        for t in touches {
            if let s = started[t] {
                ended.append((t.timestamp - s, movedTotal[t] ?? 0))
            }
            active.removeValue(forKey: t)
            started.removeValue(forKey: t)
            movedTotal.removeValue(forKey: t)
        }
        lastSingle = nil
        if was == 1, ended.count == 1, ended[0].dt < 0.25, ended[0].moved < 12 {
            click("left")                       // 轻点 = 左键
        } else if was == 2, ended.count == 2,
                  ended.allSatisfy({ $0.dt < 0.3 && $0.moved < 12 }) {
            click("right")                      // 双指轻点 = 右键
        }
        updateTwoCentroid()
        if active.count < 2 { twoCentroid = nil; wheelAcc = 0 }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            active.removeValue(forKey: t)
            started.removeValue(forKey: t)
            movedTotal.removeValue(forKey: t)
        }
        lastSingle = nil
        twoCentroid = nil
        wheelAcc = 0
    }

    // MARK: helpers

    private func centroid() -> CGPoint {
        var x: CGFloat = 0, y: CGFloat = 0
        for p in active.values { x += p.x; y += p.y }
        let n = CGFloat(max(active.count, 1))
        return CGPoint(x: x / n, y: y / n)
    }

    private func updateTwoCentroid() {
        twoCentroid = (active.count == 2) ? centroid() : nil
    }

    private func click(_ button: String) {
        client.send(["t": "mb", "b": button, "d": true])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.04) { [weak self] in
            self?.client.send(["t": "mb", "b": button, "d": false])
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }
}
