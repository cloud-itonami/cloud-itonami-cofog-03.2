(ns firerisk.registry-test
  (:require [clojure.test :refer [deftest is]]
            [firerisk.registry :as r]))

;; ----------------------------- site-verified? / site-registered? / site-ready? -----------------------------

(deftest site-is-verified-when-flagged
  (is (true? (r/site-verified? {:id "s1" :verified? true}))))

(deftest site-is-not-verified-when-false-or-missing
  (is (false? (r/site-verified? {:id "s1" :verified? false})))
  (is (false? (r/site-verified? {:id "s1"}))))

(deftest site-is-registered-when-flagged
  (is (true? (r/site-registered? {:registered? true}))))

(deftest site-is-not-registered-when-false-or-missing
  (is (false? (r/site-registered? {:registered? false})))
  (is (false? (r/site-registered? {}))))

(deftest site-ready-requires-both
  (is (true? (r/site-ready? {:verified? true :registered? true})))
  (is (false? (r/site-ready? {:verified? true :registered? false})))
  (is (false? (r/site-ready? {:verified? false :registered? true})))
  (is (false? (r/site-ready? {}))))

;; ----------------------------- risk-finding -----------------------------

(deftest reading-below-threshold-is-normal
  (is (= :normal (r/risk-finding {:hazard-threshold-score 70.0} 20.0))))

(deftest reading-at-or-above-threshold-is-elevated
  (is (= :elevated (r/risk-finding {:hazard-threshold-score 70.0} 70.0))
      "exactly at threshold is not below it, only strictly under")
  (is (= :elevated (r/risk-finding {:hazard-threshold-score 70.0} 71.0))))

(deftest missing-inputs-fail-closed-to-elevated
  (is (= :elevated (r/risk-finding {} 20.0)))
  (is (= :elevated (r/risk-finding {:hazard-threshold-score 70.0} nil)))
  (is (= :elevated (r/risk-finding {:hazard-threshold-score 70.0} "20"))))

;; ----------------------------- hazard-score-valid? -----------------------------

(deftest typical-hazard-score-is-valid
  (is (r/hazard-score-valid? 0.0))
  (is (r/hazard-score-valid? 20.0))
  (is (r/hazard-score-valid? 100.0)))

(deftest negative-hazard-score-is-invalid
  (is (not (r/hazard-score-valid? -1.0))))

(deftest excessive-hazard-score-is-invalid
  (is (not (r/hazard-score-valid? 100.01)))
  (is (not (r/hazard-score-valid? 999999.0))))

(deftest non-numeric-or-missing-hazard-score-is-invalid
  (is (not (r/hazard-score-valid? nil)))
  (is (not (r/hazard-score-valid? "20"))))

;; ----------------------------- survey-type-valid? -----------------------------

(deftest known-survey-types-are-valid
  (doseq [t [:building :vegetation :fire-protection-system]]
    (is (r/survey-type-valid? t))))

(deftest fabricated-survey-type-is-invalid
  (is (not (r/survey-type-valid? :aerial-drone-strike)))
  (is (not (r/survey-type-valid? nil))))

;; ----------------------------- register-risk-finding -----------------------------

(deftest finding-is-a-draft-not-a-signed-clearance
  (let [result (r/register-risk-finding "find-1" "site-001" :normal 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest finding-assigns-finding-number
  (let [result (r/register-risk-finding "find-1" "site-001" :elevated 7)]
    (is (= (get result "finding_number") "RF-000007"))
    (is (= (get-in result ["record" "finding_id"]) "find-1"))
    (is (= (get-in result ["record" "site_id"]) "site-001"))
    (is (= (get-in result ["record" "finding"]) "elevated"))
    (is (= (get-in result ["record" "kind"]) "risk-finding-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest finding-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-risk-finding "" "site-001" :normal 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-risk-finding "find-1" "" :normal 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-risk-finding "find-1" "site-001" :maybe 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-risk-finding "find-1" "site-001" :normal -1))))

;; ----------------------------- register-reinspection -----------------------------

(deftest reinspection-is-a-draft-not-a-real-dispatch
  (let [result (r/register-reinspection "rei-1" "site-002" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest reinspection-assigns-reinspection-number
  (let [result (r/register-reinspection "rei-1" "site-002" 7)]
    (is (= (get result "reinspection_number") "REI-000007"))
    (is (= (get-in result ["record" "reinspection_id"]) "rei-1"))
    (is (= (get-in result ["record" "kind"]) "reinspection-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest reinspection-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-reinspection "" "site-002" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-reinspection "rei-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-reinspection "rei-1" "site-002" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-risk-finding "find-1" "site-001" :normal 0)
        hist (r/append [] c1)
        c2 (r/register-risk-finding "find-2" "site-001" :elevated 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "RF-000000" (get-in hist2 [0 "record_id"])))
    (is (= "RF-000001" (get-in hist2 [1 "record_id"])))))
