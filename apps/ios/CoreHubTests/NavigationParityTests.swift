// The navigation parity test (docs/clients/README.md): the app against docs/clients/navigation.json,
// copied into the test bundle at build time. It fails on the first difference.
@testable import CoreHub
import CoreHubClient
import XCTest

final class NavigationParityTests: XCTestCase {
    private struct Destination: Decodable {
        let id: String
        let title: String
        let roles: [String]
        let surfaces: [String]?
        let capability: String?
    }

    private struct PreAuth: Decodable {
        let title: String
        let routes: [String: String]
    }

    private struct Manifest: Decodable {
        let terms: [String: [String: String]]
        let destinations: [Destination]
        let rail: [String]
        let segments: [String]
        let footer: [String]
        let settingsTabs: [String]
        let settingsManagement: [String]
        let settingsTools: [String]
        let agentLevel: [String]
        let secondaryEntries: [String: [String]]
        let agentShell: [String: String]
    }

    private func manifest() throws -> Manifest {
        try JSONDecoder().decode(Manifest.self, from: Fixture.repositoryFile("navigation", "json"))
    }

    /// `surfaceRoutes.ios`; the map also carries a `$comment` string, so it is read loosely.
    private func iosRoutes() throws -> [String: String]? {
        let object = try JSONSerialization.jsonObject(with: Fixture.repositoryFile("navigation", "json")) as! [String: Any]
        return (object["surfaceRoutes"] as? [String: Any])?["ios"] as? [String: String]
    }

    /// `preAuth` mixes a `$comment` string with the screens, so it is read loosely.
    private func preAuth() throws -> [String: PreAuth] {
        let object = try JSONSerialization.jsonObject(with: Fixture.repositoryFile("navigation", "json")) as! [String: Any]
        let raw = object["preAuth"] as! [String: Any]
        var screens: [String: PreAuth] = [:]
        for (id, value) in raw where !id.hasPrefix("$") {
            let data = try JSONSerialization.data(withJSONObject: value)
            screens[id] = try JSONDecoder().decode(PreAuth.self, from: data)
        }
        return screens
    }

    private func onIOS(_ m: Manifest) -> [Destination] {
        m.destinations.filter { $0.surfaces?.contains("ios") ?? true }
    }

    // 1 and 2: every destination has a screen, and every screen has a destination.
    func testEveryDestinationHasAScreenAndNothingElseDoes() throws {
        let m = try manifest()
        XCTAssertEqual(Set(DestinationID.allCases.map(\.rawValue)), Set(onIOS(m).map(\.id)))
        let routes = try XCTUnwrap(try iosRoutes(), "surfaceRoutes.ios is missing")
        XCTAssertEqual(Dictionary(uniqueKeysWithValues: AppRoutes.routes.map { ($0.key.rawValue, $0.value) }), routes)
        for destination in DestinationID.allCases {
            let path = routes[destination.rawValue]!.replacingOccurrences(of: ":agentId", with: "01J8QK3ZR2W7M5N4P6T8V9X0AG")
                .replacingOccurrences(of: ":sessionId?", with: "01J8QK3ZR2W7M5N4P6T8V9X0YA")
                .replacingOccurrences(of: ":roomId?", with: "01J8QK3ZR2W7M5N4P6T8V9X0RM")
            XCTAssertEqual(AppRoutes.match(path)?.destination, destination, path)
        }
    }

    func testThePreAuthScreensAreTheManifestsAndNotDestinations() throws {
        let screens = try preAuth()
        XCTAssertEqual(Set(screens.keys), Set(NavigationMap.preAuth))
        for (id, screen) in screens {
            XCTAssertEqual(screen.routes["ios"], AppRoutes.preAuth[id], id)
            XCTAssertNil(DestinationID(rawValue: id), "\(id) must not be a destination")
            XCTAssertEqual(screen.title, id)
        }
    }

