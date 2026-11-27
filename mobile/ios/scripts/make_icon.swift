// SPDX-License-Identifier: AGPL-3.0-only
// Renders the AGY Remote app icon: the shell prompt `>_` drawn with the working indicator's
// comet gradient (violet tail → sky → mint head).
//
//   swift make_icon.swift <out-dir>
//
// Writes icon-light.png (white background), icon-clear.png (transparent background),
// icon-tinted.png (white glyph on transparent, for iOS tinted mode) and
// icon-foreground.png (transparent, padded for Android adaptive icons), all 1024×1024.
import AppKit
import CoreGraphics

let size: CGFloat = 1024
let violet = CGColor(srgbRed: 0.56, green: 0.42, blue: 1.00, alpha: 1)
let sky = CGColor(srgbRed: 0.22, green: 0.66, blue: 0.98, alpha: 1)
let mint = CGColor(srgbRed: 0.10, green: 0.82, blue: 0.72, alpha: 1)

func mix(_ a: CGColor, _ b: CGColor, _ t: CGFloat) -> CGColor {
    let x = a.components!, y = b.components!
    return CGColor(srgbRed: x[0] + (y[0] - x[0]) * t, green: x[1] + (y[1] - x[1]) * t,
                   blue: x[2] + (y[2] - x[2]) * t, alpha: 1)
}

/// Colour along the comet: 0 = tail (violet), 0.6 = sky, 1 = head (mint).
func ramp(_ u: CGFloat) -> CGColor {
    u <= 0.6 ? mix(violet, sky, u / 0.6) : mix(sky, mint, (u - 0.6) / 0.4)
}

enum Style { case light, clear, tinted, foreground }

func render(_ style: Style, to url: URL) {
    let cs = CGColorSpace(name: CGColorSpace.sRGB)!
    let ctx = CGContext(data: nil, width: Int(size), height: Int(size), bitsPerComponent: 8,
                        bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    // Flip to y-down so the glyph coordinates read naturally.
    ctx.translateBy(x: 0, y: size)
    ctx.scaleBy(x: 1, y: -1)
    if style == .light {
        ctx.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
    }
    // Android adaptive icons crop to the inner 66 %, so the artwork shrinks there.
    let scale: CGFloat = style == .foreground ? 0.66 : 1
    ctx.translateBy(x: size / 2, y: size / 2)
    ctx.scaleBy(x: scale, y: scale)
    ctx.translateBy(x: -size / 2, y: -size / 2)

    let tinted = style == .tinted

    // The `>_` glyph, filled with the comet gradient running diagonally across it.
    let glyph = CGMutablePath()
    glyph.move(to: CGPoint(x: 262, y: 318))
    glyph.addLine(to: CGPoint(x: 482, y: 512))
    glyph.addLine(to: CGPoint(x: 262, y: 706))
    glyph.move(to: CGPoint(x: 552, y: 706))
    glyph.addLine(to: CGPoint(x: 772, y: 706))
    let outline = glyph.copy(strokingWithWidth: 104, lineCap: .round, lineJoin: .round, miterLimit: 4)
    ctx.saveGState()
    ctx.addPath(outline)
    ctx.clip()
    if tinted {
        ctx.setFillColor(CGColor(gray: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
    } else {
        let gradient = CGGradient(colorsSpace: cs, colors: [violet, sky, mint] as CFArray,
                                  locations: [0, 0.55, 1])!
        ctx.drawLinearGradient(gradient, start: CGPoint(x: 230, y: 290), end: CGPoint(x: 800, y: 740),
                                   options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    }
    ctx.restoreGState()

    let image = ctx.makeImage()!
    let rep = NSBitmapImageRep(cgImage: image)
    try! rep.representation(using: .png, properties: [:])!.write(to: url)
}

let out = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? ".")
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
render(.light, to: out.appendingPathComponent("icon-light.png"))
render(.clear, to: out.appendingPathComponent("icon-clear.png"))
render(.tinted, to: out.appendingPathComponent("icon-tinted.png"))
render(.foreground, to: out.appendingPathComponent("icon-foreground.png"))
print("wrote icons to \(out.path)")
