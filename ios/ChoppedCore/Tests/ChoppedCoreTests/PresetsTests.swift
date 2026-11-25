import Foundation
import XCTest
@testable import ChoppedCore

/// presets/presets.json and the screen text. android/core/src/test/.../PresetsTest.kt checks the
/// same values, so both apps show the same list and the same numbers.
final class PresetsTests: XCTestCase {
    func testTheSharedPresetsFileParses() throws {
        let url = SpecCasesTests.repoRoot.appendingPathComponent("presets/presets.json")
        let presets = try Presets.parse(Data(contentsOf: url))
        XCTAssertEqual(presets.map(\.id), ["wav-48k-16-mono", "wav-48k-16-stereo", "wav-48k-24-mono", "wav-48k-24-stereo"])
        let first = presets[0].settings(dither: true)
        XCTAssertEqual(first.mono, .sum)
        XCTAssertEqual(first.sampleRate, 48000)
        XCTAssertEqual(first.bits, 16)
        XCTAssertTrue(first.dither)
        XCTAssertEqual(first.format, .wav)
        XCTAssertNil(first.normalizeDB)
        XCTAssertEqual(presets[1].settings(dither: false).mono, .keep)
    }

    func testABadPresetsFileIsRejected() {
        let dup = #"{"presets":[{"id":"a","name":"A","sampleRate":48000,"bits":16,"channels":"mono","format":"wav"},{"id":"a","name":"B","sampleRate":48000,"bits":16,"channels":"mono","format":"wav"}]}"#
        XCTAssertThrowsError(try Presets.parse(Data(dup.utf8)))
        let bits = #"{"presets":[{"id":"a","name":"A","sampleRate":48000,"bits":12,"channels":"mono","format":"wav"}]}"#
        XCTAssertThrowsError(try Presets.parse(Data(bits.utf8)))
    }

    func testScreenText() {
        XCTAssertEqual(Describe.rate(48000), "48 kHz")
        XCTAssertEqual(Describe.rate(44100), "44.1 kHz")
        XCTAssertEqual(Describe.rate(46875), "46.875 kHz")
        XCTAssertEqual(Describe.rate(22050), "22.05 kHz")
        XCTAssertEqual(Describe.rate(8000), "8 kHz")
        XCTAssertEqual(Describe.rate(96001), "96.001 kHz")
        XCTAssertEqual(Describe.channels(1), "mono")
        XCTAssertEqual(Describe.channels(2), "stereo")
        XCTAssertEqual(Describe.channels(6), "6 ch")
        XCTAssertEqual(Describe.duration(frames: 9600, sampleRate: 96000), "0.10 s")
        XCTAssertEqual(Describe.duration(frames: 204_000, sampleRate: 48000), "4.25 s")
        XCTAssertEqual(Describe.duration(frames: 48000 * 65, sampleRate: 48000), "1:05")
        XCTAssertEqual(Describe.duration(frames: 48000 * 3600, sampleRate: 48000), "60:00")
        XCTAssertEqual(Describe.peak(Audio(sampleRate: 48000, channels: [[0, 0.5, -0.25]])), "peak -6.0 dBFS")
        XCTAssertEqual(Describe.peak(Audio(sampleRate: 48000, channels: [[0, -1.0]])), "peak 0.0 dBFS")
        XCTAssertEqual(Describe.peak(Audio(sampleRate: 48000, channels: [[0, 0]])), "silent")
        XCTAssertEqual(Describe.summary(Audio(sampleRate: 96000, channels: [[Double](repeating: 0, count: 9600), [Double](repeating: 0, count: 9600)])),
                       "96 kHz · stereo · 0.10 s")
    }
}
