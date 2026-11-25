import SwiftUI

/// The test app: a self-test of the audio core on this device, and a manual test of one file.
/// It is a test screen for the spike, not the app's design; it uses no design tokens yet.
@main
struct ItsChoppedYoApp: App {
    var body: some Scene {
        WindowGroup {
            TestScreen()
        }
    }
}
