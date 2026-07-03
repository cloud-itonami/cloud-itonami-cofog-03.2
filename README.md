# cloud-itonami-cofog-03.2

Open COFOG Blueprint for **COFOG 03.2**: Fire-protection services.

This repository designs a forkable OSS business for an independent
fire-risk inspection contractor: a fire-risk inspection robot performs
building/vegetation fire-hazard surveys under a governor-gated actor, so a
municipality's fire department (or its contracted inspector) keeps
auditable inspection records instead of renting a closed field-service SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a fire-risk inspection robot
(thermal/smoke sensing, vegetation-density scan) performs the on-site
survey under an actor that proposes risk findings and an independent
**Fire-Risk Governor** that gates them. The governor never dispatches
hardware itself; `:high`/`:safety-critical` findings (e.g. an active hazard
requiring immediate fire-department dispatch) require human sign-off.

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

No automated finding can dispatch a robot action the governor refuses,
suppress an inspection record, or downgrade a hazard finding without
governor approval and audit evidence.

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
