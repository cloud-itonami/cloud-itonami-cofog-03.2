(ns firerisk.governor
  "Fire-Risk Governor -- the independent compliance layer that earns
  the Inspection Advisor the right to commit. The advisor has no
  notion of whether a site it wants to survey or schedule a
  reinspection against has actually been inspected/registered, whether
  its own claimed elevated/normal finding actually matches what the
  raw hazard-score sensor reading and the site's own registered
  threshold say, whether a proposal secretly tries to ACTUATE the
  inspection robot beyond passive sensing or trigger a real emergency
  dispatch/alarm system directly, whether a proposal secretly tries to
  self-report a fire-department dispatch that never happened (an
  authority this actor never holds), or whether a reinspection is
  being scheduled (and billed) against a site with no on-file elevated
  finding to justify it -- so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:fire-risk-governor` (see
  blueprint.edn).

  `:escalate-hazard` -- an active hazard requiring immediate
  fire-department dispatch -- is the one always-escalate op this
  governor treats as high-stakes regardless of confidence; the README
  names this exact scenario. This governor does not itself dispatch
  anything (see `firerisk.registry`'s ns docstring); it only ensures a
  human dispatcher, never an LLM confidence score, is the one who acts
  on it.

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to
                                       coordinate? Anything else -- HARD
                                       hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:sprinkler/actuate`
                                       or `:alarm/trigger`) is the
                                       'direct hardware control' scope
                                       violation this actor must NEVER
                                       perform -- HARD, PERMANENT,
                                       unconditional.
    4. Equipment-actuate blocked   -- does any proposal's own `:value`
                                       declare `:actuate-equipment?
                                       true`? Directly actuating the
                                       inspection robot beyond passive
                                       sensing, or a suppression/alarm/
                                       dispatch system, is this actor's
                                       permanent scope boundary (see
                                       README `Robotics premise`) --
                                       HARD, PERMANENT, unconditional.
                                       No phase and no human approval
                                       can ever override this (see
                                       `firerisk.phase`: no op is ever
                                       a member of any phase's `:auto`
                                       set for this reason either --
                                       two independent layers agree).
    5. Dispatch authority blocked  -- ANY proposal (any op) whose own
                                       `:value`/`:patch` declares
                                       `:dispatched? true` OUTSIDE the
                                       gated `:escalate-hazard` path is
                                       attempting to self-report a
                                       fire-department dispatch through
                                       a side channel (e.g. slipped
                                       into a routine `:log-inspection`
                                       patch) -- an authority this
                                       actor never holds regardless of
                                       which op carries the claim --
                                       HARD, PERMANENT, unconditional.
    6. Site not verified/
       registered                  -- for `:fire-hazard-survey` and
                                       `:schedule-reinspection`,
                                       INDEPENDENTLY verify the
                                       referenced site's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`firerisk.registry/site-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status.
    7. Finding mismatch            -- for `:fire-hazard-survey`,
                                       INDEPENDENTLY recompute the
                                       elevated/normal finding from the
                                       proposal's own `:hazard-score`
                                       reading against the site's own
                                       registered `:hazard-threshold-
                                       score` (`firerisk.registry/
                                       risk-finding`) and compare it
                                       against the proposal's own
                                       claimed `:finding` -- a mismatch
                                       (e.g. an advisor rubber-stamping
                                       `:normal` when the sensor
                                       reading itself would be
                                       elevated) is HARD-held: never
                                       let a self-reported finding
                                       stand against contradicting
                                       sensor evidence.
    8. Invalid hazard score        -- for `:fire-hazard-survey`, if
                                       `:hazard-score` is not a
                                       physically plausible reading
                                       (`firerisk.registry/hazard-
                                       score-valid?`), the proposal is
                                       rejected rather than let
                                       fabricated/sensor-error data
                                       drive a finding.
    9. No elevated finding on
       file                        -- for `:schedule-reinspection`,
                                       INDEPENDENTLY verify the site's
                                       own `:last-finding` on file is
                                       `:elevated` -- never trust the
                                       advisor's own claim that a
                                       reinspection is warranted.
                                       Scheduling (and billing) a
                                       reinspection against a site with
                                       no on-file elevated finding is a
                                       fabrication this governor
                                       rejects.
   10. Already scheduled           -- for `:schedule-reinspection`,
                                       refuses to schedule the SAME
                                       reinspection record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
   11. Invalid survey type         -- for `:log-inspection`, if the
                                       patch declares a `:survey-type`
                                       outside the closed known set
                                       (`firerisk.registry/survey-
                                       type-valid?`), the inspection
                                       record is rejected rather than
                                       let a fabricated category
                                       through.
   12. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/hazard-
                                       escalation`, ALWAYS set for
                                       `:escalate-hazard`) -- escalate
                                       to a human fire-department
                                       dispatcher/inspection
                                       supervisor. SOFT: the human may
                                       approve."
  (:require [firerisk.registry :as registry]
            [firerisk.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-inspection :fire-hazard-survey
    :escalate-hazard :schedule-reinspection})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct
  hardware-control effect."
  #{:inspection-record/upsert :risk-finding/decide
    :hazard/escalate :reinspection/schedule})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean. An
  active hazard requiring immediate fire-department dispatch is the
  one op in this domain that always demands human eyes regardless of
  confidence."
  #{:coordination/hazard-escalation})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- equipment-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct hardware control, a fabricated actuation
  effect) is this actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :equipment-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は検査ロボットやサプレッション/警報系統の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- equipment-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a proposal whose own `:value`
  declares `:actuate-equipment? true` is attempting to directly actuate
  the inspection robot beyond passive sensing, or a suppression/alarm/
  dispatch system -- this actor may only ever propose/schedule a DRAFT
  (an inspection log, a risk finding, a reinspection window), never
  actuate hardware directly. No override, ever."
  [proposal]
  (when (true? (:actuate-equipment? (:value proposal)))
    [{:rule :equipment-actuate-blocked
      :detail "検査ロボットや警報・消火系統の直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- dispatch-authority-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal (any op) whose own
  `:value`/`:patch` declares `:dispatched? true` OUTSIDE the gated
  `:hazard/escalate` path is attempting to self-report a
  fire-department dispatch through a side channel -- an authority this
  actor never holds. No phase and no human approval can ever override
  this."
  [proposal]
  (let [payload (or (:value proposal) (:patch proposal))]
    (when (and (true? (:dispatched? payload))
               (not= :hazard/escalate (:effect proposal)))
      [{:rule :dispatch-authority-blocked
        :detail "消防出動(dispatch)の自己申告は恒久的に禁止 -- 出動は :escalate-hazard 経路でのみ、かつ人間確認後にのみ成立"}])))

(defn- site-not-verified-violations
  "For `:fire-hazard-survey` and `:schedule-reinspection`,
  INDEPENDENTLY verify the referenced site exists and is both
  `:verified?` AND `:registered?` -- never trust the advisor's own
  report."
  [{:keys [op]} proposal st]
  (when (contains? #{:fire-hazard-survey :schedule-reinspection} op)
    (let [site-id (:site-id (:value proposal))
          st-site (and site-id (store/site st site-id))]
      (when-not (and st-site (registry/site-ready? st-site))
        [{:rule :site-not-verified
          :detail (str site-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みサイト記録が無い状態での提案")}]))))

(defn- finding-mismatch-violations
  "For `:fire-hazard-survey`, INDEPENDENTLY recompute the
  elevated/normal finding from the proposal's own `:hazard-score`
  reading against the site's own registered threshold, and compare it
  against the proposal's own claimed `:finding` -- never let a
  self-reported finding stand against contradicting sensor evidence."
  [{:keys [op]} proposal st]
  (when (= op :fire-hazard-survey)
    (let [{:keys [site-id hazard-score finding]} (:value proposal)
          st-site (and site-id (store/site st site-id))]
      (when (and st-site finding)
        (let [truth (registry/risk-finding st-site hazard-score)]
          (when-not (= truth finding)
            [{:rule :finding-mismatch
              :detail (str site-id " のhazard-score(" hazard-score
                           ")から独立算出した判定は " truth
                           " -- 提案の自己申告判定 " finding " と不一致")}]))))))

(defn- invalid-hazard-score-violations
  "For `:fire-hazard-survey`, if `:hazard-score` is not a physically
  plausible reading, reject rather than let fabricated/sensor-error
  data drive a finding."
  [{:keys [op]} proposal]
  (when (= op :fire-hazard-survey)
    (let [hazard-score (:hazard-score (:value proposal))]
      (when-not (registry/hazard-score-valid? hazard-score)
        [{:rule :invalid-hazard-score
          :detail (str (pr-str hazard-score) " は物理的に妥当な hazard-score ではない")}]))))

(defn- no-elevated-finding-violations
  "For `:schedule-reinspection`, INDEPENDENTLY verify the site's own
  `:last-finding` on file is `:elevated` -- never trust the advisor's
  own claim that a reinspection is warranted. Scheduling (and billing)
  a reinspection with no on-file elevated finding is a fabrication
  this governor rejects."
  [{:keys [op]} proposal st]
  (when (= op :schedule-reinspection)
    (let [site-id (:site-id (:value proposal))
          st-site (and site-id (store/site st site-id))]
      (when (and st-site (not= :elevated (:last-finding st-site)))
        [{:rule :no-elevated-finding
          :detail (str site-id " に未合格(:elevated)の登録済み判定が無い -- 再調査の予定提案には elevated 判定の記録が必要")}]))))

(defn- already-scheduled-violations
  "For `:schedule-reinspection`, refuses to schedule the SAME
  reinspection record twice, off a dedicated `:scheduled?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-reinspection)
    (when (store/reinspection-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- invalid-survey-type-violations
  "For `:log-inspection`, if the patch declares a `:survey-type`
  outside the closed known set, reject rather than let a fabricated
  category through."
  [{:keys [op]} proposal]
  (when (= op :log-inspection)
    (let [survey-type (:survey-type (:value proposal))]
      (when (and (some? survey-type) (not (registry/survey-type-valid? survey-type)))
        [{:rule :invalid-survey-type
          :detail (str survey-type " は既知の survey-type 値ではない")}]))))

(defn check
  "Censors an Inspection Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (equipment-control-blocked-violations proposal)
                           (equipment-actuate-blocked-violations proposal)
                           (dispatch-authority-blocked-violations proposal)
                           (site-not-verified-violations request proposal st)
                           (finding-mismatch-violations request proposal st)
                           (invalid-hazard-score-violations request proposal)
                           (no-elevated-finding-violations request proposal st)
                           (already-scheduled-violations request st)
                           (invalid-survey-type-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
