# ADR-0001: RefractoryAdvisor ⊣ Refractory Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2391` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2391` publishes an OSS blueprint for refractory-
products (fire brick, kiln lining, furnace lining materials) plant
**operations coordination** (production-batch product-type/weight/
thermal-shock-resistance/cold-crushing-strength data logging,
pressing-line/kiln-line-equipment maintenance scheduling,
safety-concern flagging, and outbound refractory-product shipment
coordination). Like every actor in this fleet, the blueprint alone is
not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the
same langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest architectural analog is `cloud-itonami-isic-2392` (Manufacture
of clay building materials): both are back-office coordination actors
for a fixed processing PLANT with heavy kiln-firing equipment and a
real physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). The two verticals are, however, distinct
plants with distinct hazard profiles and distinct product/quality
vocabularies: 2392's central physical hazard is kiln-firing heat and
clay/silica-dust exposure at building-material firing temperatures,
while 2391's is a hotter, higher-temperature-service kiln-firing line
(refractory products must themselves withstand furnace-service
temperatures well above building-material brick) plus refractory-dust
exposure (crystalline silica/alumina/magnesia dust at batching and
pressing, a well-documented silicosis risk in this industry) and
pressing-line-equipment pinch-point hazard. This build mirrors 2392's
architecture closely but adapts the hazard profile and equipment/
product vocabulary to the refractory-products plant: 2391's permanent
equipment-actuation block guards a pressing-line press/kiln line
(`:actuate-kiln-pressing-line?`) rather than an extrusion-press/kiln
line (`:actuate-kiln-line?`); and 2391's production-batch record
declares a `:product-type` (spanning fireclay, silica, high-alumina,
magnesia, insulating-firebrick, monolithic-castable, ramming-mix and
kiln-furniture families, per ISIC 2391's own combined scope), a
`:thermal-shock-cycles` reading (water-quench cycles-to-failure, a
refractory-specific quality data point with no direct 2392 analog --
refractory products are graded by their resistance to rapid
temperature-change cracking, unlike dimensional-spec conformance for
building brick), and a `:cold-crushing-strength-mpa` reading, rather
than 2392's `:dimensional-deviation-percent`/`:defect-rate-percent`
pair.

`cloud-itonami-isic-2391` is also distinct from `cloud-itonami-isic-2393`
(Manufacture of other porcelain and ceramic products, a distinct
ceramics vertical with a different feedstock and firing profile) --
which this build does not depend on or wrap.

This vertical has NO pre-existing `kotoba-lang/refractorymfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`refractorymfg.registry` (equipment/batch verification, shipment-
weight recompute, product-type validation, thermal-shock-resistance
plausibility validation, cold-crushing-strength plausibility
validation) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2392`'s `claymfg.registry`,
itself modeled on `cloud-itonami-isic-2431`'s `foundrymfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:refractory-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "refractory-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created); the
`refractorymfg` namespace prefix is likewise grep-verified UNIQUE
fleet-wide (`gh search code "refractorymfg" --owner cloud-itonami`,
zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external refractory-products capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
refractory-products vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-weight /
product-type / thermal-shock-resistance / cold-crushing-strength
validation functions live as pure functions in
`refractorymfg.registry` and are re-verified independently by
`refractorymfg.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-2392`'s `claymfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of refractory-
products plant operations. It does NOT:
- Control the pressing-line press or kiln line equipment directly
- Make plant-safety or thermal-safety decisions (exclusive to the human plant supervisor)
- Actuate the pressing-line press or kiln line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority --
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: refractory-products manufacturing is a
safety-critical domain (kiln-firing thermal/burn hazard at
higher-than-building-material-brick service temperatures,
refractory-dust exposure, pressing-line pinch-point hazard, heavy
material handling). Safety-concern flagging NEVER auto-commits. All
safety concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (kiln-safety/thermal-hazard concern,
radiant-heat exposure, refractory-dust-hazard exposure, pressing-line-
equipment safety concern, crew fatigue) ALWAYS escalates, never
auto-commits. This is not a "low-stakes proposal" -- it is a
circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2392`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently verified/
registered before any action" HARD invariant applied to the two
distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight -- never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into eleven concrete checks
in `refractorymfg.governor`, matching `cloud-itonami-isic-2392`'s own
eleven -- this vertical's `:thermal-shock-cycles` plausibility check
replaces 2392's `:dimensional-deviation-percent` check, per Decision 1's
own field-vocabulary decision above) block proposals and cannot be
overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct pressing-line/kiln-line-equipment control or kiln-line actuation is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Refractory-products plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into eleven concrete governor checks) protect against scope creep into
unauthorized equipment operation or kiln-line actuation. Safety
concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and kiln-line operation remain
human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-2391`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, kiln-line-actuate-
  blocked, already-scheduled, invalid-product-type, invalid-
  thermal-shock-cycles, invalid-cold-crushing-strength).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
