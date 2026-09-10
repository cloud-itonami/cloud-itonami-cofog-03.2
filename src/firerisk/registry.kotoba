(ns firerisk.registry
  "Pure-function domain logic for the fire-risk-inspection back-office
  coordination actor -- site verification, the risk-finding verdict
  (a raw hazard-score sensor reading vs a site's own registered
  threshold), reading-plausibility validation, survey-type validation,
  and draft risk-finding/reinspection-schedule record construction.

  No pre-existing `kotoba-lang/firerisk`-style capability library
  exists for this vertical, so the domain logic lives here as pure
  functions, re-verified INDEPENDENTLY by `firerisk.governor` -- the
  same 'ground truth, not self-report' discipline every sibling
  actor's own registry establishes (e.g. `cleancert.registry`,
  `motomfg.registry`): never trust a proposal's own self-reported
  finding when the inputs needed to recompute it independently are
  already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to a real sensor/dispatch system. It builds the DRAFT record a
  fire-risk inspection contractor would keep (a risk finding, a
  scheduled reinspection window), not the act of actuating the
  inspection robot beyond passive sensing and never the act of
  dispatching a real fire department (see README `Robotics premise`
  and `firerisk.governor`'s permanent scope blocks).

  SCOPE: COFOG 03.2 (Fire-protection services) -- here illustrated as
  an independent fire-risk inspection contractor: a fire-risk
  inspection robot (thermal/smoke sensing, vegetation-density scan)
  performs the on-site survey; this actor coordinates the back-office
  record-keeping around that survey (inspection logging, risk-finding
  decisions, hazard escalation, reinspection scheduling). It never
  actuates the robot beyond passive sensing and never dispatches a
  real fire department -- only a human dispatcher does that, off this
  actor's escalation record.

  Two of the three closed `survey-type` values this actor recognizes
  map to real, verifiable inspection standards this actor does not
  implement but whose existence grounds why a contractor would
  classify a survey this way: NFPA 25 (Standard for the Inspection,
  Testing, and Maintenance of Water-Based Fire Protection Systems --
  sprinklers, standpipes, hydrants) for `:fire-protection-system`, and
  NFPA 1141 (Standard for Fire Protection Infrastructure for Land
  Development in Wildland, Rural, and Suburban Areas) for the
  vegetation/wildland-interface framing of `:vegetation`. This actor
  cites neither standard's specific numeric thresholds (a site's own
  registered `:hazard-threshold-score` is the ground truth a finding
  is judged against, never a value invented from the standard's
  text) -- the citation only grounds the survey-type taxonomy."
  )

;; ----------------------------- constants -----------------------------