    // 3: one primary entry per destination, in the manifest's order.
    func testEveryEntryListIsTheManifestsInOrder() throws {
        let m = try manifest()
        let ios = Set(onIOS(m).map(\.id))
        XCTAssertEqual(NavigationMap.rail.map(\.rawValue), m.rail.filter(ios.contains))
        XCTAssertEqual(NavigationMap.segments.map(\.rawValue), m.segments.filter(ios.contains))
        XCTAssertEqual(NavigationMap.footer.map(\.rawValue), m.footer.filter(ios.contains))
        XCTAssertEqual(NavigationMap.settingsTabs.map(\.rawValue), m.settingsTabs.filter(ios.contains))
        XCTAssertEqual(NavigationMap.settingsManagement.map(\.rawValue), m.settingsManagement.filter(ios.contains))
        XCTAssertEqual(NavigationMap.settingsTools.map(\.rawValue), m.settingsTools.filter(ios.contains))
        XCTAssertEqual(NavigationMap.agentLevel.map(\.rawValue), m.agentLevel.filter(ios.contains))
        let lists = NavigationMap.rail + NavigationMap.segments + NavigationMap.footer + NavigationMap.settingsTabs
            + NavigationMap.settingsManagement + NavigationMap.settingsTools + NavigationMap.agentLevel
        XCTAssertEqual(lists.count, Set(lists).count, "a destination has two primary entries")
        XCTAssertEqual(NavigationMap.agentBackTerm, m.agentShell["back"])
    }

    // 4: the entry's label and the screen's title are one key, the manifest's word.
    func testEntryLabelEqualsScreenTitle() throws {
        let m = try manifest()
        let host = Bundle(for: AppModel.self)
        for destination in onIOS(m) {
            let id = try XCTUnwrap(DestinationID(rawValue: destination.id))
            XCTAssertEqual(id.titleTerm, destination.title, destination.id)
            for language in AppLanguage.allCases {
                let l10n = L10n(language, bundle: host)
                XCTAssertEqual(l10n(id.titleKey), m.terms[destination.title]?[language.rawValue], "\(language) \(destination.id)")
            }
        }
    }

    // 5: secondary entries only where the manifest allows them.
    func testSecondaryEntries() throws {
        let m = try manifest()
        let app = Dictionary(uniqueKeysWithValues: NavigationMap.secondaryEntries.map { ($0.key.rawValue, $0.value.map(\.rawValue)) })
        XCTAssertEqual(app, m.secondaryEntries)
    }

    // 6: roles and surfaces.
    func testRolesAndSurfaces() throws {
        let m = try manifest()
        for destination in onIOS(m) {
            let id = try XCTUnwrap(DestinationID(rawValue: destination.id))
            XCTAssertEqual(id.adminOnly, destination.roles.contains("admin"), destination.id)
        }
        let member = NavigationMap.visible(NavigationMap.rail + NavigationMap.settingsTabs + NavigationMap.settingsTools, admin: false)
        XCTAssertFalse(member.contains { $0.adminOnly })
        XCTAssertTrue(DestinationID.allCases.contains(.thisDevice), "this_device exists on phones")
    }

    // 7: the agent level is capability-driven, from the adapter — never the client.
    func testTheAgentLevelFollowsCapabilities() throws {
        let m = try manifest()
        for destination in onIOS(m) where m.agentLevel.contains(destination.id) {
            XCTAssertEqual(DestinationID(rawValue: destination.id)?.capability, destination.capability, destination.id)
        }
        // A fake agent declaring a subset (Claude Code's: skills and MCP) and installed.
        XCTAssertEqual(NavigationMap.agentMenu(capabilities: ["skills", "mcp", "streaming"], installed: true).map(\.rawValue),
                       ["agent_skills", "agent_mcp", "agent_settings"])
        XCTAssertEqual(NavigationMap.agentMenu(capabilities: ["memory", "channels", "jobs"], installed: false).map(\.rawValue),
                       ["agent_memory", "agent_jobs", "agent_channels"])
    }

