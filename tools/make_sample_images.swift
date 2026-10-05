// Renders synthetic test screenshots with macOS text shaping (Pillow here lacks Indic shaping).
// Usage: swift tools/make_sample_images.swift tools/sample_images
// Every image carries a "SYNTHETIC SAMPLE" footer. These are test fixtures, never real documents.
import AppKit

let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "tools/sample_images"
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)

func render(_ name: String, width: Int = 1080, height: Int = 1920, lines: [(String, CGFloat, Bool)], footer: Bool = true) {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSColor(calibratedWhite: 0.97, alpha: 1).setFill()
    NSRect(x: 0, y: 0, width: width, height: height).fill()
    var y = CGFloat(height) - 220
    for (text, size, bold) in lines + (footer ? [("SYNTHETIC SAMPLE - not a real document", 34, false)] : []) {
        let font = bold ? NSFont.boldSystemFont(ofSize: size) : NSFont.systemFont(ofSize: size)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
        let s = NSAttributedString(string: text, attributes: attrs)
        let h = size * 1.9
        s.draw(in: NSRect(x: 70, y: y - h, width: CGFloat(width) - 140, height: h))
        y -= h + 24
    }
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(out)/\(name).png"))
}

render("sample_en_hostel_fee", lines: [
    ("Hostel Fee Receipt", 70, true), ("Student: Ravi Kumar", 52, false), ("Semester: Second semester 2026", 52, false),
    ("Amount paid: Rs 45,000", 56, true), ("Paid on: 12 Sep 2026", 52, false), ("Transaction ID: 4821937560", 52, false)])
render("sample_en_electricity_bill", lines: [
    ("Electricity Bill", 70, true), ("Consumer: Ravi Kumar", 52, false), ("Amount due: ₹1,250", 56, true),
    ("Due date: 15/10/2026", 52, false), ("Pay at the nearest centre or online", 48, false)])
render("sample_en_upi_payment", lines: [
    ("Payment successful", 70, true), ("₹2,499", 110, true), ("Paid to Amazon Pay", 56, false),
    ("20 Sep 2026, 6:45 PM", 52, false), ("UPI Ref No: 612345678901", 52, false)])
render("sample_hi_hostel_fee", lines: [
    ("छात्रावास शुल्क रसीद", 70, true), ("छात्र: रवि कुमार", 52, false), ("जमा राशि: ₹45,000", 56, true),
    ("दिनांक: 12 सितंबर 2026", 52, false), ("लेन-देन संख्या: 4821937560", 52, false)])
render("sample_te_hostel_fee", lines: [
    ("హాస్టల్ ఫీజు రసీదు", 70, true), ("విద్యార్థి: రవి కుమార్", 52, false), ("చెల్లించిన మొత్తం: రూ. 45,000", 56, true),
    ("తేదీ: 12 సెప్టెంబర్ 2026", 52, false)])
render("sample_en_clinic_card", lines: [
    ("Apollo Clinic", 70, true), ("Appointment card", 52, false), ("Address: Road No 36, Jubilee Hills", 52, false),
    ("Hyderabad 500033", 52, false), ("Phone: 98765 43210", 56, true), ("Appointment: 20 Sep 2026, 10:30 AM", 52, false)])
// ---- synthetic UPI payment screenshots in three layouts (A: amount first, B: status first, C: label then payee) ----
func payLines(_ layout: Int, amount: String, payee: String, date: String, ref: String, status: String? = nil, received: Bool = false, hideDate: Bool = false) -> [(String, CGFloat, Bool)] {
    let d: [(String, CGFloat, Bool)] = hideDate ? [] : [(date, 48, false)]
    switch layout {
    case 0: return [("Google Pay", 54, true), (amount, 100, true), (status ?? "Completed", 52, false),
                    (received ? "From \(payee)" : "To \(payee)", 56, true)] + d + [("UPI transaction ID", 44, false), (ref, 48, false)]
    case 1: return [("PhonePe", 54, true), (status ?? "Payment Successful", 56, true), (amount, 100, true),
                    (received ? "Received from \(payee)" : "Paid to \(payee)", 56, false)] + d + [("UTR: \(ref)", 48, false), ("Debited from XXXX1234", 44, false)]
    default: return [("Paytm UPI", 54, true), (status ?? "Paid Successfully", 56, true), (received ? "Received from" : "Paid to", 50, false),
                     (payee, 56, true), (amount, 100, true)] + d + [("UPI Ref No: \(ref)", 48, false)]
    }
}
render("sample_pay_a_chaipoint", lines: payLines(0, amount: "₹ 180", payee: "Chai Point", date: "21 Sep 2026, 9:10 AM", ref: "618800112233"))
render("sample_pay_b_ravitea", lines: payLines(1, amount: "₹ 450", payee: "Ravi Tea Stall", date: "Sep 18, 2026 at 08:15 AM", ref: "609123456789"))
render("sample_pay_c_pharmacy", lines: payLines(2, amount: "₹ 1,275.50", payee: "Sunrise Pharmacy", date: "22 Sep 2026, 11:02 AM", ref: "609876543210"))
render("sample_pay_b_chaipoint_again", lines: payLines(1, amount: "₹180", payee: "Chai Point", date: "Sep 21, 2026 at 09:10 AM", ref: "618800112233"))
render("sample_pay_b_failed", lines: payLines(1, amount: "₹ 300", payee: "Metro Mart", date: "Sep 19, 2026 at 04:20 PM", ref: "611100223344", status: "Payment failed"))
render("sample_pay_a_received", lines: payLines(0, amount: "₹ 500", payee: "Priya Sharma", date: "23 Sep 2026, 2:00 PM", ref: "612200334455", status: "Money received", received: true))
render("sample_pay_a_nodate", lines: payLines(0, amount: "₹ 210", payee: "Campus Canteen", date: "", ref: "613300445566", hideDate: true))
render("sample_pay_c_aug_hotel", lines: payLines(2, amount: "₹ 3,200", payee: "Hotel Annapurna", date: "12 Aug 2026, 7:30 PM", ref: "607700112244"))
render("sample_pay_a_aug_chai", lines: payLines(0, amount: "₹ 95", payee: "Chai Point", date: "28 Aug 2026, 5:05 PM", ref: "607711223344"))
render("sample_no_text", lines: [], footer: false)
render("sample_tiny_icon", width: 96, height: 96, lines: [("Hi", 30, true)], footer: false)
print("wrote images to \(out)")
