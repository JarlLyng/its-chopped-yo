import IAMJARLDesignTokens
import SwiftUI
import UIKit

/// "The panel": the app looks like a small piece of studio gear. A display that is always dark
/// sits on a panel of keys with LEDs and small printed group labels. Every colour is a design token
/// (iamjarl-design 1.38.0): the panel follows light and dark, the display always uses the dark
/// theme's colours. Design: the canvas linked from hub `ItsChoppedYo/screen-flow.md`.
enum Panel {
    typealias T = DesignTokens

    static let background = T.Palette.Background.app
    static let key = T.Palette.Background.card
    static let keyBorder = T.Palette.Border.default
    static let rule = T.Palette.Border.subtle
    static let ledOff = T.Palette.Border.default

    static let display = T.ColorToken.Dark.Background.app
    static let displayText = T.ColorToken.Dark.primary
    static let displayText2 = T.ColorToken.Dark.Text.secondary
    static let displayText3 = T.ColorToken.Dark.Text.tertiary
    static let displayDim = T.ColorToken.Dark.primarySubtle
    static let displayInk = T.ColorToken.Dark.onPrimary
    static let playhead = T.ColorToken.Dark.Text.primary
    static let displayBorder = T.adaptive(light: T.ColorToken.Dark.Background.app, dark: T.ColorToken.Dark.Border.default)
}

extension Font {
    /// A step of the token type scale, as a Dynamic Type style so it follows the system text size.
    static func token(_ style: DesignTokens.Typography.Style) -> Font { .system(style.textStyle) }
    /// The same step in the monospaced face, for values and labels on keys.
    static func mono(_ style: DesignTokens.Typography.Style) -> Font { .system(style.textStyle, design: .monospaced) }
}

/// A screen of the app: the panel background, a scrolling column, no navigation bar.
struct PanelScreen<Content: View, Bottom: View>: View {
    private let content: Content
    private let bottom: Bottom

    init(@ViewBuilder content: () -> Content, @ViewBuilder bottom: () -> Bottom) {
        self.content = content()
        self.bottom = bottom()
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.lg) { content }
                    .padding(.horizontal, DesignTokens.Spacing.xl)
                    .padding(.vertical, DesignTokens.Spacing.lg)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            bottom
                .padding(.horizontal, DesignTokens.Spacing.xl)
                .padding(.vertical, DesignTokens.Spacing.sm)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Panel.background)
        .background(SwipeBack().frame(width: 0, height: 0))
        .foregroundStyle(DesignTokens.Palette.Text.primary)
        .toolbar(.hidden, for: .navigationBar)
    }
}

extension PanelScreen where Bottom == EmptyView {
    init(@ViewBuilder content: () -> Content) {
        self.init(content: content, bottom: { EmptyView() })
    }
}

/// The small printed label over a group, with a line after it.
struct SilkLabel<Trailing: View>: View {
    var title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: DesignTokens.Spacing.sm) {
            Text(title).font(.mono(.xs)).tracking(1.5).foregroundStyle(DesignTokens.Palette.Text.tertiary)
            Rectangle().fill(Panel.rule).frame(height: 1)
            trailing
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

extension SilkLabel where Trailing == EmptyView {
    init(_ title: String) { self.title = title; self.trailing = EmptyView() }
}

struct LED: View {
    var on: Bool
    var size: CGFloat = 10

    var body: some View {
        Circle()
            .fill(on ? DesignTokens.Palette.primary : .clear)
            .overlay(Circle().strokeBorder(on ? .clear : Panel.ledOff, lineWidth: 1.5))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

struct ProTag: View {
    var body: some View {
        Text("PRO")
            .font(.mono(.xs))
            .tracking(1)
            .padding(.horizontal, DesignTokens.Spacing.sm - 2)
            .padding(.vertical, 1)
            .background(DesignTokens.Palette.primarySubtle, in: Capsule())
            .foregroundStyle(DesignTokens.Palette.primary)
    }
}

/// A key on the panel. A selected key gets a primary border; its LED is drawn by the content.
struct KeyStyle: ButtonStyle {
    var selected = false
    var radius: CGFloat = DesignTokens.Radius.md

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(Panel.key, in: RoundedRectangle(cornerRadius: radius))
            .overlay(
                RoundedRectangle(cornerRadius: radius)
                    .strokeBorder(selected ? DesignTokens.Palette.primary : Panel.keyBorder, lineWidth: selected ? 2 : 1)
            )
            .opacity(configuration.isPressed ? DesignTokens.Opacity.muted : 1)
            .contentShape(RoundedRectangle(cornerRadius: radius))
    }
}

/// The one big action on a screen: a pad in the primary colour.
struct PadStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(DesignTokens.Palette.onPrimary)
            .background(DesignTokens.Palette.primary, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.lg))
            .opacity(!enabled ? DesignTokens.Opacity.disabled : configuration.isPressed ? DesignTokens.Opacity.muted : 1)
    }
}

/// The display: always dark, values in the monospaced face.
struct DisplayPanel<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) { content }
            .font(.mono(.sm))
            .foregroundStyle(Panel.displayText)
            .padding(DesignTokens.Spacing.md + 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Panel.display, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.lg))
            .overlay(RoundedRectangle(cornerRadius: DesignTokens.Radius.lg).strokeBorder(Panel.displayBorder, lineWidth: 1))
            .environment(\.colorScheme, .dark)
    }
}

/// The top of a screen: a key to go back or close, a printed label and a title.
struct PanelHeader: View {
    var label: String
    var title: String
    var icon = "chevron.left"
    var iconLabel = "Back"
    var action: (() -> Void)?

    var body: some View {
        HStack(spacing: DesignTokens.Spacing.md) {
            if let action, icon == "chevron.left" { iconKey(action) }
            VStack(alignment: .leading, spacing: 0) {
                Text(label).font(.mono(.xs)).tracking(1.5).foregroundStyle(DesignTokens.Palette.Text.tertiary)
                Text(title).font(.token(.lg).weight(.semibold)).lineLimit(1).truncationMode(.middle)
            }
            Spacer(minLength: 0)
            if let action, icon != "chevron.left" { iconKey(action) }
        }
    }

    private func iconKey(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.token(.lg)).frame(width: 44, height: 44)
        }
        .buttonStyle(KeyStyle())
        .accessibilityLabel(iconLabel)
    }
}

/// Keeps the swipe from the left edge going back while the navigation bar is hidden. Sits in each
/// screen's background, finds the navigation controller and lets its pop gesture start whenever
/// there is a screen to go back to.
struct SwipeBack: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController {
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard let nav = navigationController else { return }
            PopGestureDelegate.shared.nav = nav
            nav.interactivePopGestureRecognizer?.delegate = PopGestureDelegate.shared
        }
    }
}

final class PopGestureDelegate: NSObject, UIGestureRecognizerDelegate {
    static let shared = PopGestureDelegate()
    weak var nav: UINavigationController?

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        (nav?.viewControllers.count ?? 0) > 1
    }
}
