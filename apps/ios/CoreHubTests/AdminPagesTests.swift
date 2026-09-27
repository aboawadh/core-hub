@testable import CoreHub
import CoreHubClient
import XCTest

/// The admin pages on the phone (apps batches 12 and 14): People, Profiles, push senders, pairing.
final class AdminPagesTests: XCTestCase {
    private func user(_ id: String, _ role: Role, profiles: [String] = [], name: String = "") -> User {
        User(id: id, username: "u\(id)", displayName: name, role: role, status: .active, locale: .ar, avatar: Avatar(kind: .generated, seed: "a"),
             profiles: profiles, defaultProfile: "default", createdAt: Fixture.date, updatedAt: Fixture.date)
    }

    // MARK: - People

    func testARowOffersOnlyWhatTheHubAccepts() {
        let owner = user("o", .owner)
        let admin = user("a", .admin)
        XCTAssertEqual(PeopleRules.row(owner, me: "a"), .ownerNote)
        XCTAssertEqual(PeopleRules.row(owner, me: "o"), .ownPassword)
        XCTAssertEqual(PeopleRules.row(admin, me: "o"), .full)
        XCTAssertFalse(PeopleRules.canDisableOrDelete(admin, me: "a"), "nobody disables or deletes themselves")
        XCTAssertTrue(PeopleRules.canDisableOrDelete(admin, me: "o"))
        XCTAssertFalse(PeopleRules.canDisableOrDelete(owner, me: "a"))
    }

    func testAMemberReachesExactlyTheListedProfiles() {
        XCTAssertEqual(PeopleRules.reach(user("a", .admin, profiles: ["default"])), .every)
        XCTAssertEqual(PeopleRules.reach(user("m", .member)), PeopleRules.Reach.none)
        XCTAssertEqual(PeopleRules.reach(user("m", .member, profiles: ["work"])), .listed)
    }

    func testMakingAnAdminAMemberSendsTheListWithTheRole() {
        let admin = user("a", .admin, profiles: ["default", "work"])
        XCTAssertTrue(PeopleRules.startingChoice(admin, makeMember: true).isEmpty)
        XCTAssertEqual(PeopleRules.startingChoice(admin, makeMember: false), ["default", "work"])
        XCTAssertNil(PeopleRules.profilesPatch([], makeMember: true))
        XCTAssertEqual(PeopleRules.profilesPatch(["work"], makeMember: true), UserAdminPatch(role: .member, profiles: ["work"]))
        XCTAssertEqual(PeopleRules.profilesPatch(["work"], makeMember: false), UserAdminPatch(profiles: ["work"]))
        let sent = Fixture.json(PeopleRules.profilesPatch(["work"], makeMember: true)!)
        XCTAssertEqual(sent["role"] as? String, "member")
        XCTAssertEqual(sent["profiles"] as? [String], ["work"])
        XCTAssertEqual(PeopleRules.nameOf("s", in: [user("s", .member, name: "سارة")]), "سارة")
        XCTAssertEqual(PeopleRules.nameOf("s", in: [user("s", .member)]), "us")
        XCTAssertEqual(PeopleRules.nameOf("gone", in: []), "gone")
    }

    func testLockoutTimesKeepLatinDigits() {
        XCTAssertTrue(LockoutsSection.until(Fixture.date).range(of: "^[0-9]{2}:[0-9]{2}$", options: .regularExpression) != nil)
    }

    // MARK: - Profiles

    func testANewProfileNeedsAFreeSlugANameAndASourceWhenCopied() {
        XCTAssertEqual(ProfileRules.suggest(" My Team! "), "my-team")
        XCTAssertEqual(ProfileRules.suggest("فريقي"), "")
        XCTAssertEqual(ProfileRules.followName(slug: "", old: "", new: "My Team"), "my-team")
        XCTAssertEqual(ProfileRules.followName(slug: "custom", old: "My", new: "My Team"), "custom")
        XCTAssertEqual(ProfileRules.slugProblem("-x", taken: []), .bad)
        XCTAssertEqual(ProfileRules.slugProblem("work", taken: ["work"]), .taken)
        XCTAssertNil(ProfileRules.create(name: "Team", slug: "work", copy: false, cloneFrom: nil, taken: ["work"]))
        XCTAssertNil(ProfileRules.create(name: "Team", slug: "team", copy: true, cloneFrom: nil, taken: []), "a copy asks which one")
        let copy = ProfileRules.create(name: " Team ", slug: "team", copy: true, cloneFrom: "default", taken: [])
        XCTAssertEqual(copy?.name, "Team")
        XCTAssertEqual(copy?.cloneFrom, "default")
        XCTAssertNil(ProfileRules.create(name: "Team", slug: "team", copy: false, cloneFrom: "default", taken: [])?.cloneFrom)
        XCTAssertNil(ProfileRules.rename(current: "Work", typed: " Work "), "an unchanged name is not sent")
        XCTAssertEqual(ProfileRules.rename(current: "Work", typed: " العمل ")?.name, "العمل")
        XCTAssertFalse(ProfileRules.canArchive("default"))
        XCTAssertTrue(ProfileRules.canArchive("work"))
    }

