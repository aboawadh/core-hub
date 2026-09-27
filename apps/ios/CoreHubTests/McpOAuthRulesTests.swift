@testable import CoreHub
import CoreHubClient
import XCTest

/// A remote MCP server's OAuth sign-in on the row (DECISIONS §121), as the web's page offers it.
/// Android's McpOAuthTest checks the same.
final class McpOAuthRulesTests: XCTestCase {
    func testTheSignInIsOfferedOnlyWhereHermesCanSignIn() {
        let oauth: [String: JSONValue] = ["url": .string("https://mcp.clickup.example/mcp"), "auth": .string("oauth")]
        XCTAssertTrue(McpOAuthRules.signsInByOAuth(oauth))
        XCTAssertTrue(McpOAuthRules.offers(transport: .http, config: oauth, status: .notConnected))
        // A server that signs in with its own header, an older hub, a process: nothing to offer.
        let header: [String: JSONValue] = ["url": .string("https://x.example"), "headers": .dictionary(["Authorization": .string("[stored]")])]
        XCTAssertFalse(McpOAuthRules.offers(transport: .http, config: header, status: .notConnected))
        XCTAssertFalse(McpOAuthRules.offers(transport: .http, config: oauth, status: nil))
        XCTAssertFalse(McpOAuthRules.offers(transport: .stdio, config: ["command": .string("npx")], status: .notConnected))
        XCTAssertTrue(McpOAuthRules.offers(transport: .sse, config: header, status: .expired))
    }

    func testAFailedTestThatWantsASignInIsRecognised() {
        XCTAssertTrue(McpOAuthRules.needsSignIn("OAuth authentication required — no token found."))
        XCTAssertTrue(McpOAuthRules.needsSignIn("HTTP 401 Unauthorized"))
        XCTAssertFalse(McpOAuthRules.needsSignIn("[Errno 2] No such file or directory"))
        XCTAssertFalse(McpOAuthRules.needsSignIn(nil))
    }
}