    // docs/clients/phone-pages.md: every Settings and agent page is registered once, in the manifest's order.
    func testThePageRegistryNamesEverySettingsAndAgentPageOnceInOrder() throws {
        let m = try manifest()
        let ios = Set(onIOS(m).map(\.id))
        XCTAssertEqual(PageRegistry.settings.map(\.destination.rawValue),
                       (m.settingsTabs + m.settingsManagement + m.settingsTools).filter(ios.contains))
        XCTAssertEqual(PageRegistry.agent.map(\.destination.rawValue), m.agentLevel.filter(ios.contains))
        for page in PageRegistry.settings + PageRegistry.agent {
            XCTAssertEqual(PageRegistry.page(page.destination)?.destination, page.destination)
        }
        XCTAssertFalse(PageRegistry.notNative.contains(.account), "a native page is not in the fallback list")
    }

    /// `railExtra`, `brandRow` and `sidebarGroups`, read loosely (they carry `$comment` strings).
    private func drawerManifest() throws -> (railExtra: [String], brandRow: [String: Any], groups: [String: [String: Any]]) {
        let object = try JSONSerialization.jsonObject(with: Fixture.repositoryFile("navigation", "json")) as! [String: Any]
        let railExtra = object["railExtra"] as? [String] ?? []
        let brandRow = object["brandRow"] as? [String: Any] ?? [:]
        var groups: [String: [String: Any]] = [:]
        for (id, value) in object["sidebarGroups"] as? [String: Any] ?? [:] where !id.hasPrefix("$") {
            groups[id] = value as? [String: Any]
        }
        return (railExtra, brandRow, groups)
    }

    // DECISIONS §126/§128: the rail's extra entries, the brand row and the drawer's groups are the manifest's.
    func testTheDrawersExtraEntriesBrandRowAndGroupsAreTheManifests() throws {
        let m = try manifest()
        let ios = Set(onIOS(m).map(\.id))
        let drawer = try drawerManifest()
        XCTAssertEqual(NavigationMap.railExtra.map(\.rawValue), drawer.railExtra.filter(ios.contains))
        XCTAssertTrue((drawer.brandRow["surfaces"] as? [String] ?? []).contains("ios"))
        XCTAssertEqual(NavigationMap.brandRow.map(\.rawValue), drawer.brandRow["items"] as? [String])
        let onPhone = drawer.groups.filter { ($0.value["surfaces"] as? [String] ?? []).contains("ios") }
        XCTAssertEqual(Set(NavigationMap.sidebarGroups.map(\.id)), Set(onPhone.keys))
        for group in NavigationMap.sidebarGroups {
            let entry = try XCTUnwrap(onPhone[group.id], group.id)
            XCTAssertEqual(group.title, entry["title"] as? String, group.id)
            XCTAssertEqual(group.items.map(\.rawValue), (entry["items"] as? [String] ?? []).filter(ios.contains), group.id)
            for language in AppLanguage.allCases {
                let l10n = L10n(language, bundle: Bundle(for: AppModel.self))
                XCTAssertEqual(l10n(group.titleKey), m.terms[group.title]?[language.rawValue], "\(language) \(group.id)")
            }
        }
        // Workflows has a screen of its own and its route resolves to it.
        XCTAssertEqual(AppRoutes.routes[.workflows], "/workflows")
        XCTAssertEqual(AppRoutes.match("/workflows")?.destination, .workflows)
        XCTAssertEqual(Icons.lucide(for: .workflows), .workflow)
        XCTAssertEqual(Icons.lucide(forGroup: "tools"), .wrench)
    }

