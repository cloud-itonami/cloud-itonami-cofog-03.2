# cloud-itonami-cofog-03.2

Open COFOG Blueprint (implemented actor) for **COFOG 03.2**:
Fire-protection services.

This repository publishes a forkable OSS business for an independent
fire-risk inspection contractor: a fire-risk inspection robot performs
building/vegetation fire-hazard surveys under a governor-gated actor, so a
municipality's fire department (or its contracted inspector) keeps
auditable inspection records instead of renting a closed field-service SaaS.

**Maturity: `:implemented`** — InspectionAdvisor ⊣ Fire-Risk Governor
as a langgraph-clj StateGraph (`intake → advise → govern → decide →
commit/hold`, human-approval interrupt), modeled on
`cloud-itonami-isic-3091`'s motorcycle-plant-operations actor (the
closest robotics-gated, propose-only-coordination sibling shape). All
source `.cljc` (portable to JVM / ClojureScript / GraalVM), no JVM-only
interop. 81 tests / 210 assertions green, `clj-kondo` 0 errors / 0
warnings.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a fire-risk inspection robot
(thermal/smoke sensing, vegetation-density scan) performs the on-site
survey under an actor that proposes risk findings and an independent
**Fire-Risk Governor** that gates them. The governor never dispatches
hardware itself; `:safety-critical` findings (e.g. an active hazard
requiring immediate fire-department dispatch) always require human
sign-off, regardless of the advisor's own confidence.

## What this actor does

Proposes **fire-risk-inspection back-office coordination**, not
equipment operation or dispatch:
- `:log-inspection` — completed on-site survey data logging
  (survey-type/notes against a registered site; administrative, not an
  operational decision)
- `:fire-hazard-survey` — an elevated/normal finding drafted from a
  robot's raw composite hazard-score sensor reading against the site's
  own registered threshold
- `:escalate-hazard` — surface an active hazard requiring immediate
  fire-department dispatch (always escalates)
- `:schedule-reinspection` — propose a follow-up survey window against
  a site with an on-file ELEVATED finding

## What this actor does NOT do

- Does NOT actuate the inspection robot beyond passive sensing, and
  does NOT trigger any suppression/alarm/dispatch hardware directly —
  the robot senses, this actor only logs/finds/schedules
- Does NOT dispatch a real fire department or self-report that a
  dispatch happened (every escalation this actor produces is an
  unsigned DRAFT; a human dispatcher's own act is what actually
  dispatches)
- Does NOT let a proposal's own self-reported finding stand
  uncontested — the governor independently recomputes it from the raw
  hazard-score reading every time
- ONLY proposes/coordinates back-office records; all actuation and
  dispatch requires explicit human authority

## Core Contract

```text
site survey request + prior inspection history
        |
        v
Inspection Advisor -> Fire-Risk Governor -> report, or human dispatch
        |
        v
robot survey actions (gated) + inspection record + audit ledger
```

Implemented faithfully: no automated finding can dispatch a robot
action the governor refuses, suppress an inspection record, or
downgrade a hazard finding without governor approval and audit
evidence.

## Implementation

Portable `.cljc` namespaces under `src/firerisk/`:

- `registry` — pure domain logic: site verified/registered ground
  truth, the independent elevated/normal finding verdict (hazard-score
  vs a site's own registered threshold), reading-plausibility and
  survey-type validation, draft finding/reinspection-schedule record
  construction. Survey-type taxonomy is grounded in real NFPA
  standards (NFPA 25 for fixed water-based fire-protection-system
  inspection; NFPA 1141 for wildland/rural/suburban vegetation-
  interface fire-protection infrastructure) — cited for the
  classification only, never for a fabricated numeric threshold.
- `store` — SSoT behind a `Store` protocol (`MemStore`); sites,
  inspection records, risk-finding history, reinspection schedules,
  hazard escalations and the audit ledger all live here.
- `advisor` — the contained intelligence node (`mock-advisor` default,
  `llm-advisor` swap-in); returns proposals only, grounded only in
  store facts.
- `governor` — the independent Fire-Risk Governor (12 HARD + 1 SOFT
  check).
- `phase` — 0→3 staged rollout; only `:log-inspection` is ever
  auto-eligible, and only at phase 3.
- `operation` — the langgraph-clj StateGraph (1 run = 1 coordination
  request); `sim` drives the offline demo.

`kbb -M:dev:test` (81 tests, 210 assertions) and `kbb -M:lint`
(clj-kondo, 0 errors). `kbb -M:dev:run` drives the demo end to end,
including every HARD-hold scenario.

## Capability layer

Resolves via [`kotoba-lang/cofog`](https://github.com/kotoba-lang/cofog)
(COFOG `03.2`). Required capabilities:

- :robotics
- :telemetry
- :forms
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
