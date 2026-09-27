@testable import CoreHub
import CoreHubClient
import XCTest

/// Agents II on the phone (apps batch 9): the rules of MCP servers, the Settings cards, Channels and
/// webhooks, as the web's pages have them. Android's AgentsTwoTest checks the same rules.
final class AgentsTwoRulesTests: XCTestCase {
    private func server(_ name: String, _ transport: McpServer.Transport, _ config: [String: JSONValue]) -> McpServer {
        McpServer(name: name, transport: transport, enabled: true, connected: false, tools: [], config: config, updatedAt: Fixture.date)
    }

    private func channel(_ platform: String, login: Channel.Login? = nil, linked: Bool? = nil, configured: Bool = false,
                         mode: ChannelLink.Mode? = nil, fields: [ChannelField] = []) -> Channel {
        Channel(platform: platform, label: platform.capitalized, enabled: true, configured: configured, exclusive: false, status: .online,
                restartNeeded: false, login: login, link: linked.map { ChannelLink(linked: $0, mode: mode) }, fields: fields)
    }

    private func spec(_ platform: String, _ login: ChannelPlatform.Login, settings: Bool) -> ChannelPlatform {
        ChannelPlatform(platform: platform, label: platform, support: .full, login: login, credentials: [], validates: true, pairs: true,
                        allowlist: false, settings: settings, exclusive: true, packages: ._none, inbound: false)
    }

    private func option(_ key: String, _ kind: ChannelSetting.Kind, value: JSONValue? = nil, default fallback: JSONValue? = nil, min: Int? = nil, max: Int? = nil) -> ChannelSetting {
        ChannelSetting(key: key, section: .access, kind: kind, value: value, _default: fallback, min: min, max: max, shared: false)
    }

    // MARK: - MCP servers

    func testACommandServerReadsAsAFormAndWritesBackWithItsOtherKeysAndStoredSecretsKept() {
        let stored = server("github", .stdio, [
            "command": .string("npx"), "args": .array([.string("-y"), .string("@mcp/github")]),
            "env": .dictionary(["GITHUB_TOKEN": .string("[stored]"), "MODE": .string("read")]), "enabled": .bool(true), "timeout": .int(30),
        ])
        var draft = McpRules.draft(stored.config)
        XCTAssertEqual(draft.kind, .command)
        XCTAssertEqual(draft.args, "-y\n@mcp/github")
        XCTAssertEqual(draft.env.first, McpRules.Row(key: "GITHUB_TOKEN", value: "", stored: true))
        XCTAssertEqual(Set(draft.rest.keys), ["timeout"])
        draft.args = "-y\n\n @mcp/github \n"
        let back = McpRules.config(draft)
        XCTAssertEqual(back["env"], .dictionary(["GITHUB_TOKEN": .string("[stored]"), "MODE": .string("read")]))
        XCTAssertEqual(back["args"], .array([.string("-y"), .string("@mcp/github")]))
        XCTAssertEqual(back["timeout"], .int(30))
        XCTAssertNil(back["enabled"])
        draft.env = [McpRules.Row(key: "GITHUB_TOKEN", value: "ghp_new", stored: true)]
        XCTAssertEqual(McpRules.config(draft)["env"], .dictionary(["GITHUB_TOKEN": .string("ghp_new")]))
        XCTAssertEqual(McpRules.summary(stored), "npx -y @mcp/github")
    }

