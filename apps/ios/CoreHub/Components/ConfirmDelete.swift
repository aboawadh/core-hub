// «Delete this?» before anything is deleted (docs/clients/phone-pages.md): a page keeps the item
// about to go in a `@State` and adds `.confirmDelete(…)`; its Delete action (a swipe, a menu item)
// only sets that state. Android's ConfirmDelete.kt is its twin.
import SwiftUI

extension View {
    /// Asks while `item` holds something: the title names it («Delete “Daily report”?»), the message
    /// says it cannot be undone. Delete runs `delete`; a refusal shows the hub's words in a second
    /// alert, a success calls `deleted`.
    func confirmDelete<Item>(
        _ item: Binding<Item?>,
        name: @escaping (Item) -> String,
        delete: @escaping (Item) async throws -> Void,
        deleted: @escaping (Item) -> Void = { _ in }
    ) -> some View {
        modifier(ConfirmDeleteModifier(item: item, name: name, delete: delete, deleted: deleted))
    }
}

private struct ConfirmDeleteModifier<Item>: ViewModifier {
    @Binding var item: Item?
    let name: (Item) -> String
    let delete: (Item) async throws -> Void
    let deleted: (Item) -> Void
    @Environment(\.l10n) private var l10n
    @State private var failure: String?

    func body(content: Content) -> some View {
        content
            .alert(
                item.map { l10n("kit.delete_confirm", ["name": name($0)]) } ?? "",
                isPresented: Binding(get: { item != nil }, set: { if !$0 { item = nil } })
            ) {
                Button(l10n("common.cancel"), role: .cancel) { item = nil }
                Button(l10n("kit.delete"), role: .destructive) {
                    guard let target = item else { return }
                    item = nil
                    Task {
                        do {
                            try await delete(target)
                            deleted(target)
                        } catch {
                            failure = HubFailure(error).describe(l10n)
                        }
                    }
                }
                .accessibilityIdentifier("dialog.confirm")
            } message: {
                Text(l10n("kit.delete_body"))
            }
            .alert(l10n("common.error_title"), isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
                Button(l10n("common.close"), role: .cancel) { failure = nil }
            } message: {
                Text(failure ?? "")
            }
    }
}
