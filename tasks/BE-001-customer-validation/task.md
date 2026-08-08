# BE-001 — Customer ID validation

## Requirement

When `POST /customers` receives an empty or blank `customerId`, return HTTP 400.

## Constraints

- The existing Kotlin + Spring Boot architecture must be preserved.
- Do not add new dependencies.
- Do not modify files outside the customer feature, except tests if required.

## Acceptance criteria

1. Existing build passes.
2. Existing tests pass.
3. New blank-ID test passes.
4. Valid customer creation still succeeds.
5. No new Maven dependency appears.
6. No unrelated production file changes.

## Notes for the agent

The service under test is `sample-service`. Run `./mvnw test` from `sample-service/`
to verify your work before finishing.
