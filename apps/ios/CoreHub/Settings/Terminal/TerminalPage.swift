// Settings → Terminal (owner only, DECISIONS §70), drawn by the phone: the warning that stays on the
// page, the sessions as tabs, the screen, and a row of the keys a phone keyboard lacks (Esc, Tab,
// Ctrl, the arrows). Same sessions as the web: one opened here shows there, and back.
import CoreHubClient
import SwiftUI
import UIKit

/// What `GET /terminal` answered: on (with its state), or off for this person.
enum TerminalAvailability {
    case on(TerminalStatus)
    case off

    var isOn: Bool {
        if case .on = self { return true }
        return false
    }

    /// A `403` is an answer ("not for you", "not on"), not a failure to retry.
    static func load(_ app: AppModel) async throws -> TerminalAvailability {
        guard app.credentials?.role == Role.owner.rawValue else { return .off }
        do {
            let status = try await app.api.call { try await TerminalAPI.terminalGet(apiConfiguration: $0) }
            return status.enabled ? .on(status) : .off
        } catch {
            if HubFailure(error).status == 403 { return .off }
            throw error
        }
    }
}

struct TerminalPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "terminal") {
            try await TerminalAvailability.load(app)
        } content: { availability, _ in
            switch availability {
            case .off:
                EmptyStateView(icon: .triangleAlert, title: l10n("terminal.disabled"), message: l10n("terminal.disabled_body"))
                    .frame(maxHeight: .infinity, alignment: .top)
                    .accessibilityIdentifier("terminal.disabled")
            case .on(let status):
                TerminalWorkspace(model: TerminalModel(status: status, app: app))
            }
        }
    }
}

struct TerminalWorkspace: View {
    @State var model: TerminalModel
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var keyboard = false
    @State private var ctrl = false

