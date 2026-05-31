//
//  OverlayRenderer.swift
//  Burn-in timestamp + GPS overlay implemented as a CIFilter on the capture pipeline. iOS makes
//  this so much cleaner than Android: we just render the text to a CIImage and composite over
//  every captured frame.
//

import Foundation
import CoreImage
import CoreImage.CIFilterBuiltins
import HaishinKit
import UIKit
import CoreLocation

@MainActor
final class OverlayRenderer {
    /// Latest GPS fix injected by LocationService. nil = "no GPS" in the overlay.
    var lastLocation: CLLocation?
    /// Bound to the TEXT_OVERLAY pref; the filter no-ops when false.
    var enabled: Bool { UserDefaults.standard.bool(forKey: Pref.textOverlay) }
    var utc: Bool { UserDefaults.standard.object(forKey: Pref.textOverlayUTC) as? Bool ?? PrefDefaults.textOverlayUTC }

    private let ciContext = CIContext()
    private lazy var dateFormatterLocal: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"; return f
    }()
    private lazy var dateFormatterUTC: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss'Z'"; f.timeZone = TimeZone(identifier: "UTC"); return f
    }()

    /// HaishinKit `VideoEffect` that composites the overlay onto each frame. Lazily holds onto
    /// the same instance so HaishinKit's filter chain stays stable.
    private(set) lazy var filterEffect: any VideoEffect = OverlayEffect(renderer: self)

    fileprivate func overlay(on input: CIImage) -> CIImage {
        guard enabled else { return input }
        let text = buildText()
        let textImage = renderText(text)
        // Position: bottom-left, with a small inset. iOS CIImage origin is bottom-left so
        // y=inset is what we want for "near the bottom".
        let inset: CGFloat = 16
        let positioned = textImage.transformed(by: .init(translationX: inset, y: inset))
        return positioned.composited(over: input)
    }

    private func buildText() -> String {
        let stamp = utc ? dateFormatterUTC.string(from: Date()) : dateFormatterLocal.string(from: Date())
        if let l = lastLocation {
            return String(format: "%@  %.4f,%.4f", stamp, l.coordinate.latitude, l.coordinate.longitude)
        }
        return stamp
    }

    /// Render the overlay text into a CIImage with a semi-transparent black background pill so
    /// it reads against any scene.
    private func renderText(_ text: String) -> CIImage {
        let font = UIFont.monospacedSystemFont(ofSize: 28, weight: .semibold)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor.white
        ]
        let attributed = NSAttributedString(string: text, attributes: attrs)
        let size = attributed.size()
        let pad: CGFloat = 8
        let bgSize = CGSize(width: size.width + pad * 2, height: size.height + pad * 2)

        let renderer = UIGraphicsImageRenderer(size: bgSize)
        let uiImage = renderer.image { ctx in
            UIColor.black.withAlphaComponent(0.6).setFill()
            UIBezierPath(roundedRect: CGRect(origin: .zero, size: bgSize), cornerRadius: 6).fill()
            attributed.draw(at: CGPoint(x: pad, y: pad))
        }
        return CIImage(image: uiImage) ?? CIImage()
    }
}

/// HaishinKit `VideoEffect` adapter — calls back into the renderer per frame.
private final class OverlayEffect: VideoEffect, @unchecked Sendable {
    let renderer: OverlayRenderer
    init(renderer: OverlayRenderer) { self.renderer = renderer; super.init() }
    override func execute(_ image: CIImage, info: CMSampleBuffer?) -> CIImage {
        // Capture pipeline isn't on the main actor; bounce to it to read the overlay state.
        var out = image
        if Thread.isMainThread {
            out = MainActor.assumeIsolated { renderer.overlay(on: image) }
        } else {
            DispatchQueue.main.sync {
                out = MainActor.assumeIsolated { renderer.overlay(on: image) }
            }
        }
        return out
    }
}
