(ns firerisk.operation-test
  "Smoke tests for the compiled InspectionOperationActor graph itself
  (build + one happy path per op). The governor's full rule contract
  (HARD holds, escalation, phase gating) is exercised in
  `firerisk.governor-contract-test` (full graph) and
  `firerisk.governor-test` (direct unit); the Store contract in
  `firerisk.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [firerisk.operation :as op]
            [firerisk.store :as store]))

(def coordinator {:actor-id "coord-1" :actor-role :inspection-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest test-actor-builds
  (testing "InspectionOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-inspection-logging-proposal
  (testing "Proposing an inspection log auto-commits when clean (phase 3, no physical/financial risk)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          initial-ledger-size (count (store/get-ledger s))
          result (exec-op actor "t1"
                          {:op :log-inspection :effect :propose :subject "insp-001"
                           :patch {:site-id "site-001" :survey-type :building}}
                          coordinator)
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-fire-hazard-survey
  (testing "Fire-hazard-survey findings always escalate for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t2"
                          {:op :fire-hazard-survey :effect :propose :subject "find-1"
                           :value {:site-id "site-001" :hazard-score 20.0}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t2") [:state :disposition]))))))

(deftest test-hazard-escalation
  (testing "Hazard escalations always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t3"
                          {:op :escalate-hazard :effect :propose :subject "haz-1"
                           :value {:site-id "site-001" :hazard-type :active-fire-hazard
                                   :severity :critical :description "active fire hazard"}}
                          coordinator)]
      (is (= :interrupted (:status result))))))

(deftest test-schedule-reinspection-proposal
  (testing "Reinspection scheduling proposal is submitted and (when the site has an elevated finding on file) escalates for approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t4"
                          {:op :schedule-reinspection :effect :propose :subject "rei-1"
                           :value {:site-id "site-002" :scheduled-date "2026-08-01"
                                   :actuate-equipment? false}}
                          coordinator)]
      (is (some? result))
      (is (= :interrupted (:status result))))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "The domain-agnostic commit-record! path stores a raw record by :id"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))
