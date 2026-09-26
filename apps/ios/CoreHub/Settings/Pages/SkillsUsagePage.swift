// Settings → Skills usage: not native on the phone yet (audit.getSkillUsage), so the registry draws
// the fallback. To make it native: draw the page here and drop `native: false`
// (docs/clients/phone-pages.md).
import SwiftUI

extension PhonePage {
    static let skillsUsage = PhonePage(.skillsUsage, native: false) { context in NotNativePage(destination: context.destination) }
}
