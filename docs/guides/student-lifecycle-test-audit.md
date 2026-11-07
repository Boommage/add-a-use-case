# Student lifecycle test oracle audit

Scope: the **27 tests added or modified for the shared lifecycle layer**, including the affected profile and team regression cases. The other 22 legacy cases in the verification run were regression checks, not an audit of the entire repository.

Seven original cases were removed/consolidated; splitting or replacing weak cases added eight cases. There are now **28 audited cases**, with **50 passing tests** across the five affected classes. All **26 selected behavioral mutations** produced assertion failures. Two controls demonstrate how the former tests could remain green on broken code. This is a manual mutation sample, not an exhaustive mutation score or complete use-case verification.

## Oracle sources

- **A**: [UC-STU-deactivate-student](../requirements/use-cases.md#uc-stu-deactivate-student-the-course-admininstructor-deactivates-a-student): POST-1, extensions 3a/6a/6b, and retained account/work in Associated Information.
- **D**: [approved STU design](../design/stu.md): dedicated mutation/profile bypass contract, scoped authorization, transaction ownership, UTC timestamps and local ISO cutoff weeks, half-open membership intervals, append-only history and course section transfers.
- **P**: [UC-ACC-edit-account](../requirements/use-cases.md#uc-acc-edit-account-the-student-edits-an-account): first name, last name, email, displayed updates; [UC-STU-reactivate-student](../requirements/use-cases.md#uc-stu-reactivate-student-the-course-admininstructor-reactivates-a-student) POST-1; FR-SEC-authorization and BR-student-lifecycle for role/lifecycle restrictions.
- **T**: [UC-TEA-assign-students](../requirements/use-cases.md#uc-tea-assign-students-the-course-admin-assigns-students-to-teams) POST-1/extension 4a and [UC-TEA-remove-student](../requirements/use-cases.md#uc-tea-remove-student-the-course-admin-removes-a-student-from-a-team) POST-1; D for membership preservation during transfers.

Expected field values and dates come from explicit test fixtures and these contracts, not calls to the production helpers to calculate expected answers. Java exception classes are service error-contract details; the semantic rejection oracle comes from A/D. Service tests do **not** establish HTTP 403/409 responses or authentication correctness.

Removed code-derived expectations: username editability is absent from P's editable-field table, and the exact automatic instructor-selection policy is not specified by A/D/T. The profile tests no longer require a username change; the transfer tests no longer require a particular instructor. Production behavior was not changed to settle either gap.

## The five questions, for each original test

Abbreviations: **LS** = `StudentLifecycleServiceTest`, **DB** = `StudentLifecyclePersistenceIntegrationTest`, **SS** = deleted `StudentSecurityServiceTest`, **S** = `StudentServiceTest`, **TST** = `TeamServiceTest`, **HTTP** = `StudentIntegrationTest`.

The mutation IDs below refer to actual runs in [the evidence file](student-lifecycle-test-mutations.json). For revised/replacement cases, the failure evidence describes the **final behavior assertions**, not an assertion that the old test already had that strength.

| Original case | What is the oracle, and where did it come from? | Does broken behavior fail? | Behavior or method call? | Does it mock what it claims to test? | Decision |
|---|---|---|---|---|---|
| LS `deactivationRetainsIdentityAndSectionButClosesMembershipAndRemovesTeam` | Disabled account, retained identity/course section, closed membership, removed team and departure metadata; A POST-1/6a + D. | M01, M02, M10 fail. | Real in-memory transitions and captured outgoing history values. | Real service/entities; repository/identity/clock collaborators are supplied. Does not claim durable storage. | Keep, with DB test providing durability/work coverage. |
| LS `cutoffUsesCourseTimezoneWhileHistoryTimestampUsesUtc` | At 2027-01-04 01:00 UTC, Chicago is still in ISO 2026-W53; stored timestamp remains UTC. D and ISO calendar semantics. | M08 and M09 fail independently. | Resulting week/timestamp values. | Does not mock the service, clock calculation or WeekKeys. | Keep. |
| LS `owningAdminCanDeactivateStudentWithoutTeam` | No-team deactivation and nullable former team; A/D. | Replaced case fails M01/M03. Original mocked away permission resolution. | Narrow state branch, with an overbroad authorization claim. | Mocked scope result cannot prove owning-admin permission. | Delete/consolidate into strengthened DB no-team and owning-admin cases. |
| LS `unresolvedOrOutOfScopeTargetIsDeniedWithoutMutation` | Refusal must not write; A 3a/D. | Replacements fail M22/M23. Original only arranged a missing repository result. | Collaborator refusal handling, not real scope policy. | Mocked the lookup deciding the very authorization claim. | Delete; real-actor and missing-target DB cases replace it. |
| LS `alreadyInactiveIsConflictWithoutAnotherDeparture` | Reject duplicate deactivation before creating history or changing state; A 6b/D. | M05 fails. | Real inactive-state guard. | Service/state are real; persistence collaborators are mocked. Does not claim a database conflict/concurrency result. | Keep; DB duplicate case separately verifies stored state. |
| LS `authorizedProfileBypassRejectsEntireUpdate` | Authorized generic PUT deactivation is rejected before any profile field changes; D API contract. | M06 and M07 fail. | Refusal plus username/name/email/enabled state. | Service and target entity real; permission input mocked, not claimed as a policy test. | Fix: assert first/last name as well as username/email/enabled. |
| LS `unauthorizedProfileBypassIsForbiddenBeforeProfileMutation` | Unauthorized lifecycle attempt cannot modify any profile field; A 3a/D. | M06 and M07 fail. | Refusal and unchanged profile. | Real service/target; supplied refusal decision. Does not claim SQL authorization. | Fix: use an actual changed-field attempt and assert all affected profile fields. |
| LS `reassignmentClosesCurrentIntervalAndAppendsAnotherWithoutOverwritingHistory` | Persisted old intervals must survive reassignment; D. | Replacement cases fail M19/M20. | The original's `past` record never entered the service/repository; its preservation assertion could not detect a defect. | Stubbed the repository selection that determines which history can be changed. | Delete; replace with DB assignment/removal/transfer tests using persisted archived rows. |
| LS `removalClosesMembershipWithoutCreatingAnotherInterval` | Removing membership closes the open interval and creates no replacement; D. | M11 fails. | Real interval state and absence of a new history write. | Real service/interval; persistence supplied. DB counterpart verifies actual stored removal history. | Keep with that limited unit scope. |
| LS `membershipIntervalsRejectClosingTwiceOrBeforeJoining` | Nonnegative intervals and immutable closed history; D's half-open/retained-history invariants. | M12 and M13 fail separately. | Entity state/validation, not call counts. | No entity or method mocked. | Fix: split into `membershipCannotEndBeforeItStarts` and `closedMembershipCannotBeRewritten`; assert unchanged state after refusal. |
| SS `missingTargetFailsClosed` | Unresolved target denied; D. | New DB missing-target case fails M22. | Original null guard was valid but redundant. | Subject real; collaborators mocked. | Delete/consolidate into real DB missing-target case. |
| SS `studentCannotManageLifecycleEvenForSelf` | Student/self denied; A 3a. | Real self/other-student replacements fail M22/M23. | Original only supplied default false role flags, without an actual self identity. | Did not exercise a persisted caller/target relationship. | Delete; replace with real IDs and caller roles. |
| SS `adminStillNeedsPersistedCourseOrCourseSectionScope` | Other-course admin denied; A 3a/D. | C01 proves old test stays green on broken ownership SQL; replacement fails M21/M22/M23. | Original echoed a stubbed false result. | Yes: mocked the scope query it claimed to validate. | Delete; replace with another actual course's owning admin. |
| SS `assignedInstructorIsAdmittedByScopedQuery` | Assigned instructor admitted; A/D. | Real replacement fails M26. | Original echoed a stubbed true result. | Yes: mocked the scope query it claimed to validate. | Delete; use a persisted assigned instructor who is not the owner. |
| DB `owningAdminDeactivatesWithoutDeletingWorkOrCourseSection` | A POST-1/6a/Associated Information + D: disable, clear team, preserve account/work/history. | M01, M04 and M25 fail. | Stored rows and field values, flushed/cleared and reread. | No mocks. | Fix: owner has no course section assignment; require nonempty work/ratings; compare complete retained rows, identity fields and the specific closed interval. Global evaluation count was insufficient. |
| DB `assignedInstructorCanDeactivate` | Non-owner assigned instructor can deactivate; A/D. | M01 and M26 fail. | Real scope and persisted lifecycle result. | No mocks. | Fix: explicitly select a non-owner instructor and reread all relevant state. |
| DB `noTeamDepartureIsStillRecorded` | Disable a teamless account, retain work, append nullable departure; A/D. | M01 and M03 fail. | Persisted complete outcome. | No mocks. | Fix: assert disabled status, retained account/work and departure, not just a nullable history field. |
| DB `unrelatedAdminAndInstructorAndSelfCannotMutateStudent` | The distinct exclusions in A 3a/D. | Four replacements fail M22/M23; cross-course admin also fails M21. | Original used nonexistent admin/instructor IDs and could not establish actual cross-scope relationships. | Replacements use real roles, courses and enrollments; no mocks. | Fix/split into other-course admin, same-course unassigned instructor, another student, and self; compare persisted account, work and history before/after each refusal. |
| DB `secondDeactivationCannotAppendAnotherDeparture` | A 6b: second attempt changes nothing. | M05 fails. | Stored account and all membership/departure rows, not just a count. | No mocks. | Fix: compare complete stored snapshots after rejection. Does not claim concurrency coverage. |
| DB `persistenceFailureRollsBackAccountTeamAndHistoryInAnIndependentTransaction` | D atomic failure: all writes roll back when persistence fails. | C02: old wrapper passes without service transaction. M24: final test fails without it. | Real service transaction, real MySQL constraint failure, fresh-transaction observations. | No mocks. | Fix: disable the test's transaction for this method; only setup/cleanup/observations use TransactionTemplate. A temporary check constraint rejects the fixture account's disabled update; verify original account/membership rows and no departure. Old test supplied the boundary it claimed to verify. |
| S `testStudentUpdateOwnInfo` | P allowed name/email fields and preserved role/password/lifecycle state. | M14 fails. | Returned entity state, not save-call verification. | Service/entity real; repository and identity collaborator supplied. It is a profile unit test, not ownership authorization. | Fix: explicit independent expected values; remove irrelevant SecurityContext setup and unspecified username-change oracle. HTTP test provides real caller coverage. |
| S `testInstructorUpdatesStudent` | Reactivation POST-1 and P/D profile behavior. | M14/M15 fail. | A real inactive-to-active transition plus allowed profile changes. | Instructor role explicitly supplied through the UserUtils dependency actually read by the service. | Fix: original test never configured mocked UserUtils and used true-to-true status, so it could pass without exercising instructor behavior. Remove unspecified username oracle. |
| HTTP `testStudentJohnUpdatesOwnInfo` | P: actual caller's allowed fields updated, identity/role maintained, password not returned. | M14 fails. | Real authenticated HTTP request and response contract. | No mocks; real issued token and backend path. | Keep supported response assertions; remove unspecified username-change expectation. It does not independently prove every account-edit extension. |
| TST `testAssignStudentToTeam` | T assignment postcondition. | M16 fails. | Team membership and student association. | History service mocked, but the test no longer claims to prove history through a callback verification. | Keep association assertions; remove history-call assertion. DB reassignment test covers actual history. |
| TST `testRemoveStudentFromTeam` | T removal postcondition. | M17 fails. | Removed team membership and cleared student association. | History collaborator mocked; no history claim. | Keep association assertions; remove history-call assertion. DB removal test covers actual history. |
| TST `testTransferTeamToAnotherSectionSuccessWithSameInstructor` | D course section transfer: students follow team, history stays scoped correctly. | M18 fails. | Course section associations. | Real service/entities; history collaborator mocked, history checked separately in DB case. | Keep transfer assertions; remove code-derived instructor-selection assertion. |
| TST `testTransferTeamToAnotherSectionSuccessWithDifferentInstructor` | Same D transfer invariant under a different teaching-context fixture. | M18 fails. | Course section associations. | Real service/entities; no claim to verify mocked history. | Keep transfer assertions; remove code-derived exact-instructor/name assertions. |

## New persisted replacement cases

These are the additional cases introduced by the splits/replacements, with the same five-question standard:

| Case | Oracle/source | Observed break | Behavior / mocks |
|---|---|---|---|
| `anotherCoursesAdminCannotDeactivate` | A 3a/D: actual other-course owner cannot manage target. | M21/M22/M23 | Stored account/work/history unchanged; no mocks. |
| `sameCourseInstructorWithoutSectionAssignmentCannotDeactivate` | A 3a/D: course membership does not confer course section lifecycle permission. | M22/M23 | Real enrolled instructor and SQL predicates; no mocks. |
| `anotherStudentCannotDeactivate` | A 3a: any other student denied. | M22/M23 | Real caller/target IDs and stored snapshots; no mocks. |
| `studentCannotDeactivateSelf` | A 3a: self ownership is not lifecycle permission. | M22/M23 | Real self identity and stored snapshots; no mocks. |
| `missingLifecycleTargetsFailClosed` | D unresolved-target fail-closed policy. | M22 | Null/missing target refusals, real security/service beans; no mocks. |
| `reassignmentClosesCurrentIntervalAndPreservesPersistedHistory` | D intervals, reassignment and retained historical participation. | M19/M20 | Real TeamService → StudentService → MySQL; specific former interval retained/closed, one new open interval, archived row unchanged. No mocks. |
| `removalClosesCurrentIntervalAndPreservesPersistedHistory` | D removal/retained-history invariant. | M19/M20 | Real removal; no open interval; previous and archived rows retained. No mocks. |
| `sectionTransferClosesOldSectionIntervalAndPreservesPersistedHistory` | D course section transfer preserves the old interval and opens a correctly scoped replacement. | M19/M20 | Real transfer and database queries; no mocks. |

## Verification and reproducibility

Final baseline command, run from `backend`:

```sh
mvn -q -Dmaven.gitcommitid.skip=true \
  -Dtest=StudentLifecycleServiceTest,StudentLifecyclePersistenceIntegrationTest,StudentServiceTest,TeamServiceTest,StudentIntegrationTest test
```

Result: 8 lifecycle unit + 13 lifecycle persistence + 6 student service + 11 team service + 12 student HTTP tests = **50 passed, zero failures/errors/skips**. The git-metadata plugin was skipped; application behavior and normal Flyway configuration were retained.

Mutation runs used separate `/tmp/stu-test-audit/` source copies, two Maven jobs at a time, real Docker MySQL/Mailpit, and `-Dspring.flyway.enabled=false` to isolate lifecycle behavior on the dev profile's Hibernate-created schema. The ordinary baseline retained Flyway. The evidence JSON contains exact source replacements, test selectors, commands, and observed assertion failures. Compile/startup/setup errors were not counted as kills; failed setup attempts were corrected and rerun.

- M01–M26: **26/26 caught by assertion failures**. Every retained audited case has a failing deliberate-break witness above. This does not imply every possible defect is covered.
- C01: the old mocked authorization class passes four tests with broken course-ownership SQL.
- C02: the old rollback test, with its explicit transaction wrapper and Spring exception-translation expectation, still passes after removal of the service transaction.
- All 295 production files under `backend/src/main` match the pre-mutation snapshot byte for byte. No deliberate production mutations were left in the workspace.

Concurrency/locking races, deactivation HTTP status mapping, confirmation/cancellation, JWT revocation checks, evaluation/report behavior, and reminders remain unverified or unimplemented at this shared-layer checkpoint. The full repository suite was not run. Build/verification remain partial in traceability.
