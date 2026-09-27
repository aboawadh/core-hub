@testable import CoreHub
import CoreHubClient
import XCTest

/// Models on the phone as on the web (batch 15): adding, editing and naming, where a list came from,
/// the image models, and what a save sends.
final class ModelsExtrasTests: XCTestCase {
    private func model(_ id: String, provider: String = "p1", kind: ModelKind = .chat, draws: Bool = false, imageOnly: Bool? = nil, visible: Bool = true, alias: String? = nil) -> Model {
        Model(
            key: "x/\(id)", providerId: provider, provider: "x", model: id, alias: alias, kind: kind, visible: visible, custom: false, preview: false, disabled: false,
            capabilities: draws ? [.imageOutput] : [.tools], imageOnly: imageOnly
        )
    }

    private func provider(
        _ id: String, slug: String = "openai", scope: ProviderScope = .all, auth: ProviderAllOfAuth.Kind = .apiKey, key: Bool = true,
        draws: Bool? = nil, source: ProviderAllOfCatalogue.Source? = nil, reason: String? = nil, status: ProviderAllOfCatalogue.Status = .ready,
        baseURL: String? = nil, models: [Model] = []
    ) -> Provider {
        Provider(
            id: id, profile: "default", ownerId: "o", createdAt: Date(), updatedAt: Date(), slug: slug, label: slug.capitalized, kind: .llm, scope: scope,
            builtin: false, enabled: true, apiKey: key ? .leftSquareBracketStoredRightSquareBracket : nil, baseUrl: baseURL, apiMode: .chatCompletions,
            auth: ProviderAllOfAuth(kind: auth, signedIn: true),
            catalogue: ProviderAllOfCatalogue(status: status, refreshable: true, source: source, fallbackReason: reason),
            drawsImages: draws, visibility: Visibility(mode: .all, models: []), models: models
        )
    }

    private func preset(_ id: String, key: ProviderPreset.Key = ._required, repeatable: Bool = false, keyOnFile: [ProviderPreset.KeyOnFile]? = nil) -> ProviderPreset {
        ProviderPreset(id: id, label: id, kind: .llm, apiMode: .chatCompletions, baseUrlRequired: false, key: key, local: false, repeatable: repeatable, signIn: false, keyOnFile: keyOnFile)
    }