    func testTheDrawerDrawsNewChatThenTheToolsGroupWithTheMembersTheRoleAllows() {
        let tools = NavigationMap.sidebarGroups[0]
        XCTAssertEqual(NavigationMap.drawer(admin: true), [.row(.newChat), .group(tools, [.agentManager, .tasks, .workflows, .schedules])])
        XCTAssertEqual(NavigationMap.drawer(admin: false), [.row(.newChat), .group(tools, [.tasks, .workflows, .schedules])])
        // Search is in the header (the brand row), never a row of the rail.
        XCTAssertFalse(NavigationMap.drawer(admin: true).contains(.row(.search)))
        // Every rail and extra entry is drawn once: in the header, as a row, or in a group.
        let drawn = NavigationMap.brandRow + NavigationMap.drawer(admin: true).flatMap { entry -> [DestinationID] in
            switch entry {
            case .row(let destination): return [destination]
            case .group(_, let members): return members
            }
        }
        XCTAssertEqual(Set(drawn), Set(NavigationMap.rail + NavigationMap.railExtra))
        XCTAssertEqual(drawn.count, Set(drawn).count)
    }

    func testWorkflowLinksAndNoticesOpenTheWorkflowsPageOrTheRun() {
        func open(_ path: String) -> MainContent? { AppModel.route(for: URL(string: "corehub://open\(path)")!, selector: "home") }
        XCTAssertEqual(open("/workflows"), .destination(.workflows))
        XCTAssertEqual(open("/schedules"), .destination(.schedules))
        // The old address of the Workflows tab, and a run in it.
        XCTAssertEqual(open("/schedules?section=workflows"), .destination(.workflows))
        XCTAssertEqual(open("/schedules?section=workflows&workflow_run=01J8QK3ZR2W7M5N4P6T8V9X0RN&profile=work"),
                       .workflowRun(runID: "01J8QK3ZR2W7M5N4P6T8V9X0RN", profile: "work"))
        XCTAssertEqual(open("/schedules?workflow_run=01J8QK3ZR2W7M5N4P6T8V9X0RN"), .workflowRun(runID: "01J8QK3ZR2W7M5N4P6T8V9X0RN", profile: "home"))
        XCTAssertEqual(open("/workflows?workflow_run=R1&profile=work"), .workflowRun(runID: "R1", profile: "work"))
        XCTAssertEqual(open("/workflows?workflow=W1&profile=work&run=R2"), .workflowRun(runID: "R2", profile: "work"))
        XCTAssertEqual(open("/workflows?workflow=W1&profile=work&run=latest"), .destination(.workflows))
        XCTAssertEqual(MainContent.workflowRun(runID: "R1", profile: "work").place, .workflows)
        XCTAssertEqual(MainContent.destination(.schedules).place, .schedules)
        XCTAssertNil(MainContent.newChat.place)

        // Notices: a workflow run opens its run; a workflow or a step the page; a schedule, Schedules.
        XCTAssertEqual(NoticeRouting.route(kind: "workflow_run", sessionID: nil, profile: "work", selector: "x", runID: "R1"),
                       .workflowRun(runID: "R1", profile: "work"))
        XCTAssertEqual(NoticeRouting.route(kind: "workflow_run", sessionID: nil, profile: nil, selector: "x"), .destination(.workflows))
        XCTAssertEqual(NoticeRouting.route(kind: "step", sessionID: nil, profile: nil, selector: "x"), .destination(.workflows))
        XCTAssertEqual(NoticeRouting.route(kind: "schedule", sessionID: nil, profile: nil, selector: "x"), .destination(.schedules))
        let pushed = NoticeRouting.tap(from: ["type": "notice", "notice_id": "n1", "profile": "work",
                                              "resource": ["kind": "workflow_run", "id": "R9"]])
        XCTAssertEqual(pushed.runID, "R9")
        XCTAssertEqual(NoticeRouting.route(kind: pushed.kind, sessionID: pushed.sessionID, profile: pushed.profile, selector: "x", runID: pushed.runID),
                       .workflowRun(runID: "R9", profile: "work"))
        let notice = Notice(id: "n2", userId: "u1", profile: "work", kind: .runCompleted, title: "Failed",
                            resource: ResourceRef(kind: .workflowRun, id: "R8"), createdAt: Fixture.date)
        let local = NoticeRouting.tap(from: NoticeRouting.userInfo(for: notice))
        XCTAssertEqual(local.runID, "R8")
        XCTAssertEqual(local.kind, "workflow_run")
    }

