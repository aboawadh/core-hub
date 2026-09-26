// Settings → Device connections: pairing a new app, and the devices as cards (DeviceCardsList.swift).
import CoreHubClient
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Device connections: `App` makes a pairing another phone scans; `Devices` (admin) lists the
/// paired devices — the hub's `devices` module says itself whether it is built.
struct DeviceConnectionsPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var tab = 0

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                Text(l10n("devices.tab_app")).tag(0)
                // Everyone sees their own devices; an admin sees everyone's (the hub decides).
                Text(l10n("devices.tab_devices")).tag(1)
            }
            .pickerStyle(.segmented)
            .padding(Space.s3)
            if tab == 0 {
                PairingMaker()
            } else {
                // The cards of #149: state, last active, push; rename, test the push, remove.
                DeviceCardsList()
            }
        }
    }
}

/// Makes a pairing and shows its QR code, the code and the link, for another phone.
struct PairingMaker: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var pairing: Pairing?
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        ScrollView {
            VStack(spacing: Space.s3) {
                Text(l10n("devices.pair_hint")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                if let error { NoticeView(text: error, tone: .danger) }
                if let pairing {
                    if let image = QRImage.make(pairing.qrPayload) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 240)
                            .padding(Space.s3)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: Radius.md))
                            .accessibilityLabel(l10n("devices.qr"))
                    }
                    Text(pairing.code)
                        .font(.system(size: FontSize.sizeXl, weight: .bold, design: .monospaced))
                        .textSelection(.enabled)
                        .environment(\.layoutDirection, .leftToRight)
                    Text(l10n("devices.expires", ["time": pairing.expiresAt.shortText(app.language)]))
                        .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
                Button {
                    Task { await make() }
                } label: {
                    LucideLabel(pairing == nil ? l10n("devices.make_pairing") : l10n("devices.new_pairing"), icon: .qrCode)
                }
                .buttonStyle(.borderedProminent)
                .disabled(busy)
            }
            .padding(Space.s4)
        }
    }

    private func make() async {
        busy = true
        defer { busy = false }
        do {
            pairing = try await app.api.call { try await AuthAPI.authCreatePairing(pairingCreate: PairingCreate(), apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

enum QRImage {
    static func make(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

extension PhonePage {
    static let deviceConnections = PhonePage(.deviceConnections) { _ in DeviceConnectionsPage() }
}