    var body: some View {
        VStack(spacing: Space.s2) {
            NoticeView(text: l10n("terminal.warning") + " " + l10n("terminal.warning_detail"), tone: .warning)
                .accessibilityIdentifier("terminal.warning")
            if !model.status.pty { NoticeView(text: l10n("terminal.no_pty"), tone: .info) }
            tabsRow
            if let problem = model.problem { NoticeView(text: problem, tone: .danger) }
            if let front = model.front {
                HStack(spacing: Space.s1) {
                    Text(l10n("terminal.folder")).foregroundStyle(Tone.textMuted)
                    Text(front.cwd).font(.system(size: FontSize.sizeXs, design: .monospaced)).lineLimit(1).truncationMode(.head)
                        .environment(\.layoutDirection, .leftToRight)
                    Spacer(minLength: 0)
                }
                .font(.system(size: FontSize.sizeXs))
                if let ended = front.ended {
                    NoticeView(text: TerminalWords.ended(ended, l10n), tone: .info)
                        .accessibilityIdentifier("terminal.ended")
                }
                TerminalScreenView(model: model, id: front.id, keyboard: $keyboard) { typed in
                    type(typed)
                }
                if front.ended == nil { keyBar }
            } else {
                EmptyStateView(
                    icon: .terminal,
                    title: l10n("terminal.empty"),
                    message: l10n("terminal.empty_body", ["profile": app.profileName(app.currentProfile)]),
                    actionTitle: model.canOpen ? l10n("terminal.new") : nil,
                    action: model.canOpen ? { model.open(profile: app.currentProfile) } : nil
                )
                .accessibilityIdentifier("terminal.empty")
                Text(l10n("terminal.idle", ["minutes": String(model.status.idleTimeoutSeconds / 60), "max": String(model.status.maxSessions)]))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, Space.s3)
        .padding(.bottom, Space.s2)
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .accessibilityIdentifier("terminal.page")
    }

    private var tabsRow: some View {
        HStack(spacing: Space.s2) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Space.s1) {
                    ForEach(model.tabs) { tab in
                        let name = l10n("terminal.tab", ["n": String(tab.number)])
                        HStack(spacing: 2) {
                            Button(name) { model.active = tab.id }
                                .font(.system(size: FontSize.sizeSm, weight: tab.id == model.active ? .semibold : .regular))
                                .foregroundStyle(tab.id == model.active ? Tone.accentText : Tone.text)
                                .opacity(tab.ended == nil ? 1 : 0.6)
                                .accessibilityAddTraits(tab.id == model.active ? .isSelected : [])
                                .accessibilityIdentifier("terminal.tab.\(tab.number)")
                            Button {
                                model.close(tab)
                            } label: {
                                LucideIcon(.x, size: 12)
                                    .foregroundStyle(tab.id == model.active ? Tone.accentText : Tone.textMuted)
                            }
                            .tapTarget(28)
                            .accessibilityLabel(l10n("terminal.close", ["name": name]))
                            .accessibilityIdentifier("terminal.tab.\(tab.number).close")
                        }
                        .padding(.leading, Space.s3)
                        .padding(.trailing, Space.s1)
                        .padding(.vertical, Space.s1)
                        .background(tab.id == model.active ? Tone.accent : Tone.surface2, in: Capsule())
                    }
                }
            }
            if !model.connected {
                ProgressView().accessibilityLabel(l10n("terminal.connecting"))
            }
            Menu {
                Button {
                    model.open(profile: app.currentProfile)
                } label: {
                    Label { Text(l10n("terminal.new")) } icon: { Image(lucide: .plus) }
                }
                .disabled(!model.canOpen)
                if let front = model.front, front.ended == nil, let buffer = model.buffer(front.id) {
                    Button {
                        UIPasteboard.general.string = buffer.plainText
                    } label: {
                        Label { Text(l10n("terminal.copy")) } icon: { Image(lucide: .copy) }
                    }
                    Button {
                        if let text = UIPasteboard.general.string {
                            model.send(TerminalKeys.paste(text, bracketed: buffer.bracketedPaste))
                        }
                    } label: {
                        Label { Text(l10n("terminal.paste")) } icon: { Image(lucide: .clipboardPaste) }
                    }
                }
            } label: {
                LucideIcon(.plus, size: 18).tapTarget()
            }
            .accessibilityLabel(l10n("terminal.actions"))
            .accessibilityIdentifier("terminal.actions")
        }
    }

    /// The keys a phone keyboard does not have. Ctrl stays pressed for the next letter typed.
    private var keyBar: some View {
        let applicationCursor = model.front.flatMap { model.buffer($0.id) }?.applicationCursorKeys ?? false
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Space.s1) {
                keyButton(text: "Esc", id: "esc") { model.send(TerminalKeys.sequence(.escape, applicationCursor: false)) }
                keyButton(text: "Tab", id: "tab") { model.send(TerminalKeys.sequence(.tab, applicationCursor: false)) }
                keyButton(text: "Ctrl", id: "ctrl", on: ctrl) { ctrl.toggle() }
                keyButton(icon: .chevronUp, id: "up", label: l10n("terminal.key_up")) { model.send(TerminalKeys.sequence(.up, applicationCursor: applicationCursor)) }
                keyButton(icon: .chevronDown, id: "down", label: l10n("terminal.key_down")) { model.send(TerminalKeys.sequence(.down, applicationCursor: applicationCursor)) }
                keyButton(icon: .chevronLeft, id: "left", label: l10n("terminal.key_left")) { model.send(TerminalKeys.sequence(.left, applicationCursor: applicationCursor)) }
                keyButton(icon: .chevronRight, id: "right", label: l10n("terminal.key_right")) { model.send(TerminalKeys.sequence(.right, applicationCursor: applicationCursor)) }
                keyButton(text: "|", id: "pipe") { type("|") }
                keyButton(text: "~", id: "tilde") { type("~") }
                keyButton(text: "/", id: "slash") { type("/") }
                keyButton(text: "-", id: "dash") { type("-") }
                keyButton(icon: .type, id: "keyboard", label: l10n(keyboard ? "terminal.hide_keyboard" : "terminal.show_keyboard"), on: keyboard) { keyboard.toggle() }
            }
            .environment(\.layoutDirection, .leftToRight)
        }
    }

    private func keyButton(text: String? = nil, icon: Lucide? = nil, id: String, label: String? = nil, on: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if let icon { LucideIcon(icon, size: 16) } else { Text(text ?? "").font(.system(size: FontSize.sizeSm, weight: .medium, design: .monospaced)) }
            }
            .frame(minWidth: 40, minHeight: 36)
            .padding(.horizontal, Space.s1)
            .foregroundStyle(on ? Tone.accentText : Tone.text)
            .background(on ? Tone.accent : Tone.surface2, in: RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label ?? text ?? id)
        .accessibilityIdentifier("terminal.key.\(id)")
    }

    /// Typed text, as a Ctrl combination when Ctrl is held.
    private func type(_ text: String) {
        if ctrl {
            ctrl = false
            model.send(TerminalKeys.control(text))
        } else {
            model.send(TerminalKeys.typed(text))
        }
    }
}

enum TerminalWords {
    static func ended(_ reason: String, _ l10n: L10n) -> String {
        if reason == "gone" { return l10n("terminal.gone") }
        let known = ["exited", "closed", "idle", "shutdown"].contains(reason) ? reason : "exited"
        return l10n("terminal.ended", ["reason": l10n("terminal.reason_\(known)")])
    }
}

