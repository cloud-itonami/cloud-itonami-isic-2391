# cloud-itonami-isic-2391: Manufacture of refractory products

Open Business Blueprint for **ISIC Rev.5 2391**: manufacture of refractory products — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **refractory-products plant operations**: production-batch data logging (product-type/weight/thermal-shock-resistance/cold-crushing-strength test results), pressing-line/kiln-line-equipment maintenance scheduling, safety-concern flagging, and outbound refractory-product shipment coordination.

This repository designs a forkable OSS business for refractory-
products plant operations: run by a qualified operator so a fire-
brick/lining plant keeps its own operating records instead of renting
a closed SaaS.

## Scope: refractory-products plant, not clay building materials or other ceramics

ISIC 2391 covers the **refractory-products plant** that batches raw
materials (chamotte, alumina, magnesia, silica), presses (uniaxial or
isostatic) or casts them into shape, dries, then fires in a kiln
(tunnel kiln or shuttle kiln, typically at higher service temperatures
than building-material brick) — producing fireclay brick, silica
brick, high-alumina brick, magnesia brick, insulating firebrick,
monolithic castable, ramming mix, or kiln furniture: fire brick, kiln
lining and furnace lining materials for high-temperature-service
industrial furnaces and kilns. This is distinct from
`cloud-itonami-isic-2392` (Manufacture of clay building materials),
which covers construction brick/tile rather than high-temperature-
service furnace/kiln linings, and from `cloud-itonami-isic-2393`
(Manufacture of other porcelain and ceramic products), a separate
ceramics vertical with a different feedstock and firing profile. This
actor's own hazard profile is centered on the kiln-firing line and
raw-material batching/pressing: kiln-fire/thermal-hazard (radiant heat
and burn risk at the firing zone, typically hotter service than
building-material brick firing), refractory-dust hazard (crystalline
silica/alumina/magnesia respirable dust exposure at batching and
pressing — a well-documented silicosis risk in this industry), and
pressing-line-equipment pinch-point/crush hazard (uniaxial/isostatic
hydraulic press).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — product-type/weight/thermal-shock-resistance/cold-crushing-strength data logging (administrative, not an operational decision)
- `:schedule-maintenance` — pressing-line/kiln-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a kiln-safety/thermal-hazard or refractory-dust-exposure concern (always escalates)
- `:coordinate-shipment` — outbound refractory-product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(kiln firing line, thermal/burn hazard, refractory-dust exposure,
pressing-line pinch-point hazard):

- Does NOT control the pressing-line press or kiln line equipment directly
- Does NOT make plant-safety or thermal-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the pressing-line press or kiln line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`refractorymfg.operation/build`, a langgraph-clj StateGraph):
1. **`refractorymfg.advisor`** (sealed intelligence node, `RefractoryAdvisor`): proposes decisions only, never commits
2. **`refractorymfg.governor`** (independent, `Refractory Plant Operations Governor`): validates against domain rules, re-derived from `refractorymfg.registry`'s pure functions and `refractorymfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct pressing-line/kiln-line-equipment control)
     - Directly actuating the pressing-line press or kiln line (`:actuate-kiln-pressing-line? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:thermal-shock-cycles` value on a production-batch patch
     - No physically implausible `:cold-crushing-strength-mpa` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`refractorymfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`refractorymfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
