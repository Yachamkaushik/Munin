// Renders every document in data/manifest.json as a screenshot-like PNG (macOS text shaping handles Hindi/Telugu).
// Usage: swift tools/eval/render_images.swift tools/eval/data/manifest.json tools/eval/images
// Every image carries a "SYNTHETIC SAMPLE" footer. These are test fixtures, never real documents.
import AppKit

let args = CommandLine.arguments
let manifestPath = args.count > 1 ? args[1] : "tools/eval/data/manifest.json"
let outDir = args.count > 2 ? args[2] : "tools/eval/images"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

let json = try! JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: manifestPath))) as! [String: Any]
let docs = json["docs"] as! [[String: Any]]
let width = 900, height = 1600

for doc in docs {
    let id = doc["id"] as! String
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                               isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSColor(calibratedWhite: 0.97, alpha: 1).setFill()
    NSRect(x: 0, y: 0, width: width, height: height).fill()
    var y = CGFloat(height) - 180
    var lines: [(String, String)] = (doc["lines"] as! [Any]).map { l in
        if let s = l as? String { return (s, "") }
        let a = l as! [String]; return (a[0], a[1])
    }
    lines.append(("SYNTHETIC SAMPLE - not a real document", "small"))
    for (text, style) in lines {
        let size: CGFloat = style == "big" ? 78 : style == "b" ? 50 : style == "small" ? 28 : 42
        let font = (style == "b" || style == "big") ? NSFont.boldSystemFont(ofSize: size) : NSFont.systemFont(ofSize: size)
        let s = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor.black])
        let h = size * 1.9
        s.draw(in: NSRect(x: 56, y: y - h, width: CGFloat(width) - 112, height: h))
        y -= h + 18
    }
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(outDir)/\(id).png"))
}
print("rendered \(docs.count) images to \(outDir)")