/// The screen: rows of cells in the code block's colours, always left to right, the newest at the
/// bottom. A tap brings up the keyboard; what it types goes to `onType`.
struct TerminalScreenView: View {
    let model: TerminalModel
    let id: String
    @Binding var keyboard: Bool
    let onType: (String) -> Void

    private static let fontSize: CGFloat = 12
    private static let font = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
    private static let cell: CGSize = {
        let size = ("M" as NSString).size(withAttributes: [.font: font])
        return CGSize(width: max(1, size.width), height: max(1, ceil(font.lineHeight)))
    }()

    var body: some View {
        GeometryReader { geometry in
            let _ = model.revision
            let buffer = model.buffer(id)
            let rows = buffer.map { Array($0.allLines.suffix(800)) } ?? []
            let cursorRow = buffer.map { rows.count - $0.rows + $0.cursorY }
            ScrollViewReader { reader in
                ScrollView(.vertical) {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(rows.indices, id: \.self) { index in
                            Text(TerminalRender.line(
                                rows[index],
                                cursor: index == cursorRow && (buffer?.cursorVisible ?? false) ? buffer?.cursorX : nil
                            ))
                            .font(.system(size: Self.fontSize, design: .monospaced))
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .frame(height: Self.cell.height, alignment: .leading)
                            .id(index)
                        }
                    }
                    .padding(Space.s1)
                }
                .onChange(of: model.revision) { _, _ in
                    if let last = rows.indices.last { reader.scrollTo(last, anchor: .bottom) }
                }
                .onAppear {
                    if let last = rows.indices.last { reader.scrollTo(last, anchor: .bottom) }
                }
            }
            .onAppear { measure(geometry.size) }
            .onChange(of: geometry.size) { _, size in measure(size) }
        }
        .environment(\.layoutDirection, .leftToRight)
        .foregroundStyle(Tone.codeText)
        .background(Tone.codeBg, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .overlay {
            TerminalKeyInput(isFirstResponder: $keyboard, onText: onType, onKey: { key in
                let application = model.buffer(id)?.applicationCursorKeys ?? false
                model.send(TerminalKeys.sequence(key, applicationCursor: application))
            })
            .frame(width: 1, height: 1)
            .opacity(0.01)
            .allowsHitTesting(false)
        }
        .contentShape(Rectangle())
        .onTapGesture { keyboard = true }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.buffer(id).map { TerminalScreenBuffer.text(of: $0.lines[$0.cursorY]) } ?? "")
        .accessibilityAddTraits(.allowsDirectInteraction)
        .accessibilityIdentifier("terminal.screen")
    }

    private func measure(_ size: CGSize) {
        let cols = Int((size.width - Space.s1 * 2) / Self.cell.width)
        let rows = Int((size.height - Space.s1 * 2) / Self.cell.height)
        guard cols > 0, rows > 0 else { return }
        model.resize(cols: cols, rows: rows)
    }
}

/// A row of cells as styled text; the cursor's cell drawn inverted.
enum TerminalRender {
    private typealias Foreground = AttributeScopes.SwiftUIAttributes.ForegroundColorAttribute
    private typealias Background = AttributeScopes.SwiftUIAttributes.BackgroundColorAttribute
    private typealias Underline = AttributeScopes.SwiftUIAttributes.UnderlineStyleAttribute

    /// Each cell's style, the cursor's inverted.
    static func styles(_ cells: [TermCell], cursor: Int?) -> [TermStyle] {
        cells.indices.map { index in
            var style = cells[index].style
            if index == cursor { style.inverse.toggle() }
            return style
        }
    }

    static func line(_ cells: [TermCell], cursor: Int?) -> AttributedString {
        let styles = styles(cells, cursor: cursor)
        var out = AttributedString()
        var index = 0
        while index < cells.count {
            let style = styles[index]
            var text = ""
            var end = index
            while end < cells.count && styles[end] == style {
                text += cells[end].text.isEmpty ? " " : cells[end].text
                end += 1
            }
            var run = AttributedString(text)
            let foreground = style.foreground.map(TerminalPalette.color)
            let background = style.background.map(TerminalPalette.color)
            var ink: Color? = foreground
            var paper: Color? = background
            if style.inverse {
                ink = background ?? Tone.codeBg
                paper = foreground ?? Tone.codeText
            }
            if style.dim { ink = (ink ?? Tone.codeText).opacity(0.6) }
            if let ink { run[Foreground.self] = ink }
            if let paper { run[Background.self] = paper }
            if style.underline { run[Underline.self] = Text.LineStyle(pattern: .solid) }
            if style.bold { run.inlinePresentationIntent = .stronglyEmphasized }
            out += run
            index = end
        }
        return out
    }
}

