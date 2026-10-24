#!/usr/bin/env swift
import AppKit
import CoreGraphics
import Foundation

// Reproduce app/src/main/res/drawable/ic_launcher.xml without external tools.
// Usage: swift desktop/scripts/icon.swift [output-assets-directory]
let scriptURL = URL(fileURLWithPath: #filePath).standardizedFileURL
let outputURL = CommandLine.arguments.count > 1
    ? URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
    : scriptURL.deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("assets", isDirectory: true)
let files = FileManager.default
let temporaryURL = files.temporaryDirectory.appendingPathComponent("jianji-icon-\(UUID().uuidString)", isDirectory: true)
let iconsetURL = temporaryURL.appendingPathComponent("app.iconset", isDirectory: true)

func color(_ red: Int, _ green: Int, _ blue: Int, alpha: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat(red) / 255, green: CGFloat(green) / 255, blue: CGFloat(blue) / 255, alpha: alpha)
}

func png(size: Int) throws -> Data {
    guard let space = CGColorSpace(name: CGColorSpace.sRGB),
          let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                                  bytesPerRow: size * 4, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
        throw NSError(domain: "JianjiIcon", code: 1, userInfo: [NSLocalizedDescriptionKey: "无法创建图标画布"])
    }
    context.setAllowsAntialiasing(true)
    context.setShouldAntialias(true)
    context.scaleBy(x: CGFloat(size) / 1024, y: CGFloat(size) / 1024)
    context.translateBy(x: 0, y: 1024)
    context.scaleBy(x: 1, y: -1)

    // macOS icon canvas: transparent margin, gently rounded blue tile.
    let blue = color(0x4D, 0x6B, 0xFE)
    let tile = CGPath(roundedRect: CGRect(x: 80, y: 80, width: 864, height: 864),
                      cornerWidth: 196, cornerHeight: 196, transform: nil)
    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: 10), blur: 18, color: color(0, 0, 0, alpha: 0.16))
    context.setFillColor(blue)
    context.addPath(tile)
    context.fillPath()
    context.restoreGState()

    // The original Android vector has a 108 × 108 viewport.
    context.translateBy(x: 80, y: 80)
    context.scaleBy(x: 8, y: 8)
    let paper = CGMutablePath()
    paper.move(to: CGPoint(x: 38, y: 28))
    paper.addLine(to: CGPoint(x: 58, y: 28))
    paper.addLine(to: CGPoint(x: 76, y: 46))
    paper.addLine(to: CGPoint(x: 76, y: 74))
    paper.addQuadCurve(to: CGPoint(x: 70, y: 80), control: CGPoint(x: 76, y: 80))
    paper.addLine(to: CGPoint(x: 38, y: 80))
    paper.addQuadCurve(to: CGPoint(x: 32, y: 74), control: CGPoint(x: 32, y: 80))
    paper.addLine(to: CGPoint(x: 32, y: 34))
    paper.addQuadCurve(to: CGPoint(x: 38, y: 28), control: CGPoint(x: 32, y: 28))
    paper.closeSubpath()
    context.setFillColor(color(255, 255, 255))
    context.addPath(paper)
    context.fillPath()

    let fold = CGMutablePath()
    fold.move(to: CGPoint(x: 58, y: 28))
    fold.addLine(to: CGPoint(x: 58, y: 41))
    fold.addQuadCurve(to: CGPoint(x: 63, y: 46), control: CGPoint(x: 58, y: 46))
    fold.addLine(to: CGPoint(x: 76, y: 46))
    fold.closeSubpath()
    context.setFillColor(color(0xDD, 0xE3, 0xFF))
    context.addPath(fold)
    context.fillPath()

    context.setStrokeColor(blue)
    context.setLineWidth(3.5)
    context.setLineCap(.round)
    for (y, endX): (CGFloat, CGFloat) in [(51, 61), (60, 65), (69, 55)] {
        context.move(to: CGPoint(x: 42, y: y))
        context.addLine(to: CGPoint(x: endX, y: y))
    }
    context.strokePath()
    guard let image = context.makeImage(),
          let data = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else {
        throw NSError(domain: "JianjiIcon", code: 2, userInfo: [NSLocalizedDescriptionKey: "无法导出 PNG 图标"])
    }
    return data
}

do {
    try files.createDirectory(at: outputURL, withIntermediateDirectories: true)
    try files.createDirectory(at: iconsetURL, withIntermediateDirectories: true)
    defer { try? files.removeItem(at: temporaryURL) }
    let variants: [(String, Int)] = [
        ("icon_16x16.png", 16), ("icon_16x16@2x.png", 32),
        ("icon_32x32.png", 32), ("icon_32x32@2x.png", 64),
        ("icon_128x128.png", 128), ("icon_128x128@2x.png", 256),
        ("icon_256x256.png", 256), ("icon_256x256@2x.png", 512),
        ("icon_512x512.png", 512), ("icon_512x512@2x.png", 1024)
    ]
    for (name, size) in variants {
        try png(size: size).write(to: iconsetURL.appendingPathComponent(name), options: .atomic)
    }
    try png(size: 1024).write(to: outputURL.appendingPathComponent("app.png"), options: .atomic)
    let process = Process()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
    process.arguments = ["-c", "icns", "-o", outputURL.appendingPathComponent("app.icns").path, iconsetURL.path]
    try process.run()
    process.waitUntilExit()
    guard process.terminationStatus == 0 else {
        throw NSError(domain: "JianjiIcon", code: Int(process.terminationStatus), userInfo: [NSLocalizedDescriptionKey: "iconutil 导出失败"])
    }
    print("图标已生成：\(outputURL.path)/app.png、app.icns")
} catch {
    fputs("图标生成失败：\(error.localizedDescription)\n", stderr)
    exit(1)
}
