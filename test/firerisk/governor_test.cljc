(ns firerisk.governor-test
  "Direct unit tests against `firerisk.governor/check` with hand-crafted
  proposals -- including the finding-mismatch case a well-behaved
  deterministic advisor can never itself produce (see
  `firerisk.advisor/fire-hazard-survey`, which always recomputes its
  own :finding honestly). This governor's INDEPENDENT recompute exists
  precisely for a compromised/hallucinating advisor or the LLM-advisor
  path -- exercised here directly, the same discipline
  `firerisk.governor-contract-test` applies at the full-graph level for
  every other rule."
  (:require [clojure.test :refer [deftest is testing]]
            [firerisk.store :as store]
            [firerisk.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/with-sites st {"site-001" {:id "site-001" :kind :building
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
    st))

(def ^:private req {:op :fire-hazard-survey :effect :propose :subject "find-x"})

(defn- survey [site-id hazard-score finding]
  {:effect :risk-finding/decide
   :value {:site-id site-id :hazard-score hazard-score :finding finding}
   :confidence 0.9 :stake nil})

(deftest ok-when-finding-matches-independent-recompute
  (let [st (fresh-store)
        v (governor/check req {} (survey "site-001" 20.0 :normal) st)]
    (is (not (:hard? v)))))

(deftest hard-on-finding-mismatch-normal-when-truth-is-elevated
  (testing "an advisor rubber-stamping :normal when the sensor reading itself would be elevated -- HARD, never let a self-report stand against contradicting evidence"
    (let [st (fresh-store)
          v (governor/check req {} (survey "site-001" 90.0 :normal) st)]
      (is (:hard? v))
      (is (some #(= :finding-mismatch (:rule %)) (:violations v))))))

(deftest hard-on-finding-mismatch-elevated-when-truth-is-normal
  (let [st (fresh-store)
        v (governor/check req {} (survey "site-001" 5.0 :elevated) st)]
    (is (:hard? v))
    (is (some #(= :finding-mismatch (:rule %)) (:violations v)))))

(deftest hard-on-unverified-site-for-fire-hazard-survey
  (let [st (fresh-store)
        v (governor/check req {} (survey "site-003" 5.0 :normal) st)]
    (is (:hard? v))
    (is (some #(= :site-not-verified (:rule %)) (:violations v)))))

(deftest hard-on-invalid-hazard-score
  (let [st (fresh-store)
        v (governor/check req {} (survey "site-001" -5.0 :elevated) st)]
    (is (:hard? v))
    (is (some #(= :invalid-hazard-score (:rule %)) (:violations v)))))

(deftest hard-on-non-propose-effect
  (let [st (fresh-store)
        v (governor/check {:op :fire-hazard-survey :effect :direct-write :subject "x"} {}
                          (survey "site-001" 20.0 :normal) st)]
    (is (:hard? v))
    (is (some #(= :not-propose-effect (:rule %)) (:violations v)))))

(deftest hard-on-unknown-op
  (let [st (fresh-store)
        v (governor/check {:op :trigger-alarm-directly :effect :propose :subject "x"} {}
                          {:effect :inspection-record/upsert :confidence 0.9} st)]
    (is (:hard? v))
    (is (some #(= :unknown-op (:rule %)) (:violations v)))))

(deftest hard-on-proposal-effect-outside-allowlist
  (let [st (fresh-store)
        v (governor/check req {} (assoc (survey "site-001" 20.0 :normal) :effect :sprinkler/actuate) st)]
    (is (:hard? v))
    (is (some #(= :equipment-control-blocked (:rule %)) (:violations v)))))

(deftest hard-on-actuate-equipment-permanent
  (let [st (fresh-store)
        v (governor/check {:op :schedule-reinspection :effect :propose :subject "rei-x"} {}
                          {:effect :reinspection/schedule :confidence 0.9
                           :value {:site-id "site-002" :actuate-equipment? true}} st)]
    (is (:hard? v))
    (is (some #(= :equipment-actuate-blocked (:rule %)) (:violations v)))))

(deftest hard-on-dispatch-authority-side-channel
  (let [st (fresh-store)
        v (governor/check {:op :log-inspection :effect :propose :subject "insp-x"} {}
                          {:effect :inspection-record/upsert :confidence 0.9
                           :value {:site-id "site-001" :dispatched? true}} st)]
    (is (:hard? v))
    (is (some #(= :dispatch-authority-blocked (:rule %)) (:violations v)))))

(deftest hazard-escalate-effect-with-dispatched-flag-is-not-a-side-channel
  (testing "the gated :hazard/escalate effect itself is exempt from the side-channel block"
    (let [st (fresh-store)
          v (governor/check {:op :escalate-hazard :effect :propose :subject "haz-x"} {}
                            {:effect :hazard/escalate :confidence 0.9 :stake :coordination/hazard-escalation
                             :value {:site-id "site-001" :dispatched? true}} st)]
      (is (not (some #(= :dispatch-authority-blocked (:rule %)) (:violations v)))))))

(deftest hard-on-no-elevated-finding-for-reinspection
  (let [st (fresh-store)
        v (governor/check {:op :schedule-reinspection :effect :propose :subject "rei-x"} {}
                          {:effect :reinspection/schedule :confidence 0.9
                           :value {:site-id "site-001" :actuate-equipment? false}} st)]
    (is (:hard? v))
    (is (some #(= :no-elevated-finding (:rule %)) (:violations v)))))

(deftest ok-reinspection-against-elevated-site
  (let [st (fresh-store)
        v (governor/check {:op :schedule-reinspection :effect :propose :subject "rei-x"} {}
                          {:effect :reinspection/schedule :confidence 0.9
                           :value {:site-id "site-002" :actuate-equipment? false}} st)]
    (is (not (:hard? v)))))

(deftest hard-on-invalid-survey-type
  (let [st (fresh-store)
        v (governor/check {:op :log-inspection :effect :propose :subject "insp-x"} {}
                          {:effect :inspection-record/upsert :confidence 0.9
                           :value {:site-id "site-001" :survey-type :aerial-drone-strike}} st)]
    (is (:hard? v))
    (is (some #(= :invalid-survey-type (:rule %)) (:violations v)))))

(deftest escalates-hazard-regardless-of-confidence
  (let [st (fresh-store)
        v (governor/check {:op :escalate-hazard :effect :propose :subject "haz-x"} {}
                          {:effect :hazard/escalate :confidence 0.99
                           :stake :coordination/hazard-escalation
                           :value {:site-id "site-001" :hazard-type :active-fire-hazard}} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (survey "site-001" 20.0 :normal) :confidence 0.2) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
