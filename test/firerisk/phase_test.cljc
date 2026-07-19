(ns firerisk.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:fire-hazard-survey` and `:schedule-reinspection` must
  NEVER be members of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [firerisk.phase :as phase]))

(deftest fire-hazard-survey-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a risk finding"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :fire-hazard-survey))
          (str "phase " n " must not auto-commit :fire-hazard-survey")))))

(deftest escalate-hazard-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :escalate-hazard))
        (str "phase " n " must not auto-commit :escalate-hazard"))))

(deftest schedule-reinspection-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :schedule-reinspection))
        (str "phase " n " must not auto-commit :schedule-reinspection"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-inspection carries no physical/financial risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-inspection} (:auto (get phase/phases 3))))))

(deftest fire-hazard-survey-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :fire-hazard-survey))
  (is (not (contains? (:writes (get phase/phases 2)) :fire-hazard-survey)))
  (is (not (contains? (:writes (get phase/phases 1)) :fire-hazard-survey))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-inspection} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :fire-hazard-survey} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :escalate-hazard} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-reinspection} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-inspection} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-inspection} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