    // 8: the profile is a filter — a link names a page, and the selector does not move it.
    func testLinksOpenTheirPageInTheirProfile() {
        let chat = URL(string: "corehub://open/chat/01J8QK3ZR2W7M5N4P6T8V9X0YA?profile=work")!
        XCTAssertEqual(AppModel.route(for: chat, selector: "default"), .chat(sessionID: "01J8QK3ZR2W7M5N4P6T8V9X0YA", profile: "work"))
        let bare = URL(string: "corehub://open/chat/01J8QK3ZR2W7M5N4P6T8V9X0YA")!
        XCTAssertEqual(AppModel.route(for: bare, selector: "home"), .chat(sessionID: "01J8QK3ZR2W7M5N4P6T8V9X0YA", profile: "home"))
        XCTAssertEqual(AppModel.route(for: URL(string: "corehub://open/tasks")!, selector: "x"), .destination(.tasks))
        XCTAssertEqual(AppModel.route(for: URL(string: "corehub://open/settings/models")!, selector: "x"), .settings)
        XCTAssertEqual(AppModel.route(for: URL(string: "corehub://open/agents/01J8QK3ZR2W7M5N4P6T8V9X0AG/mcp")!, selector: "x"), .destination(.agentManager))
        XCTAssertNil(AppModel.route(for: URL(string: "corehub://open/nowhere")!, selector: "x"))
        XCTAssertNil(AppModel.route(for: URL(string: "https://open/tasks")!, selector: "x"))
    }
}

/// The drawer's groups open and close on this phone (`sidebar.groupsClosed`, DECISIONS §128).
final class SidebarGroupStateTests: XCTestCase {
    private func fresh() -> UserDefaults {
        let name = "sidebar-groups-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    func testAGroupIsOpenByDefaultAndTheChoiceIsRemembered() {
        let defaults = fresh()
        let state = SidebarGroupState(defaults: defaults)
        XCTAssertTrue(state.isOpen("tools"))
        XCTAssertEqual(state.closed, [])
        XCTAssertEqual(state.toggle("tools"), ["tools"])
        XCTAssertEqual(defaults.stringArray(forKey: SidebarGroupState.key), ["tools"])
        XCTAssertEqual(SidebarGroupState.key, "sidebar.groupsClosed")
        // Read again, as the next launch does.
        XCTAssertFalse(SidebarGroupState(defaults: defaults).isOpen("tools"))
        XCTAssertEqual(SidebarGroupState(defaults: defaults).toggle("tools"), [])
        XCTAssertTrue(SidebarGroupState(defaults: defaults).isOpen("tools"))
    }

    func testAnythingUnreadableCountsAsAllOpen() {
        let defaults = fresh()
        defaults.set("tools", forKey: SidebarGroupState.key)
        XCTAssertTrue(SidebarGroupState(defaults: defaults).isOpen("tools"))
        defaults.set([1, 2], forKey: SidebarGroupState.key)
        XCTAssertEqual(SidebarGroupState(defaults: defaults).closed, [])
        defaults.set(["tools": true], forKey: SidebarGroupState.key)
        XCTAssertTrue(SidebarGroupState(defaults: defaults).isOpen("tools"))
    }

    func testAClosedGroupsHeadingIsTheCurrentPlaceOnlyWhileOneOfItsPagesIsOnScreen() {
        let tools = NavigationMap.sidebarGroups[0]
        XCTAssertTrue(SidebarGroupState.headingMarked(tools, open: false, current: .workflows))
        XCTAssertTrue(SidebarGroupState.headingMarked(tools, open: false, current: .schedules))
        XCTAssertFalse(SidebarGroupState.headingMarked(tools, open: true, current: .workflows), "open: the row itself is marked")
        XCTAssertFalse(SidebarGroupState.headingMarked(tools, open: false, current: .chat))
        XCTAssertFalse(SidebarGroupState.headingMarked(tools, open: false, current: nil))
    }
}
