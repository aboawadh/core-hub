// Settings → Files (the profile's files: browse, preview, upload): not native on the phone yet, so
// the registry draws the fallback. To make it native: draw the page here and drop `native: false`
// (docs/clients/phone-pages.md).
import SwiftUI

extension PhonePage {
    static let files = PhonePage(.files, native: false) { context in NotNativePage(destination: context.destination) }
}
