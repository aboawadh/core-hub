// Settings → Profiles (destination `workspaces`: the code's word; a person reads «profile»).
import CoreHubClient
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

struct WorkspacesPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var slug = ""
    @State private var name = ""
    @State private var error: String?

    var body: some View {
        AsyncContent(key: "profiles") {
            try await app.api.call { try await AuthAPI.authListProfiles(apiConfiguration: $0) }.items
        } content: { profiles, reload in
            Form {
                if let error { NoticeView(text: error, tone: .danger) }
                Section {
                    ForEach(profiles, id: \.id) { profile in
                        HStack {
                            Text(profile.name)
                            Spacer()
                            Text(profile.slug).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted)
                        }
                    }
                }
                Section(l10n("workspaces.new")) {
                    TextField(l10n("workspaces.name"), text: $name)
                    TextField(l10n("workspaces.slug"), text: $slug)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .environment(\.layoutDirection, .leftToRight)
                    Button(l10n("workspaces.create")) { Task { await create(reload) } }
                        .disabled(slug.isEmpty || name.isEmpty)
                }
            }
        }
    }

    private func create(_ reload: @escaping () -> Void) async {
        let request = ProfileCreate(slug: slug.lowercased(), name: name)
        do {
            _ = try await app.api.call { try await AuthAPI.authCreateProfile(profileCreate: request, apiConfiguration: $0) }
            slug = ""
            name = ""
            error = nil
            await app.refreshAccount()
            reload()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

extension PhonePage {
    static let workspaces = PhonePage(.workspaces) { _ in WorkspacesPage() }
}
