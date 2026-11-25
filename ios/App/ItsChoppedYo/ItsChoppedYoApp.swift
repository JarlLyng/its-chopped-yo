import IAMJARLDesignTokens
import SwiftUI

/// The test app: a self-test of the audio core on this device, and a manual test of one file.
/// It is a test screen for the spike, not the app's design. Colours and type come from the IAMJARL
/// design tokens (the IAMJARLDesignTokens package, iamjarl-design 1.38.0).
@main
struct ItsChoppedYoApp: App {
    var body: some Scene {
        WindowGroup {
            TestScreen()
                .tint(DesignTokens.Palette.primary)
        }
    }
}
