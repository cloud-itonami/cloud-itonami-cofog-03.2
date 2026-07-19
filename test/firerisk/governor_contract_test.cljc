(ns firerisk.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT actuate the inspection robot beyond
  passive sensing... does NOT dispatch a real fire department')
  implemented faithfully through the FULL compiled graph. The single
  invariant under test:

    InspectionAdvisor never decides a risk finding, escalates a
    hazard, or schedules a reinspection the Fire-Risk Governor would
    reject; `:fire-hazard-survey`/`:escalate-hazard`/
    `:schedule-reinspection` NEVER auto-commit at any phase;
    `:log-inspection` (no physical/financial risk) MAY auto-commit
    when clean; and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [firerisk.store :as store]
            [firerisk.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :inspection-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-inspection-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-inspection :effect :propose :subject "insp-001"
                   :patch {:site-id "site-001" :survey-type :building}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :building (:survey-type (store/inspection-record db "insp-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest fire-hazard-survey-always-needs-approval
  (testing "risk-finding decisions are never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :fire-hazard-survey :effect :propose :subject "find-1"
                     :value {:site-id "site-001" :hazard-score 20.0}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :normal (:last-finding (store/site db "site-001"))))
        (is (= 1 (count (store/finding-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-inspection :effect :direct-write :subject "insp-001"
                     :patch {:site-id "site-001"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :trigger-alarm-directly :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest site-not-verified-is-held-and-unoverridable
  (testing "surveying an unverified/unregistered site -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :fire-hazard-survey :effect :propose :subject "find-2"
                     :value {:site-id "site-003" :hazard-score 10.0}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:site-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/finding-history db))))))

(deftest invalid-hazard-score-is-held
  (testing "an implausible hazard-score sensor reading -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :fire-hazard-survey :effect :propose :subject "find-3"
                     :value {:site-id "site-001" :hazard-score 999999.0}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:invalid-hazard-score} (-> (store/ledger db) last :basis)))
      (is (empty? (store/finding-history db))))))

(deftest no-elevated-finding-is-held-and-unoverridable
  (testing "scheduling a reinspection with no on-file elevated finding -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :schedule-reinspection :effect :propose :subject "rei-1"
                     :value {:site-id "site-001" :scheduled-date "2026-08-01"
                             :actuate-equipment? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:no-elevated-finding} (-> (store/ledger db) last :basis)))
      (is (empty? (store/reinspection-history db))))))

(deftest equipment-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-equipment? true -> HOLD, PERMANENT, never reaches request-approval even though the site is verified, registered and has an elevated finding on file"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :schedule-reinspection :effect :propose :subject "rei-2"
                     :value {:site-id "site-002" :scheduled-date "2026-09-01"
                             :actuate-equipment? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:equipment-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/reinspection-history db))))))

(deftest dispatch-authority-is-held-and-permanently-blocked
  (testing "a proposal that sets :dispatched? true outside the gated hazard-escalation path -> HOLD, PERMANENT, never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t8b"
                    {:op :log-inspection :effect :propose :subject "insp-002"
                     :patch {:site-id "site-001" :dispatched? true}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:dispatch-authority-blocked} (-> (store/ledger db) last :basis)))
      (is (not (true? (:dispatched? (store/inspection-record db "insp-002"))))
          "fabricated self-reported dispatch never lands in the SSoT"))))

(deftest schedule-reinspection-double-schedule-is-held
  (testing "scheduling the SAME reinspection record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9a" {:op :schedule-reinspection :effect :propose :subject "rei-3"
                                  :value {:site-id "site-002" :scheduled-date "2026-08-01"
                                          :actuate-equipment? false}} coordinator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :schedule-reinspection :effect :propose :subject "rei-3"
                                   :value {:site-id "site-002" :scheduled-date "2026-08-01"
                                           :actuate-equipment? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/reinspection-history db))) "still only the one earlier schedule"))))

(deftest invalid-survey-type-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :log-inspection :effect :propose :subject "insp-003"
                                  :patch {:site-id "site-001" :survey-type :aerial-drone-strike}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-survey-type} (-> (store/ledger db) last :basis)))
    (is (not= :aerial-drone-strike (:survey-type (store/inspection-record db "insp-003"))) "fabricated survey-type never lands in the SSoT")))

(deftest hazard-escalation-always-escalates-even-high-confidence
  (testing "escalate-hazard always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :escalate-hazard :effect :propose :subject "haz-1"
                                    :value {:site-id "site-001" :hazard-type :active-fire-hazard
                                            :severity :critical :description "active fire hazard"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t11")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/hazard-escalations db))))))))

(deftest hazard-escalation-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t12" {:op :escalate-hazard :effect :propose :subject "haz-2"
                                :value {:site-id "site-001" :hazard-type :vegetation-encroachment
                                        :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t12")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/hazard-escalations db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-reinspection-always-needs-approval
  (testing "a CLEAN reinspection scheduling proposal is never auto-eligible -- always escalates, even against a site with an elevated finding on file"
    (let [[db actor] (fresh)
          res (exec-op actor "t13" {:op :schedule-reinspection :effect :propose :subject "rei-4"
                                    :value {:site-id "site-002" :scheduled-date "2026-08-01"
                                            :actuate-equipment? false}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t13")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/reinspection-history db))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-inspection :effect :propose :subject "insp-001"
                          :patch {:site-id "site-001" :survey-type :building}} coordinator)
      (exec-op actor "b" {:op :log-inspection :effect :propose :subject "insp-002"
                          :patch {:site-id "site-001" :survey-type :fabricated-type}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
