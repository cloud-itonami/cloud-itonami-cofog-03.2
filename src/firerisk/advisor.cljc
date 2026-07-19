(ns firerisk.advisor
  "InspectionAdvisor -- the *contained intelligence node* for the
  fire-risk-inspection back-office coordination actor.

  It normalizes inspection-log patches (survey-type/notes), drafts a
  risk-finding decision from a robot's raw composite hazard-score
  sensor reading, drafts a hazard-escalation flag, and drafts a
  reinspection scheduling proposal against a site. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a real robot actuation beyond passive sensing or a fire-department
  dispatch -- see README `What this actor does NOT do`. Every output
  is censored downstream by `firerisk.governor` before anything
  touches the SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `firerisk.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:inspection-record/upsert
                                 ; :risk-finding/decide
                                 ; :hazard/escalate
                                 ; :reinspection/schedule} propose-shaped
                                 ; effects, NEVER a direct
                                 ; hardware-control effect
     :stake      kw|nil         ; :coordination/hazard-escalation | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) -- `firerisk.governor`
  HARD-holds any request that doesn't, so a mis-wired caller can never
  reach a commit path even if this advisor were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [firerisk.registry :as registry]
            [firerisk.store :as store]
            [langchain.model :as model]))

(defn- log-inspection
  "Inspection-log intake upsert -- the advisor only normalizes/
  validates the patch; it does not invent the survey-type or notes.
  High confidence, low stakes -- administrative logging, not an
  operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "現地調査記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :inspection-record/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- fire-hazard-survey
  "Draft an elevated/normal finding from a robot's raw composite
  hazard-score sensor reading against a site. The advisor reports what
  it can see (site verified?/registered?, the independently computed
  finding) in its rationale, but `firerisk.governor` NEVER trusts this
  report -- it independently re-derives verified?/registered? and the
  elevated/normal finding from the site's own stored threshold before
  any commit is possible. This mock advisor itself always reports the
  honestly-recomputed finding (it has no incentive to lie); the
  governor's own independent recompute exists for the LLM-advisor path
  and any compromised/hallucinating advisor."
  [db {:keys [subject value]}]
  (let [site-id (:site-id value)
        hazard-score (:hazard-score value)
        st-site (store/site db site-id)
        ready? (and st-site (registry/site-ready? st-site))
        finding (when st-site (registry/risk-finding st-site hazard-score))]
    {:summary    (str subject " 向け火災リスク判定提案 (hazard-score=" hazard-score ")"
                      (when st-site (str " site=" site-id)))
     :rationale  (if st-site
                   (str "site-verified?=" (registry/site-verified? st-site)
                        " site-registered?=" (registry/site-registered? st-site)
                        " threshold-score=" (:hazard-threshold-score st-site)
                        " finding=" finding)
                   (str site-id " が見つかりません"))
     :cites      (if st-site [site-id] [])
     :effect     :risk-finding/decide
     :value      (assoc value :finding (or finding :elevated))
     :stake      nil
     :confidence (if (and ready? (registry/hazard-score-valid? hazard-score)) 0.9 0.3)}))

(defn- escalate-hazard
  "Draft an active-hazard escalation requiring immediate
  fire-department dispatch (the exact scenario README `Robotics
  premise` names). ALWAYS `:stake :coordination/hazard-escalation` -- a
  hazard escalation is NEVER a proposal the advisor may quietly
  downgrade to low-stakes, and it is never gated on the referenced site
  being verified (a hazard can be raised about ANY site, verified or
  not -- see README `What this actor does NOT do` re: never blocking
  safety-relevant reporting on an administrative technicality). See
  `firerisk.phase`: no phase ever adds this op to a phase's `:auto`
  set; `firerisk.governor` also always escalates on
  `:coordination/hazard-escalation`. Two independent layers agree,
  deliberately."
  [db {:keys [subject value]}]
  (let [site-id (:site-id value)
        st-site (and site-id (store/site db site-id))]
    {:summary    (str subject " 向け危険事象エスカレーション (" (:hazard-type value) ")"
                      (when st-site (str " site=" site-id)))
     :rationale  (str "hazard-type=" (:hazard-type value)
                      " severity=" (:severity value)
                      " description=" (:description value))
     :cites      (if st-site [site-id] [])
     :effect     :hazard/escalate
     :value      value
     :stake      :coordination/hazard-escalation
     :confidence 0.9}))

(defn- schedule-reinspection
  "Draft a reinspection scheduling proposal against a site with an
  on-file ELEVATED finding. The advisor reports the site's own on-file
  finding status in its rationale, but `firerisk.governor` NEVER trusts
  it: it independently re-derives the site's own `:last-finding`
  before any commit is possible."
  [db {:keys [subject value]}]
  (let [site-id (:site-id value)
        st-site (store/site db site-id)
        ready? (and st-site (registry/site-ready? st-site))
        elevated? (and st-site (= :elevated (:last-finding st-site)))]
    {:summary    (str subject " 向け再調査予定提案"
                      (when st-site (str " site=" site-id)))
     :rationale  (if st-site
                   (str "site-verified?=" (registry/site-verified? st-site)
                        " site-registered?=" (registry/site-registered? st-site)
                        " last-finding=" (:last-finding st-site)
                        " actuate-equipment?=" (boolean (:actuate-equipment? value)))
                   (str site-id " が見つかりません"))
     :cites      (if st-site [site-id] [])
     :effect     :reinspection/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? elevated? (not (:actuate-equipment? value))) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-inspection            (log-inspection db request)
    :fire-hazard-survey        (fire-hazard-survey db request)
    :escalate-hazard           (escalate-hazard db request)
    :schedule-reinspection     (schedule-reinspection db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは火災リスク現地調査コーディネーターの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:inspection-record/upsert|:risk-finding/decide|"
       ":hazard/escalate|:reinspection/schedule) "
       ":stake(:coordination/hazard-escalation か nil) :confidence(0..1)。\n"
       "重要: 未検証または未登録のサイトに対する作業を提案してはいけません。"
       "検査ロボットの受動的センシングを超える直接操作(actuate)や警報・消火系統の直接操作を"
       "絶対に提案してはいけません(この actor は提案のみを行い、実行は一切行いません)。"
       "hazard-scoreセンサー読取値と矛盾する判定(:finding)を報告してはいけません。"
       "消防出動(dispatch)を自己申告する提案をしてはいけません。"))

(defn- facts-for [st {:keys [op value]}]
  (case op
    :log-inspection              {}
    :fire-hazard-survey          {:site (store/site st (:site-id value))}
    :escalate-hazard             {:site (and (:site-id value)
                                             (store/site st (:site-id value)))}
    :schedule-reinspection       {:site (store/site st (:site-id value))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `firerisk.governor`
  escalates/holds -- an LLM hiccup can never auto-decide a risk
  finding, auto-escalate a hazard, or auto-schedule a reinspection."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :firerisk-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
