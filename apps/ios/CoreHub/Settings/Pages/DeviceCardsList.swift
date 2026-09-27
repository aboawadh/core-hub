// The devices as cards (Device connections → Devices): state, last active, push; rename, test, remove.
import AVFoundation
import CoreHubClient
import SwiftUI
import UIKit

struct DeviceCardsList: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var devices: [Device]?
    @State private var note: (text: String, tone: NoticeView.Kind)?
    @State private var renaming: Device?
    @State private var name = ""
    @State private var removing: Device?

    var body: some View {
        List {
            if let note { NoticeView(text: note.text, tone: note.tone) }
            if let devices {
                if devices.isEmpty { EmptyRow(icon: .smartphone) }
                ForEach(AdminLogic.devices(devices), id: \.id) { device in
                    VStack(alignment: .leading, spacing: Space.s1) {
                        HStack(spacing: Space.s2) {
                            StatusDot(kind: device.online ? .good : .neutral, label: device.online ? l10n("shell.connected") : l10n("shell.offline"))
                            Text(device.name).font(.system(size: FontSize.sizeMd, weight: .medium))
                            Spacer()
                            if device.thisDevice { StatusPill(text: l10n("devices_page.this"), kind: .good) }
                        }
                        let facts = [[device.brand, device.model].compactMap { $0 }.joined(separator: " "), device.osVersion, device.appVersion.map { "v\($0)" }]
                            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
                        if !facts.isEmpty { Text(facts).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
                        Text([device.lastSeenAt.map { l10n("devices_page.last_seen", ["time": $0.shortText(app.language)]) }, l10n(device.push != nil ? "devices_page.push_on" : "devices_page.push_off")].compactMap { $0 }.joined(separator: " · "))
                            .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                    .accessibilityIdentifier("device.\(device.id)")
                    .contextMenu {
                        Button(l10n("devices_page.rename")) { name = device.name; renaming = device }
                        if device.push != nil { Button(l10n("devices_page.test_push")) { Task { await testPush(device) } } }
                        if !device.thisDevice { Button(l10n("devices_page.remove"), role: .destructive) { removing = device } }
                    }
                    .swipeActions {
                        if !device.thisDevice { Button(l10n("devices_page.remove"), role: .destructive) { removing = device } }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
            // The admin's push senders, folded at the bottom (the web's order, decision §81).
            if app.isAdmin { PushSendersSection() }
        }
        .task { await load() }
        .refreshable { await load() }
        .alert(l10n("devices_page.rename"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(l10n("devices_page.rename"), text: $name)
            Button(l10n("common.save")) {
                if let device = renaming {
                    let typed = name.trimmingCharacters(in: .whitespaces)
                    Task { await act { _ = try await DevicesAPI.devicesUpdate(deviceId: device.id, devicePatch: DevicePatch(name: typed), apiConfiguration: $0) } }
                }
                renaming = nil
            }
            Button(l10n("common.cancel"), role: .cancel) { renaming = nil }
        }
        .confirmationDialog(removing.map { l10n("devices_page.remove_confirm", ["name": $0.name]) } ?? "", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            Button(l10n("devices_page.remove"), role: .destructive) {
                if let device = removing { Task { await act { try await DevicesAPI.devicesUnlink(deviceId: device.id, apiConfiguration: $0) } } }
                removing = nil
            }
        } message: {
            Text(l10n("devices_page.remove_body"))
        }
    }

    private func load() async {
        do {
            devices = try await app.api.call { try await DevicesAPI.devicesList(limit: 200, apiConfiguration: $0) }.items
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }

    private func testPush(_ device: Device) async {
        do {
            let result = try await app.api.call { try await DevicesAPI.devicesTestPush(deviceId: device.id, apiConfiguration: $0) }
            note = (result.error ?? l10n("devices_page.push_sent"), result.error == nil ? .success : .danger)
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }

    private func act(_ operation: @escaping (CoreHubClientAPIConfiguration) async throws -> Void) async {
        do {
            try await app.api.call { try await operation($0) }
            note = nil
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
        await load()
    }
}