    func testAnArchiveSuggestsItsSlugAndItsNameMadeFree() {
        XCTAssertEqual(ProfileRules.slugFromArchive("design-20260924-101500.tar.gz"), "design")
        XCTAssertEqual(ProfileRules.nameFromArchive("الرئيسي-20260925-101500.tar.gz"), "الرئيسي")
        XCTAssertEqual(ProfileRules.freeSlug("design", taken: ["design"]), "design-2")
        let offered = ProfileRules.fromArchive("design-20260924-101500.tar.gz", taken: ["design"])
        XCTAssertEqual(offered.slug, "design-2")
        XCTAssertEqual(offered.name, "design-2")
        let arabic = ProfileRules.fromArchive("العمل-20260925-101500.tgz", taken: [])
        XCTAssertEqual(arabic.slug, "")
        XCTAssertEqual(arabic.name, "العمل")
        XCTAssertNil(ProfileRules.importBody(attachmentID: "att1", slug: "team", name: "  ").name)
        XCTAssertEqual(ProfileRules.size(120 * 1024), "120 KB")
        XCTAssertEqual(ProfileRules.size(Int(3.4 * 1_048_576)), "3.4 MB")
    }

    private func job(_ status: JobStatus, result: [String: JSONValue]? = nil, error: ModelError? = nil) -> Job {
        Job(id: "j1", profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date, kind: .export, status: status,
            progress: JobProgress(), result: result, error: error)
    }

    func testAnExportsResultSaysWhatIsInTheFileAndAFailureSaysWhy() {
        let done = job(.succeeded, result: [
            "attachment_id": .string("att9"), "name": .string("Work-20260927-010203.tar.gz"), "size_bytes": .int(2048),
            "removed": .array([.string("work/.env")]), "masked": .array([.string("work/config.yaml")]), "providers": .int(2),
        ])
        let result = ProfileRules.exported(done)
        XCTAssertEqual(result?.attachmentID, "att9")
        XCTAssertEqual(result?.removed, ["work/.env"])
        XCTAssertEqual(result?.masked, ["work/config.yaml"])
        XCTAssertEqual(result?.providers, 2)
        XCTAssertNil(ProfileRules.exported(job(.running)))
        XCTAssertFalse(ProfileRules.finished(job(.running)))
        XCTAssertEqual(ProfileRules.failure(job(.failed, error: ModelError(error: "Hermes said no", code: .stateInvalid))), "Hermes said no")
        XCTAssertEqual(ProfileRules.importedName(job(.succeeded, result: ["slug": .string("team"), "name": .string("Team")])), "Team")
        var refused = HubFailure(kind: .http, status: 409, code: "conflict", message: "x", operationID: nil, requestID: nil, detail: "")
        XCTAssertNil(ProfileRules.hermesRefusal(refused))
        refused.reason = "hermes_refused"
        refused.detailMessage = "Hermes: bad"
        XCTAssertEqual(ProfileRules.hermesRefusal(refused), "Hermes: bad")
    }

    // MARK: - Push senders

    private let account = """
    {"type":"service_account","project_id":"corehub-1","client_email":"a@b.iam.gserviceaccount.com","private_key":"-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----\\n"}
    """
    private let p8 = "-----BEGIN PRIVATE KEY-----\nMIGT\n-----END PRIVATE KEY-----"

