(ns firerisk.phase
  "Phase 0->3 staged rollout for the fire-risk-inspection back-office
  coordination actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- inspection logging allowed, every
                                    write needs human approval.
    Phase 2  assisted-report    -- adds hazard escalation, still
                                    approval.
    Phase 3  supervised-auto    -- adds risk-finding decisions and
                                    reinspection scheduling (still
                                    always approval -- see below);
                                    governor-clean, high-confidence
                                    `:log-inspection` (no physical/
                                    financial risk) may auto-commit.

  `:fire-hazard-survey` and `:schedule-reinspection` are deliberately
  ABSENT from every phase's `:auto` set, including phase 3 -- a
  permanent structural fact, not a rollout milestone still to come. A
  risk finding is a real liability-bearing claim, and scheduling a
  reinspection means the robot actually surveys the site again; both
  are always a human inspection supervisor's call.
  `firerisk.governor`'s `equipment-actuate-blocked-violations`
  HARD-blocks actuate attempts unconditionally, and the confidence/
  high-stakes gate independently never lets `:escalate-hazard`
  auto-commit either -- multiple independent layers agree on where this
  actor's authority ends. Like every prior sibling's phase-3 `:auto`
  set, this domain has only ONE member (`:log-inspection`) -- no
  separate no-risk lifecycle distinct from ordinary record logging.")

(def write-ops
  #{:log-inspection :fire-hazard-survey
    :escalate-hazard :schedule-reinspection})

;; NOTE the invariant: `:fire-hazard-survey` and
;; `:schedule-reinspection` are members of `write-ops` (governor-gated
;; like any write) but are NEVER members of any phase's `:auto` set
;; below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                     :auto #{}}
   1 {:label "assisted-intake"  :writes #{:log-inspection}                      :auto #{}}
   2 {:label "assisted-report"  :writes #{:log-inspection :escalate-hazard}     :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:log-inspection}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:fire-hazard-survey`/`:schedule-reinspection` are never
    auto-eligible at any phase, so they always escalate once the
    governor clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Fire-Risk Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
