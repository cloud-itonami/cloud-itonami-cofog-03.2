# cloud-itonami-cofog-03.2

Open COFOG Blueprint for **COFOG 03.2**: Fire-protection services.

This repository designs a forkable OSS business for an independent
fire-risk inspection contractor: a fire-risk inspection robot performs
building/vegetation fire-hazard surveys under a governor-gated actor, so a
municipality's fire department (or its contracted inspector) keeps
auditable inspection records instead of renting a closed field-service SaaS.

**Status: design blueprint, no code implemented yet.** This repository
has zero files under `src/` and no `test/` directory — the Inspection
Advisor and Fire-Risk Governor described below do not exist in code.
It is not (yet) a governed Advisor⊣Governor actuation actor; the Core
Contract section specifies what that pipeline is intended to enforce
once built, not current behavior. See
[`cloud-itonami-isco-1324`](https://github.com/cloud-itonami/cloud-itonami-isco-1324)
for this fleet's minimal implemented reference (`actor`/`advisor`/
`governor`/`store`), and the `cloud-itonami-assoc-*` /
`cloud-itonami-municipality-*` / `cloud-itonami-lei-*` repos for this
fleet's honest not-an-actuation-actor disclaimer pattern.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a fire-risk inspection robot
(thermal/smoke sensing, vegetation-density scan) performs the on-site
survey under an actor that proposes risk findings and an independent
**Fire-Risk Governor** that gates them. The governor never dispatches
hardware itself; `:high`/`:safety-critical` findings (e.g. an active hazard
requiring immediate fire-department dispatch) require human sign-off.

## Core Contract (design intent — not yet implemented)

```text
site survey request + prior inspection history
        |
        v
Inspection Advisor -> Fire-Risk Governor -> report, or human dispatch
        |
        v
robot survey actions (gated) + inspection record + audit ledger
```

**No code exists yet in this repo** — no `src/`, no `test/`, only this
design document plus `blueprint.edn` and `docs/`. Once built, no
automated finding will be able to dispatch a robot action the governor
refuses, suppress an inspection record, or downgrade a hazard finding
without governor approval and audit evidence — but none of that is
enforced today.

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