    func testTheFilesFirebaseAndAppleHandOutAreRecognised() {
        XCTAssertEqual(try? PushSenderRules.inspectServiceAccount(account).get(), "corehub-1")
        func problem(_ text: String) -> PushSenderRules.AccountProblem? {
            if case .failure(let error) = PushSenderRules.inspectServiceAccount(text) { return error.problem }
            return nil
        }
        XCTAssertEqual(problem("nope"), .notJSON)
        XCTAssertEqual(problem(#"{"project_info":{},"client":[]}"#), .googleServices)
        XCTAssertEqual(problem(#"{"type":"authorized_user"}"#), .notServiceAccount)
        XCTAssertEqual(problem(#"{"type":"service_account","project_id":"x"}"#), .missing)
        XCTAssertEqual(try? PushSenderRules.inspectP8(fileName: "AuthKey_ABCDE12345.p8", text: p8).get(), "ABCDE12345")
        XCTAssertEqual(PushSenderRules.keyID(fromFileName: "AuthKey_abcde12345 (1).p8"), "ABCDE12345")
        XCTAssertNil(PushSenderRules.keyID(fromFileName: "key.p8"))
        if case .failure(let error) = PushSenderRules.inspectP8(fileName: "cert.json", text: "{}") { XCTAssertEqual(error.problem, .notP8) } else { XCTFail() }
        if case .failure(let error) = PushSenderRules.inspectP8(fileName: "AuthKey_ABCDE12345.p8", text: "hello") { XCTAssertEqual(error.problem, .notAKey) } else { XCTFail() }
        XCTAssertEqual(PushSenderRules.ending("ABCDE12345"), "2345")
    }

    private func sender(_ provider: PushProvider, _ source: PushSender.Source, _ state: PushSender.State = .ready, details: [String: String] = [:]) -> PushSender {
        PushSender(provider: provider, state: state, source: source, missing: [], details: details, devices: 2)
    }

    func testAStoredSecretIsKeptWhenItsFieldStaysEmptyAndNeverFilledBackIn() {
        let fcm = sender(.fcm, .settings, details: ["project_id": "corehub-1", "service_account": "[stored]"])
        let form = PushSenderRules.form(fcm)
        XCTAssertEqual(form.serviceAccount, "")
        XCTAssertTrue(PushSenderRules.ready(fcm, form), "stored and untouched: save keeps it")
        XCTAssertEqual(PushSenderRules.body(fcm, form).serviceAccount, "[stored]")
        XCTAssertFalse(PushSenderRules.ready(sender(.fcm, ._none, .notConfigured), form))

        let apns = sender(.apns, .settings, details: ["key_id": "ABCDE12345", "team_id": "TEAM1", "bundle_id": "hub.core.app", "environment": "sandbox", "private_key": "[stored]"])
        var apnsForm = PushSenderRules.form(apns)
        XCTAssertTrue(apnsForm.sandbox)
        XCTAssertEqual(apnsForm.privateKey, "")
        let body = PushSenderRules.body(apns, apnsForm)
        XCTAssertNil(body.privateKey, "an empty key is left out")
        XCTAssertEqual(body.teamId, "TEAM1")
        XCTAssertEqual(body.environment, .sandbox)
        apnsForm.teamID = " "
        XCTAssertFalse(PushSenderRules.ready(apns, apnsForm))
        XCTAssertEqual(PushSenderRules.saved(apns), [.key("2345"), .team("TEAM1"), .sandbox])
        XCTAssertFalse(PushSenderRules.editable(sender(.webpush, .generated)), "Web Push is the hub's own")
        XCTAssertFalse(PushSenderRules.editable(sender(.fcm, .environment)), "the environment wins")
        XCTAssertEqual(PushSenderRules.outcome(sender(.apns, .settings, .notConfigured)), .incomplete)
        XCTAssertEqual(PushSenderRules.outcome(sender(.apns, .settings, .error)), .refused)
    }

    // MARK: - Pairing

    private func agent(_ id: String, _ status: AgentStatus, _ capabilities: [AgentCapability]) -> Agent {
        Agent(id: id, profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date, slug: id,
              name: id, kind: .hermes, avatar: Avatar(kind: .generated, seed: "a"), status: status, enabled: true,
              install: AgentInstall(source: .managed, updateAvailable: false, newerThanTested: false, autoUpdate: false, autoUpdateSupported: false),
              runtime: AgentRuntime(state: .running), capabilities: capabilities, sections: [], limited: false, subagents: ._none)
    }

    func testPairingIsAskedOfTheInstalledAgentThatHasChannels() {
        XCTAssertNil(PairingRules.channelAgent([agent("a", .available, [])]))
        XCTAssertNil(PairingRules.channelAgent([agent("a", .notInstalled, [.channels])]))
        XCTAssertEqual(PairingRules.channelAgent([agent("a", .available, []), agent("b", .limited, [.channels])])?.id, "b")
    }
}
