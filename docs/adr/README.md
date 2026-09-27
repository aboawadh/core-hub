# Architecture Decision Records

One file per decision, numbered, never edited after acceptance: a new ADR
supersedes an old one. Template: Context · Decision · Alternatives rejected ·
Consequences · Status.

Standing rule for every change: [ADR 0027 — No breaking changes](0027-compatibility-no-breaking-changes.md).
What people already run (hubs, apps, scripts, data) keeps working; CI compares the contract and
the migrations with the latest release.
