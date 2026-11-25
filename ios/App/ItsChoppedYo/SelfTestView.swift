import ChoppedCore
import ChoppedSpecRunner
import IAMJARLDesignTokens
import SwiftUI
import UIKit

/// Runs every case in testdata on this phone and compares each output's SHA-256 with the
/// reference. The same runner as the XCTest suite and the Android app.
struct SelfTestView: View {
    @State private var running = false
    @State private var summary: String?
    @State private var passed = false
    @State private var problems: [String] = []

    var body: some View {
        List {
            Section {
                Button(running ? "Running…" : "Run the self-test") { run() }
                    .disabled(running)
                    .accessibilityIdentifier("selfTestButton")
                if let summary {
                    Text(summary)
                        .foregroundStyle(passed ? DesignTokens.Palette.State.success : DesignTokens.Palette.State.error)
                        .accessibilityIdentifier("selfTestResult")
                }
                ForEach(problems, id: \.self) {
                    Text($0).font(.token(.xs)).foregroundStyle(DesignTokens.Palette.State.error)
                }
            } footer: {
                Text("Converts every test file on this phone and checks that each result is exactly what the reference gives, byte for byte.")
            }
        }
        .navigationTitle("Self-test")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func run() {
        running = true
        summary = nil
        problems = []
        let device = "\(UIDevice.current.model), iOS \(UIDevice.current.systemVersion)"
        Task.detached(priority: .userInitiated) {
            let started = Date()
            let outcome: (String, Bool, [String])
            do {
                let r = try SpecRunner.runAll(
                    casesJSON: try resource("cases.json"),
                    expectedJSON: try resource("expected.json"),
                    readInput: { Array(try resource($0)) }
                )
                let tenths = Int(Date().timeIntervalSince(started) * 10)
                let line = "\(r.checked - r.mismatches.count) of \(r.expectedTotal) files match the reference (\(device), \(tenths / 10).\(tenths % 10) s)"
                outcome = (line, r.passed, r.nameProblems + r.mismatches)
            } catch {
                outcome = ("The self-test stopped: \(error)", false, [])
            }
            await MainActor.run {
                summary = outcome.0
                passed = outcome.1
                problems = outcome.2
                running = false
            }
        }
    }
}

private func resource(_ name: String) throws -> Data {
    let parts = name.split(separator: ".", maxSplits: 1).map(String.init)
    guard let url = Bundle.main.url(forResource: parts[0], withExtension: parts.count > 1 ? parts[1] : nil) else {
        throw CocoaError(.fileNoSuchFile, userInfo: [NSFilePathErrorKey: name])
    }
    return try Data(contentsOf: url)
}
