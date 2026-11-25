import SwiftUI

/// It's chopped, yo! on iOS. The free flow: import one WAV or AIFF file, pick a built-in format
/// preset, play the result, save it to a folder or share it. Everything Pro adds is shown with a
/// badge and opens the Pro sheet (hub `ItsChoppedYo/screen-flow.md`). Colours, spacing and type come
/// from the IAMJARL design tokens (the IAMJARLDesignTokens package, iamjarl-design 1.38.0).
@main
struct ItsChoppedYoApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