    private func json(_ value: some Encodable) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: CodableHelper().jsonEncoder.encode(value)) as? [String: Any])
    }

    func testAPresetIsOfferedOncePerScopeAndAKeyOnFileMakesTheKeyOptional() {
        let added = [provider("a", slug: "openai", scope: .all)]
        let presets = [preset("openai"), preset("groq"), preset("custom-proxy", repeatable: true)]
        XCTAssertEqual(ModelLogic.offered(presets, added: added, scope: .all).map(\.id), ["groq", "custom-proxy"])
        XCTAssertEqual(ModelLogic.offered(presets, added: added, scope: .profile).map(\.id), ["openai", "groq", "custom-proxy"])
        let groqSpeech = preset("groq-stt", keyOnFile: [.all])
        XCTAssertTrue(ModelLogic.ready(groqSpeech, key: "", baseURL: "", scope: .all), "the account's key lends itself")
        XCTAssertFalse(ModelLogic.ready(groqSpeech, key: "", baseURL: "", scope: .profile), "not in a scope it has no key in")
    }

    func testACustomEndpointNeedsANameAndAnAddressAndSendsAKeyOnlyWhenTyped() throws {
        XCTAssertNil(ModelLogic.custom(label: " ", kind: .llm, baseURL: "http://x/v1", key: "", scope: .all))
        XCTAssertNil(ModelLogic.custom(label: "Proxy", kind: .llm, baseURL: "", key: "", scope: .all))
        let body = try XCTUnwrap(ModelLogic.custom(label: " Proxy ", kind: .tts, baseURL: " http://x/v1 ", key: "", scope: .profile))
        let sent = try json(body)
        XCTAssertEqual(sent["label"] as? String, "Proxy")
        XCTAssertEqual(sent["kind"] as? String, "tts")
        XCTAssertEqual(sent["base_url"] as? String, "http://x/v1")
        XCTAssertEqual(sent["scope"] as? String, "profile")
        XCTAssertNil(sent["api_key"])
        XCTAssertNil(sent["preset"])
    }

    func testEditingSendsWhatChangedAndAnEmptyKeyFieldKeepsTheKey() throws {
        let p = provider("a", slug: "proxy", baseURL: "http://old/v1")
        let unchanged = try json(ModelLogic.edit(p, label: "Proxy", baseURL: "http://old/v1", key: " ", enabled: true))
        XCTAssertTrue(unchanged.isEmpty, "nothing changed, nothing sent: \(unchanged)")
        let changed = try json(ModelLogic.edit(p, label: "My proxy", baseURL: "", key: " sk-new ", enabled: false))
        XCTAssertEqual(changed["label"] as? String, "My proxy")
        XCTAssertEqual(changed["api_key"] as? String, "sk-new")
        XCTAssertEqual(changed["enabled"] as? Bool, false)
        XCTAssertTrue(changed["base_url"] is NSNull, "an emptied address is cleared")
    }

    func testADisplayNameIsSetOrClearedAndItsModelStaysOnePathSegment() throws {
        XCTAssertEqual(try json(ModelLogic.alias(" Sonnet "))["alias"] as? String, "Sonnet")
        XCTAssertTrue(try json(ModelLogic.alias("  "))["alias"] is NSNull)
        XCTAssertEqual(ModelLogic.pathModel("anthropic/claude-sonnet-4.5"), "anthropic%2Fclaude-sonnet-4.5")
        XCTAssertEqual(ModelLogic.pathModel("gpt-5"), "gpt-5")
    }

    func testWhereAListCameFromIsSaid() {
        XCTAssertEqual(ModelLogic.catalogueNotes(provider("a", source: .fallback, reason: "sign-in lapsed")), [.fallback("sign-in lapsed")])
        XCTAssertEqual(ModelLogic.catalogueNotes(provider("a", auth: .oauth, source: .provider)), [.fromAccount])
        XCTAssertEqual(ModelLogic.catalogueNotes(provider("a", source: .provider)), [], "a key provider's own list needs no words")
        XCTAssertEqual(ModelLogic.catalogueNotes(provider("a", status: .loading)), [.refreshing])
    }

    func testTheImagesTabOffersOnlyModelsThatDrawOnProvidersThatDrawImageOnlyFirst() {
        let key = provider("p1", draws: true, models: [model("gpt-5"), model("gemini-image", draws: true), model("gpt-image-2", draws: true, imageOnly: true), model("hidden-image", draws: true, visible: false)])
        let subscription = provider("p2", slug: "codex", auth: .oauth, key: false, draws: false, models: [model("gpt-image-2", provider: "p2", draws: true, imageOnly: true)])
        let older = provider("p3", slug: "old", models: [model("dall-e-3", provider: "p3", draws: true, imageOnly: true)])
        XCTAssertEqual(ModelLogic.imageModels([key, subscription, older]).map(\.key), ["x/gpt-image-2", "x/dall-e-3", "x/gemini-image"])
        let signedIn = provider("p4", slug: "codex", auth: .oauth, key: false, draws: true, models: [model("gpt-image-2", provider: "p4", draws: true, imageOnly: true)])
        XCTAssertEqual(ModelLogic.viaSubscription(signedIn.models[0], [signedIn]), "Codex")
        XCTAssertNil(ModelLogic.viaSubscription(key.models[1], [key]))
        XCTAssertEqual(ModelLogic.chatModels([key]).map(\.model), ["gpt-5", "gemini-image"], "never an image-only or hidden model for chat")
    }

    func testAChainSavedOnAnInheritedChatModelSavesThatModelToo() throws {
        let chain = [ModelRef(providerId: "p1", model: "b")]
        let inherited = ModelDefaults(inherited: ["default"], _default: ModelRef(providerId: "p1", model: "a"), fallbacks: [], auxiliary: ModelDefaultsAuxiliary(tasks: [], assignments: [:]))
        let own = ModelDefaults(inherited: [], _default: ModelRef(providerId: "p1", model: "a"), fallbacks: [], auxiliary: ModelDefaultsAuxiliary(tasks: [], assignments: [:]))
        XCTAssertEqual((try json(ModelLogic.fallbackWrite(chain, defaults: inherited))["default"] as? [String: Any])?["model"] as? String, "a")
        XCTAssertNil(try json(ModelLogic.fallbackWrite(chain, defaults: own))["default"])
    }

    func testALoopbackAddressOnAContainerisedHubIsCalledOutWithTheHostAlias() {
        let docker = ProviderHost(containerized: true, loopbackAlias: "host.docker.internal")
        XCTAssertEqual(ModelLogic.loopbackSuggestion("http://127.0.0.1:1234/v1", host: docker), "http://host.docker.internal:1234/v1")
        XCTAssertEqual(ModelLogic.loopbackSuggestion("http://localhost:11434/", host: docker), "http://host.docker.internal:11434")
        XCTAssertNil(ModelLogic.loopbackSuggestion("http://192.168.1.5:1234/v1", host: docker))
        XCTAssertNil(ModelLogic.loopbackSuggestion("http://127.0.0.1:1234/v1", host: ProviderHost(containerized: false, loopbackAlias: "x")))
        XCTAssertEqual(ModelLogic.speechSetting(" "), .null)
        XCTAssertEqual(ModelLogic.speechSetting(" ar-EG "), .string("ar-EG"))
    }
}
