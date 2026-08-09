# BE-002 — Order amount validation

## Requirement

`POST /orders` currently accepts any amount. An order with an amount of `0` or a negative
amount is created and stored, which produces invoices for nothing and refunds nobody
asked for.

Reject an order whose `amount` is not greater than zero with HTTP 400.

## Constraints

- The existing Kotlin + Spring Boot architecture must be preserved.
- Do not add new dependencies.
- Do not modify files outside the order feature, except tests if required.

## Acceptance criteria

1. Existing build passes.
2. Existing tests pass.
3. `POST /orders` with an amount of `0` or a negative amount returns HTTP 400.
4. The error response is consistent with the rest of this API.
5. Creating a valid order still succeeds.
6. No new Maven dependency appears.
7. No unrelated production file changes.

## Notes for the agent

The service under test is `sample-service`. Run `./mvnw test` from `sample-service/`
to verify your work before finishing.
