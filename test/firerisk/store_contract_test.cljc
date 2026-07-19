(ns firerisk.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `firerisk.store` ns docstring."
  (:require [clojure.test :refer [deftest is testing]]
            [firerisk.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/site s "site-001"))))
    (is (true? (:registered? (store/site s "site-001"))))
    (is (nil? (:last-finding (store/site s "site-001"))))
    (is (true? (:verified? (store/site s "site-002"))))
    (is (= :elevated (:last-finding (store/site s "site-002"))))
    (is (false? (:verified? (store/site s "site-003"))))
    (is (false? (:registered? (store/site s "site-003"))))
    (is (= ["site-001" "site-002" "site-003"] (mapv :id (store/all-sites s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/finding-history s)))
    (is (= [] (store/reinspection-history s)))
    (is (= [] (store/hazard-escalations s)))
    (is (zero? (store/next-finding-sequence s)))
    (is (zero? (store/next-reinspection-sequence s)))
    (is (false? (store/reinspection-already-scheduled? s "rei-1")))
    (is (nil? (store/reinspection s "rei-1")))))

(deftest fresh-store-has-no-sites
  (let [s (store/mem-store)]
    (is (= [] (store/all-sites s)))
    (is (nil? (store/site s "site-001")))))

(deftest inspection-record-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :inspection-record/upsert :path ["insp-001"]
                             :value {:site-id "site-001" :survey-type :building
                                     :notes "初回"}})
    (is (= :building (:survey-type (store/inspection-record s "insp-001"))))
    (is (= "site-001" (:site-id (store/inspection-record s "insp-001"))))
    (store/commit-record! s {:effect :inspection-record/upsert :path ["insp-001"]
                             :value {:notes "更新"}})
    (is (= :building (:survey-type (store/inspection-record s "insp-001"))) "unrelated field preserved")
    (is (= "更新" (:notes (store/inspection-record s "insp-001"))))))

(deftest risk-finding-decide-commits-and-advances-sequence-and-updates-site
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly"
    (let [s (seeded)]
      (store/commit-record! s {:effect :risk-finding/decide :path ["find-1"]
                               :value {:site-id "site-001" :hazard-score 20.0 :finding :normal}})
      (is (= "RF-000000" (get (first (store/finding-history s)) "record_id")))
      (is (= "risk-finding-draft" (get (first (store/finding-history s)) "kind")))
      (is (= 1 (count (store/finding-history s))))
      (is (= 1 (store/next-finding-sequence s)))
      (is (= :normal (:last-finding (store/site s "site-001")))
          "ground-truth site record updated by the commit")
      (is (= :normal (:finding (store/risk-finding s "site-001")))))))

(deftest hazard-escalate-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :hazard/escalate :path ["haz-1"]
                             :value {:site-id "site-001" :hazard-type :active-fire-hazard :severity :critical}})
    (is (= 1 (count (store/hazard-escalations s))))
    (is (= :active-fire-hazard (:hazard-type (first (store/hazard-escalations s)))))
    (store/commit-record! s {:effect :hazard/escalate :path ["haz-2"]
                             :value {:site-id "site-002" :hazard-type :vegetation-encroachment :severity :moderate}})
    (is (= 2 (count (store/hazard-escalations s))) "append-only")))

(deftest reinspection-schedule-commits-and-advances-sequence
  (let [s (seeded)]
    (store/commit-record! s {:effect :reinspection/schedule :path ["rei-1"]
                             :value {:site-id "site-002" :scheduled-date "2026-08-01"}})
    (is (= "REI-000000" (get (first (store/reinspection-history s)) "record_id")))
    (is (= "reinspection-schedule-draft" (get (first (store/reinspection-history s)) "kind")))
    (is (true? (:scheduled? (store/reinspection s "rei-1"))))
    (is (= "site-002" (:site-id (store/reinspection s "rei-1"))))
    (is (= 1 (count (store/reinspection-history s))))
    (is (= 1 (store/next-reinspection-sequence s)))
    (is (true? (store/reinspection-already-scheduled? s "rei-1")))
    (is (= "REI-000000" (:reinspection-number (store/reinspection s "rei-1"))))))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
