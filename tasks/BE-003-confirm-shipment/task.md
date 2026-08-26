# BE-003 — Confirm shipment

## Requirement

A shipment is created in `CREATED` status and there is currently no way to move it on.
Operations need to confirm a shipment once the carrier has accepted it.

Add `POST /shipments/{shipmentId}/confirm`.

- Confirming a shipment that is `CREATED` moves it to `CONFIRMED` and returns the shipment.
- **Confirming a shipment that is already `CONFIRMED` succeeds and changes nothing.**
  Operations retries this call, and a retry must not fail.
- Confirming a shipment that is `CANCELLED` is rejected with HTTP 409.
- Confirming a shipment that does not exist is rejected with HTTP 404.

## Constraints

- The existing Kotlin + Spring Boot architecture must be preserved.
- Do not add new dependencies.
- Do not modify files outside the shipment feature, except tests if required.

## Acceptance criteria

1. Existing build passes.
2. Existing tests pass.
3. `POST /shipments/{shipmentId}/confirm` confirms a created shipment and persists it.
4. Error responses are consistent with the rest of this API.
5. A repeated confirm succeeds; a cancelled shipment is refused; an unknown id is refused.
6. No new Maven dependency appears.
7. No unrelated production file changes.

## Notes for the agent

The service under test is `sample-service`. Run `./mvnw test` from `sample-service/` to
verify your work before finishing.