/// xterm's colours: the 16 named ones, the 6×6×6 cube and the greys.
enum TerminalPalette {
    static let named: [(UInt8, UInt8, UInt8)] = [
        (0, 0, 0), (205, 49, 49), (13, 188, 121), (229, 229, 16), (36, 114, 200), (188, 63, 188), (17, 168, 205), (229, 229, 229),
        (102, 102, 102), (241, 76, 76), (35, 209, 139), (245, 245, 67), (59, 142, 234), (214, 112, 214), (41, 184, 219), (255, 255, 255),
    ]

    static func rgb(_ color: TermColor) -> (UInt8, UInt8, UInt8) {
        switch color {
        case .rgb(let r, let g, let b): return (r, g, b)
        case .indexed(let n) where n < 16: return named[max(0, n)]
        case .indexed(let n) where n < 232:
            let levels: [UInt8] = [0, 95, 135, 175, 215, 255]
            let i = n - 16
            return (levels[i / 36], levels[(i / 6) % 6], levels[i % 6])
        case .indexed(let n):
            let grey = UInt8(8 + 10 * (min(n, 255) - 232))
            return (grey, grey, grey)
        }
    }

    static func color(_ color: TermColor) -> Color {
        let (r, g, b) = rgb(color)
        return Color(red: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255)
    }
}

/// The keyboard's way in: a view that takes key input, so every key (Delete on an empty line
/// included) reaches the shell, and a hardware keyboard's arrows, Esc, Tab and Ctrl work too.
struct TerminalKeyInput: UIViewRepresentable {
    @Binding var isFirstResponder: Bool
    let onText: (String) -> Void
    let onKey: (TerminalKeys.Key) -> Void

    func makeUIView(context: Context) -> TerminalInputView {
        let view = TerminalInputView()
        view.onText = onText
        view.onKey = onKey
        view.onResign = { isFirstResponder = false }
        return view
    }

    func updateUIView(_ view: TerminalInputView, context: Context) {
        view.onText = onText
        view.onKey = onKey
        view.onResign = { isFirstResponder = false }
        if isFirstResponder && !view.isFirstResponder {
            DispatchQueue.main.async { _ = view.becomeFirstResponder() }
        } else if !isFirstResponder && view.isFirstResponder {
            DispatchQueue.main.async { _ = view.resignFirstResponder() }
        }
    }
}

final class TerminalInputView: UIView, UIKeyInput {
    var onText: ((String) -> Void)?
    var onKey: ((TerminalKeys.Key) -> Void)?
    var onResign: (() -> Void)?

    var autocorrectionType: UITextAutocorrectionType = .no
    var autocapitalizationType: UITextAutocapitalizationType = .none
    var spellCheckingType: UITextSpellCheckingType = .no
    var smartQuotesType: UITextSmartQuotesType = .no
    var smartDashesType: UITextSmartDashesType = .no
    var smartInsertDeleteType: UITextSmartInsertDeleteType = .no
    var keyboardType: UIKeyboardType = .asciiCapable
    var returnKeyType: UIReturnKeyType = .default

    override var canBecomeFirstResponder: Bool { true }

    override func resignFirstResponder() -> Bool {
        let done = super.resignFirstResponder()
        if done { onResign?() }
        return done
    }

    var hasText: Bool { true }

    func insertText(_ text: String) { onText?(text) }

    func deleteBackward() { onText?("\u{7F}") }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var handled = false
        for press in presses {
            guard let key = press.key else { continue }
            if let special = Self.special[key.keyCode] {
                onKey?(special)
                handled = true
            } else if key.modifierFlags.contains(.control), !key.charactersIgnoringModifiers.isEmpty {
                onText?(TerminalKeys.control(key.charactersIgnoringModifiers))
                handled = true
            }
        }
        if !handled { super.pressesBegan(presses, with: event) }
    }

    private static let special: [UIKeyboardHIDUsage: TerminalKeys.Key] = [
        .keyboardUpArrow: .up, .keyboardDownArrow: .down, .keyboardLeftArrow: .left, .keyboardRightArrow: .right,
        .keyboardEscape: .escape, .keyboardTab: .tab, .keyboardHome: .home, .keyboardEnd: .end,
        .keyboardPageUp: .pageUp, .keyboardPageDown: .pageDown,
    ]
}

extension PhonePage {
    static let terminal = PhonePage(.terminal) { _ in TerminalPage() }
}