(def valid-survey-types
  "The closed set of survey-type values a site inspection may declare.
  `:building` and `:vegetation` are this actor's two named premises
  (see README `Robotics premise`); `:fire-protection-system` is the
  NFPA-25-scoped fixed-system subtype. Anything else is a
  fabricated/unrecognized survey type -- the governor HARD-holds rather
  than let an invented category pass through."
  #{:building :vegetation :fire-protection-system})

(def hazard-score-min 0.0)

(def hazard-score-max
  "Physical ceiling for a normalized composite hazard-score sensor
  reading. A reading above this is implausible sensor/QC data, not a
  real scan result."
  100.0)

;; ----------------------------- site checks -----------------------------

(defn site-verified?
  "Ground-truth check: has `site`'s own record been marked verified
  (i.e. it has actually been inspected/commissioned, not merely
  referenced from an unverified request)? A pure predicate over the
  site's own permanent field -- no proposal inspection needed."
  [site]
  (true? (:verified? site)))

(defn site-registered?
  "Ground-truth check: does `site`'s own record carry a `:registered?`
  true flag (i.e. it is on file in the contractor's own site
  registry)? Surveying or scheduling work against a site that is not
  on file and registered is the exact scope violation this actor's
  HARD invariant ('site record must be independently verified/
  registered before any action') exists to block."
  [site]
  (true? (:registered? site)))

(defn site-ready?
  "Combined ground-truth gate: the site must be both `verified?` AND
  `registered?` before ANY fire-hazard survey or reinspection schedule
  may be proposed against it. Two independent facts on the site's own
  permanent record, neither inferred from the advisor's own
  rationale."
  [site]
  (and (site-verified? site) (site-registered? site)))

;; ----------------------------- risk finding verdict -----------------------------

(defn risk-finding
  "INDEPENDENT recompute of the :elevated/:normal finding from a raw
  `hazard-score` sensor reading against `site`'s own registered
  `:hazard-threshold-score` -- the arithmetic ground truth
  `firerisk.governor` cross-checks a proposal's own claimed `:finding`
  against. `:elevated` whenever the reading is missing, non-numeric, or
  at or above the site's own threshold (fail-safe: an unreadable
  sensor is never silently treated as :normal); `:normal` only for a
  plausible reading strictly below it. Never trusts a proposal's own
  claimed finding."
  [site hazard-score]
  (let [threshold (:hazard-threshold-score site)]
    (if (and (number? hazard-score) (number? threshold)
             (< (double hazard-score) (double threshold)))
      :normal
      :elevated)))

(defn hazard-score-valid?
  "Is `hazard-score` a physically plausible normalized composite
  sensor reading? Rejects nil, non-numbers, negative values, and
  values beyond `hazard-score-max` -- a fabricated or sensor-error
  reading, never let through as a real scan fact."
  [hazard-score]
  (and (number? hazard-score)
       (>= (double hazard-score) (double hazard-score-min))
       (<= (double hazard-score) (double hazard-score-max))))

(defn survey-type-valid?
  "Is `survey-type` one of the closed, known survey-type values
  (`:building`, `:vegetation`, `:fire-protection-system`)? nil is
  treated as invalid (an inspection record must declare a real survey
  type, not omit it silently)."
  [survey-type]
  (contains? valid-survey-types survey-type))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human fire-department/inspection-supervisor's act, not this
  actor's. This actor NEVER dispatches a real fire department or
  self-issues an official inspection clearance (see README `What this
  actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-risk-finding
  "Validate + construct the RISK-FINDING DRAFT -- a hazard-score-
  grounded elevated/normal finding against a verified, registered
  site. Pure function -- does not dispatch or sign anything; it builds
  the RECORD a fire-risk inspection contractor would keep.
  `firerisk.governor` independently re-verifies the site's own
  verified/registered ground truth and the finding's own arithmetic
  (`risk-finding`) before this is ever allowed to commit."
  [finding-id site-id finding sequence]
  (when-not (and finding-id (not= finding-id ""))
    (throw (ex-info "risk-finding: finding_id required" {})))
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info "risk-finding: site_id required" {})))
  (when-not (#{:elevated :normal} finding)
    (throw (ex-info "risk-finding: finding must be :elevated or :normal" {})))
  (when (< sequence 0)
    (throw (ex-info "risk-finding: sequence must be >= 0" {})))
  (let [finding-number (str "RF-" (zero-pad sequence 6))
        record {"record_id" finding-number
                "kind" "risk-finding-draft"
                "finding_id" finding-id
                "site_id" site-id
                "finding" (name finding)
                "immutable" true}]
    {"record" record "finding_number" finding-number
     "certificate" (unsigned-certificate "RiskFinding" finding-number finding-number)}))

(defn register-reinspection
  "Validate + construct the REINSPECTION-SCHEDULE DRAFT -- a proposed
  follow-up survey window against a site with an on-file ELEVATED risk
  finding. Pure function -- does not actuate the inspection robot or
  dispatch any real fire department; it builds the RECORD a fire-risk
  inspection contractor would keep. `firerisk.governor` independently
  re-verifies the site's own on-file elevated-finding ground truth
  before this is ever allowed to commit."
  [reinspection-id site-id sequence]
  (when-not (and reinspection-id (not= reinspection-id ""))
    (throw (ex-info "reinspection: reinspection_id required" {})))
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info "reinspection: site_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "reinspection: sequence must be >= 0" {})))
  (let [reinspection-number (str "REI-" (zero-pad sequence 6))
        record {"record_id" reinspection-number
                "kind" "reinspection-schedule-draft"
                "reinspection_id" reinspection-id
                "site_id" site-id
                "immutable" true}]
    {"record" record "reinspection_number" reinspection-number
     "certificate" (unsigned-certificate "ReinspectionSchedule" reinspection-number reinspection-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