    func testAnAddressServerItsProblemsAndTheTransportAnEditSends() {
        let http = server("docs", .http, ["url": .string("https://mcp.example/docs"), "headers": .dictionary(["Authorization": .string("[stored]")])])
        let draft = McpRules.draft(http.config)
        XCTAssertEqual(draft.kind, .url)
        XCTAssertEqual(McpRules.summary(http), "https://mcp.example/docs")
        XCTAssertEqual(McpRules.transport(McpRules.config(draft)), .http)
        XCTAssertNil(McpRules.transportChange(http, McpRules.config(draft)))
        var toCommand = draft
        toCommand.kind = .command
        toCommand.command = "uvx"
        XCTAssertNil(McpRules.config(toCommand)["url"])
        XCTAssertEqual(McpRules.transportChange(http, McpRules.config(toCommand)), .stdio)

        var bad = draft
        bad.url = "mcp.example"
        XCTAssertEqual(McpRules.problem(bad), .urlBad)
        XCTAssertEqual(McpRules.problem(McpRules.Draft(command: " ")), .commandRequired)
        XCTAssertEqual(McpRules.problem(McpRules.Draft(command: "npx", env: [McpRules.Row(key: "A B", value: "x")])), .rowBad)
        XCTAssertEqual(McpRules.problem(McpRules.Draft(command: "npx", env: [McpRules.Row(key: "", value: "orphan")])), .rowBad)
        XCTAssertNil(McpRules.problem(McpRules.template))
        XCTAssertTrue(McpRules.validName("fs.local_1-2"))
        XCTAssertFalse(McpRules.validName("my server"))
        XCTAssertNil(McpRules.parse("[1]"))
        XCTAssertEqual(McpRules.parse(#"{"command":"x"}"#)?["command"], .string("x"))
        XCTAssertEqual(McpRules.seconds(820), "0.8")
        let listed = McpRules.listed([http, server("corehub", .http, ["url": .string("http://hub/hub-mcp")])], hubServer: nil)
        XCTAssertEqual(listed.map(\.name), ["docs"])
    }

    // MARK: - Settings cards

    func testCompressionIsWholePercentagesHereAndRatiosOnTheWire() throws {
        let draft = CompressionRules.draft(ProfileSettingsCompression(enabled: true, threshold: 0.85, targetRatio: 0.2, protectFirst: 3, protectLast: 20))
        XCTAssertEqual(draft.threshold, "85")
        XCTAssertEqual(draft.target, "20")
        XCTAssertEqual(draft.contextLength, "")
        var changed = draft
        changed.threshold = "90"
        changed.contextLength = "200000"
        let patch = try CompressionRules.patch(changed).get()
        XCTAssertEqual(patch["threshold"], .double(0.9))
        XCTAssertEqual(patch["context_length"], .int(200000))
        XCTAssertEqual(try CompressionRules.patch(draft).get()["context_length"], .null)
        func invalid(_ edit: (inout CompressionRules.Draft) -> Void) -> CompressionRules.Field? {
            var d = draft
            edit(&d)
            if case .failure(let error) = CompressionRules.patch(d) { return error.field }
            return nil
        }
        XCTAssertEqual(invalid { $0.threshold = "101" }, .threshold)
        XCTAssertEqual(invalid { $0.contextLength = "512" }, .contextLength)
        XCTAssertEqual(invalid { $0.protectLast = "a" }, .protectLast)
        XCTAssertEqual(SettingsCardRules.saved(restartJobId: "01J8QK3ZR2W7M5N4P6T8V9X0JB", applies: .restart), .restarting)
        XCTAssertEqual(SettingsCardRules.saved(restartJobId: nil, applies: .restart), .restartNeeded)
        XCTAssertEqual(SettingsCardRules.saved(restartJobId: nil, applies: .nextMessage), .nextMessage)
    }

    // MARK: - Channels

    func testOnlyWhatIsLinkedOrWaitedOnIsListedEachCardOfferingWhatTheWebsDoes() {
        let whatsapp = channel("whatsapp", login: .qr, linked: true, configured: true, mode: .selfChat)
        let telegram = channel("telegram", login: .token, linked: true, configured: true)
        let discord = channel("discord", login: .credentials, linked: false)
        let matrix = channel("matrix", login: .credentials, linked: false)
        let legacy = channel("irc", configured: true, fields: [ChannelField(key: "IRC_SERVER", label: LocalizedText(ar: "خ", en: "Server"), kind: .text, target: .configuration, value: .string("irc.x"))])
        let webhook = channel("webhook", configured: true)
        let waiting = [PairingRequest(platform: "discord", requestId: "r1", userId: "u1", requestedAt: Fixture.date)]
        XCTAssertEqual(ChannelRules.shown([whatsapp, telegram, discord, matrix, legacy, webhook], pending: waiting).map(\.platform), ["whatsapp", "telegram", "discord", "irc"])
        XCTAssertEqual(ChannelRules.waitingOn(waiting, "discord"), 1)

        XCTAssertEqual(ChannelRules.actions(whatsapp, spec("whatsapp", .qr, settings: false)), [.mode, .replyHeader, .unlink])
        XCTAssertEqual(ChannelRules.actions(telegram, nil), [.settings, .unlink])
        XCTAssertEqual(ChannelRules.actions(discord, spec("discord", .credentials, settings: true)), [.link])
        XCTAssertEqual(ChannelRules.actions(discord, nil), [])
        XCTAssertEqual(ChannelRules.actions(channel("whatsapp", login: .qr, linked: false), nil), [.pair])
        XCTAssertEqual(ChannelRules.actions(legacy, nil), [.fields, .clear])
        XCTAssertEqual(ChannelRules.unlinkWords(telegram), .telegram)
        XCTAssertEqual(ChannelRules.unlinkWords(discord), .credentials)
    }

    func testTheReplyHeaderIsTheAgentsNameOrOneTypedLineOfAtMost64Characters() {
        XCTAssertFalse(ChannelRules.replyCustom(current: nil, agentName: "Office"))
        XCTAssertFalse(ChannelRules.replyCustom(current: "Office", agentName: "Office"))
        XCTAssertTrue(ChannelRules.replyCustom(current: "Desk", agentName: "Office"))
        XCTAssertEqual(ChannelRules.replyTitle(custom: true, agentName: "Office", typed: "  مكتب \n  الإدارة "), "مكتب الإدارة")
        XCTAssertEqual(ChannelRules.replyTitle(custom: false, agentName: "Office", typed: "ignored"), "Office")
        XCTAssertTrue(ChannelRules.replyUsable(String(repeating: "x", count: 64)))
        XCTAssertFalse(ChannelRules.replyUsable(String(repeating: "x", count: 65)))
        XCTAssertFalse(ChannelRules.replyUsable(""))
    }

    func testFieldsSplitByWhatTheyDeclaredAndAChannelsSettingsBecomeTheValuesTheHubTakes() {
        let fields = [
            ChannelField(key: "TOKEN", label: LocalizedText(ar: "ر", en: "Token"), kind: .secret, target: .credentials, value: .string("[stored]")),
            ChannelField(key: "SERVER", label: LocalizedText(ar: "خ", en: "Server"), kind: .text, target: .configuration, value: .string("irc.x")),
            ChannelField(key: "TLS", label: LocalizedText(ar: "ت", en: "TLS"), kind: .toggle, target: .configuration, value: .bool(true)),
        ]
        let write = ChannelRules.write(fields, draft: ["SERVER": .string("irc.y")])
        XCTAssertEqual(write.credentials, ["TOKEN": "[stored]"])
        XCTAssertEqual(write.configuration?["SERVER"], .string("irc.y"))
        XCTAssertEqual(write.configuration?["TLS"], .bool(true))

        let users = option("allowed_users", .list, value: .array([.string("1")]))
        let every = option("command_menu_max", .number, default: .int(30), min: 1, max: 100)
        let proxy = option("proxy_url", .text)
        let values = ChannelSettingRules.values([users, every, proxy], [
            "allowed_users": ChannelSettingRules.typed("12, 34،56 78"), "command_menu_max": ChannelSettingRules.typed(""), "proxy_url": .null,
        ])
        XCTAssertEqual(values["allowed_users"], .array(["12", "34", "56", "78"].map(JSONValue.string)))
        XCTAssertEqual(values["command_menu_max"], .null)
        XCTAssertEqual(values["proxy_url"], .null)
        XCTAssertEqual(ChannelSettingRules.values([every], ["command_menu_max": .string("40")])["command_menu_max"], .int(40))
        XCTAssertEqual(ChannelSettingRules.problems([every], ["command_menu_max": .string("500")]), ["command_menu_max"])
        XCTAssertEqual(ChannelSettingRules.shown(users, [:]), "1")
        XCTAssertEqual(ChannelSettingRules.default(every), .text("30"))
        XCTAssertEqual(ChannelSettingRules.default(option("x", .list, default: .array([]))), ChannelSettingRules.Default.none)
        XCTAssertEqual(ChannelSettingRules.default(option("x", .toggle, default: .bool(false))), .on(false))
    }

    func testAWebhooksNameEventsAddressAndWhereItsAnswerMayGo() {
        XCTAssertNil(WebhookRules.nameProblem("GitHub-Issues", taken: []))
        XCTAssertEqual(WebhookRules.nameProblem("-x", taken: []), .invalid)
        XCTAssertEqual(WebhookRules.nameProblem("ci", taken: ["ci"]), .taken)
        XCTAssertFalse(WebhookRules.ready(name: "ci", prompt: " ", taken: []))
        XCTAssertTrue(WebhookRules.ready(name: "ci", prompt: "Build {ref}", taken: []))
        XCTAssertEqual(WebhookRules.events("issues, push،issues  "), ["issues", "push"])
        XCTAssertEqual(WebhookRules.url(hub: "https://hub.example/", path: "/webhooks/w/ci"), "https://hub.example/webhooks/w/ci")
        let targets = WebhookRules.targets([channel("telegram", login: .token, linked: true, configured: true), channel("webhook", configured: true), channel("discord", linked: false)])
        XCTAssertEqual(targets.map(\.platform), ["telegram"])
        XCTAssertTrue(WebhookRules.accepted(202))
        XCTAssertFalse(WebhookRules.accepted(401))
    }
}
