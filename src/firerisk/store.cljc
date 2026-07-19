(ns firerisk.store
  "SSoT for the fire-risk-inspection back-office coordination actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite --
  the same seam every `cloud-itonami-*` actor in this fleet uses.

  Scope note: like its siblings (`cloud-itonami-isic-3091`'s own
  `motomfg.store`, `cloud-itonami-unspsc-73`'s own `cleancert.store`),
  this build ships a single `MemStore` backend only (atom of EDN) --
  the deterministic default for dev/tests/demo, no deps.

  Three kinds of entity live here:
    - `sites`             -- an inspectable site's own record (building,
                              vegetation parcel, fixed fire-protection
                              system). `:verified?`/`:registered?` track
                              whether it has actually been inspected/
                              commissioned and is on file;
                              `:hazard-threshold-score` is the
                              registered ceiling a survey's raw
                              hazard-score is judged against;
                              `:last-finding` (`:elevated`/`:normal`/
                              nil) is the site's own ground-truth
                              cumulative finding state.
    - `reinspections`      -- a scheduled follow-up-survey-window DRAFT
                              against a site (`firerisk.registry`'s
                              `register-reinspection`). Dedicated
                              `:scheduled?` double-schedule guard (never
                              a `:status` value -- the same discipline
                              every prior governor's guards establish).
    - `inspection-records` -- a logged completed-inspection record
                              (survey-type/notes), keyed by id.

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which inspection was logged, which
  risk finding was decided against a verified/registered site and at
  what independently-recomputed hazard-score verdict, which
  reinspection was scheduled against a site with an on-file elevated
  finding, which hazard was escalated' is always a query over an
  immutable log -- the audit trail a municipality or downstream client
  trusting this coordinator needs."
  (:require [firerisk.registry :as registry]))

(defprotocol Store
  (site [s id])
  (all-sites [s])
  (inspection-record [s id])
  (risk-finding [s site-id] "the site's own latest committed risk-finding record, or nil")
  (reinspection [s id])
  (hazard-escalations [s] "the append-only hazard-escalation log")
  (ledger [s])
  (finding-history [s] "the append-only risk-finding history (firerisk.registry drafts)")
  (reinspection-history [s] "the append-only reinspection-schedule history (firerisk.registry drafts)")
  (next-finding-sequence [s] "next finding-number sequence")
  (next-reinspection-sequence [s] "next reinspection-number sequence")
  (reinspection-already-scheduled? [s reinspection-id] "has this reinspection window already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-sites [s sites] "replace/seed the site directory (map id->site)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-sites []
  {"site-001" {:id "site-001" :kind :building
               :verified? true :registered? true
               :hazard-threshold-score 70.0
               :last-finding nil}
   "site-002" {:id "site-002" :kind :vegetation
               :verified? true :registered? true
               :hazard-threshold-score 40.0
               :last-finding :elevated}
   "site-003" {:id "site-003" :kind :fire-protection-system
               :verified? false :registered? false
               :hazard-threshold-score 60.0
               :last-finding nil}})

;; ----------------------------- shared commit logic -----------------------------

(defn- decide-risk-finding!
  "Backend-agnostic `:risk-finding/decide` -- drafts the risk-finding
  record via `firerisk.registry` and returns {:result .. :patch ..}
  for the caller to persist."
  [s finding-id site-id finding]
  (let [seq-n (next-finding-sequence s)
        result (registry/register-risk-finding finding-id site-id finding seq-n)]
    {:result result
     :patch {:finding finding
             :finding-number (get result "finding_number")}}))

(defn- schedule-reinspection!
  "Backend-agnostic `:reinspection/schedule` -- drafts the
  reinspection-schedule record via `firerisk.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s reinspection-id site-id]
  (let [seq-n (next-reinspection-sequence s)
        result (registry/register-reinspection reinspection-id site-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :reinspection-number (get result "reinspection_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ id] (get-in @a [:sites id]))
  (all-sites [_] (sort-by :id (vals (:sites @a))))
  (inspection-record [_ id] (get-in @a [:inspection-records id]))
  (risk-finding [_ site-id] (get-in @a [:finding-by-site site-id]))
  (reinspection [_ id] (get-in @a [:reinspections id]))
  (hazard-escalations [_] (:hazard-escalations @a))
  (ledger [_] (:ledger @a))
  (finding-history [_] (:finding-history @a))
  (reinspection-history [_] (:reinspection-history @a))
  (next-finding-sequence [_] (:finding-sequence @a 0))
  (next-reinspection-sequence [_] (:reinspection-sequence @a 0))
  (reinspection-already-scheduled? [_ reinspection-id]
    (boolean (get-in @a [:reinspections reinspection-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :inspection-record/upsert)
      (swap! a update-in [:inspection-records (first path)] merge (assoc value :id (first path)))

      (= effect :risk-finding/decide)
      (let [finding-id (first path)
            site-id (:site-id value)
            finding (:finding value)
            {:keys [result patch]} (decide-risk-finding! s finding-id site-id finding)]
        (swap! a (fn [state]
                   (-> state
                       (update :finding-sequence (fnil inc 0))
                       (update :finding-history registry/append result)
                       (assoc-in [:finding-by-site site-id] (merge value patch))
                       (assoc-in [:sites site-id :last-finding] finding))))
        result)

      (= effect :hazard/escalate)
      (let [escalation-id (first path)
            escalation (assoc value :id escalation-id)]
        (swap! a update :hazard-escalations conj escalation)
        escalation)

      (= effect :reinspection/schedule)
      (let [reinspection-id (first path)
            site-id (:site-id value)
            {:keys [result patch]} (schedule-reinspection! s reinspection-id site-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :reinspection-sequence (fnil inc 0))
                       (update-in [:reinspections reinspection-id] merge (assoc value :id reinspection-id) patch)
                       (update :reinspection-history registry/append result))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above.
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:sites {} :inspection-records {} :reinspections {}
                      :records {} :hazard-escalations []
                      :finding-by-site {}
                      :ledger [] :finding-sequence 0 :finding-history []
                      :reinspection-sequence 0 :reinspection-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained site set -- one
  verified+registered building never yet surveyed (clean
  fire-hazard-survey happy path, hazard-score below its own
  threshold), one verified+registered vegetation parcel with an
  on-file ELEVATED finding (schedule-reinspection happy path), one
  UNVERIFIED/unregistered fixed fire-protection-system site (blocks
  any survey or reinspection scheduling proposed against it) -- so the
  actor + demo + tests run offline. Returns `s` (thread-friendly with
  `->`)."
  [s]
  (with-sites s (sample-sites))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
