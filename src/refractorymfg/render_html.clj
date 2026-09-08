(ns refractorymfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: there was previously
  no demo page and no generator at all. This namespace drives the REAL
  actor stack -- `refractorymfg.operation/build` (a compiled
  langgraph-clj StateGraph) -> `refractorymfg.advisor` ->
  `refractorymfg.governor` -> `refractorymfg.phase` ->
  `refractorymfg.registry` -> `refractorymfg.store` -- through a
  scenario adapted from this repo's own `refractorymfg.sim` demo driver
  (`clojure -M:dev:run`, confirmed to run BEFORE this file was written).

  EVERY id, number, status, rule name and hold detail on the rendered
  page is read back out of that real run: the batch/equipment tables
  come from the `MemStore` AFTER the actor has mutated it, the
  disposition column comes from each graph run's own returned state,
  the HARD-hold rules and their Japanese detail strings come from
  `refractorymfg.governor`'s own violation maps, the draft record
  numbers (`MNT-000000` / `SHP-000000`) come from
  `refractorymfg.registry`, and the phase gate table is derived from
  `refractorymfg.phase/phases` rather than re-described by hand.
  Nothing on the page is hand-typed domain data.

  The subject ids used are exactly the ones
  `refractorymfg.store/sample-data!` seeds -- `batch-001` / `batch-002`
  / `batch-003` / `kiln-001` / `press-002` -- cross-checked against the
  store before the scenario was written (a sibling repo's sim driver
  was found to reference ids its own store never seeds, which renders
  an all-\"no activity\" page).

  DETERMINISTIC: no timestamps and no randomness reach the page, so two
  consecutive runs against the same seed are byte-identical (verify by
  diffing them).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [langgraph.graph :as g]
            [refractorymfg.governor :as governor]
            [refractorymfg.operation :as op]
            [refractorymfg.phase :as phase]
            [refractorymfg.store :as store]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- record-run!
  "Captures ONE real graph run into the run log -- op/subject/verdict/
  disposition are read back out of the actor's own returned state, never
  restated by the caller."
  [log kind r]
  (let [{:keys [request verdict disposition approval]} (:state r)]
    (swap! log conj {:kind kind
                     :op (:op request)
                     :subject (:subject request)
                     :status (:status r)
                     :disposition disposition
                     :confidence (:confidence verdict)
                     :hard? (:hard? verdict)
                     :high-stakes? (:high-stakes? verdict)
                     :violations (:violations verdict)
                     :approved-by (:by approval)})
    r))

(defn- exec! [actor log tid request]
  (record-run! log :request (g/run* actor {:request request :context coordinator}
                                    {:thread-id tid})))

(defn- approve! [actor log tid]
  (record-run! log :approval
               (g/run* actor {:approval {:status :approved :by "coord-1"}}
                       {:thread-id tid :resume? true})))

(defn run-demo!
  "Runs a freshly seeded store through the real actor.

  CLEAN LIFECYCLE (batch-001 / kiln-001): a production-batch intake
  patch auto-commits (phase 3 lists `:log-production-batch` in its
  `:auto` set and the governor is clean); a kiln-lining maintenance
  window is scheduled against the verified+registered tunnel kiln
  `kiln-001` -- escalated, because `:schedule-maintenance` is
  deliberately absent from EVERY phase's `:auto` set -- then approved
  by a human plant supervisor and committed as draft `MNT-000000`; a
  kiln safety concern is flagged (always high-stakes, always a human)
  and approved; and a 5,000 kg outbound shipment against `batch-001`
  is escalated, approved, and committed as draft `SHP-000000`, moving
  the batch's own cumulative shipped weight from 8,000 kg to 13,000 kg.

  HARD HOLDS (none of these ever reaches a human -- the graph routes
  straight from :decide to :hold, and the phase gate cannot soften a
  governor hold):
    :kiln-line-actuate-blocked  a maintenance proposal that declares
                                `:actuate-kiln-pressing-line? true` --
                                permanently blocked, no override at any
                                phase
    :equipment-not-verified     maintenance against `press-002`, the
                                UNVERIFIED/unregistered isostatic press
    :batch-not-verified         a shipment against `batch-003`, the
                                UNVERIFIED/unregistered magnesia batch
    :shipment-weight-exceeded   a 1,000 kg shipment against `batch-002`
                                whose own record already shows
                                5,600 kg shipped of 6,000 kg produced
    :already-scheduled          re-scheduling maintenance window `mnt-1`
    :invalid-product-type       a batch patch claiming a fabricated
                                product type
    :not-propose-effect         a mis-wired caller whose own request
                                `:effect` is not `:propose`

  Returns `{:db store :runs [..]}` -- both are real actor output."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        log (atom [])]

    ;; ---- clean lifecycle -------------------------------------------------
    (exec! actor log "t1"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :fireclay-brick :last-assessed "2026-07-14"}})

    (exec! actor log "t2"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "kiln-001" :maintenance-type :kiln-lining-inspection
                    :scheduled-date "2026-08-01" :actuate-kiln-pressing-line? false}})
    (approve! actor log "t2")

    (exec! actor log "t3"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "kiln-001" :severity :moderate
                    :description "トンネル窯出口付近の輻射熱上昇、耐火物粉塵滞留の兆候"}})
    (approve! actor log "t3")

    (exec! actor log "t4"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :weight-kg 5000.0
                    :destination "buyer-yard-north"}})
    (approve! actor log "t4")

    ;; ---- HARD holds ------------------------------------------------------
    (exec! actor log "t5"
           {:op :schedule-maintenance :effect :propose :subject "mnt-actuate"
            :value {:equipment-id "kiln-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-kiln-pressing-line? true}})

    (exec! actor log "t6"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "press-002" :maintenance-type :die-inspection
                    :scheduled-date "2026-08-01" :actuate-kiln-pressing-line? false}})

    (exec! actor log "t7"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :weight-kg 1000.0
                    :destination "buyer-yard-south"}})

    (exec! actor log "t8"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :weight-kg 1000.0
                    :destination "buyer-yard-east"}})

    (exec! actor log "t9"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "kiln-001" :maintenance-type :kiln-lining-inspection
                    :scheduled-date "2026-08-01" :actuate-kiln-pressing-line? false}})

    (exec! actor log "t10"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :unobtainium-brick}})

    (exec! actor log "t11"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-type :fireclay-brick}})

    {:db db :runs @log}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-str [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- flag [b yes no]
  (if b
    (str "<span class=\"ok\">" yes "</span>")
    (str "<span class=\"critical\">" no "</span>")))

(defn- num-str
  "Renders a stored number without scientific notation. Whole doubles
  print as integers so the page reads like a plant sheet; nothing is
  rounded away."
  [v]
  (cond
    (nil? v) "—"
    (and (number? v) (== (double v) (Math/rint (double v))))
    (str (long (double v)))
    :else (str v)))

;; ---- production batches ----

(defn- batch-row [{:keys [id product-type material weight-kg shipped-weight-kg
                          verified? registered? last-assessed]}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
               "<td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc (kw-str product-type)) (esc material)
          (esc (num-str weight-kg))
          (esc (num-str shipped-weight-kg))
          (esc (num-str (when (and (number? weight-kg) (number? shipped-weight-kg))
                          (- (double weight-kg) (double shipped-weight-kg)))))
          (flag verified? "QC 検証済" "未検証")
          (flag registered? "登録済" "未登録")
          (esc (or last-assessed "—"))))

;; ---- equipment ----

(defn- equipment-row [db {:keys [id kind verified? registered?
                                 last-maintenance-date last-scheduled-maintenance-date]}]
  (let [windows (filter #(= id (:equipment-id %)) (store/all-maintenance db))]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
                 "<td>%s</td><td>%s</td><td>%s</td></tr>")
            (esc id) (esc (kw-str kind))
            (flag verified? "点検済" "未検証")
            (flag registered? "登録済" "未登録")
            (esc (or last-maintenance-date "—"))
            (esc (or last-scheduled-maintenance-date "—"))
            (if (seq windows)
              (str/join ", " (map #(str "<code>" (esc (:maintenance-number %)) "</code>") windows))
              "<span class=\"muted\">なし</span>"))))

;; ---- coordination requests (one row per real graph run) ----

(defn- disposition-cell [{:keys [kind status disposition hard? high-stakes? approved-by violations]}]
  (cond
    (and (= :approval kind) (= :commit disposition))
    (str "<span class=\"ok\">承認 &rarr; commit</span> <span class=\"muted\">by "
         (esc (or approved-by "—")) "</span>")

    (= :commit disposition)
    "<span class=\"ok\">自動 commit</span> <span class=\"muted\">(phase 3 :auto)</span>"

    (and (= :hold disposition) hard?)
    (str "<span class=\"critical\">HARD hold &middot; "
         (esc (str/join ", " (map (comp kw-str :rule) violations)))
         "</span> <span class=\"muted\">人間に到達しない</span>")

    (= :hold disposition) "<span class=\"critical\">hold</span>"

    (= :escalate disposition)
    (str "<span class=\"warn\">escalate &rarr; 人間の承認待ち</span>"
         (when high-stakes? " <span class=\"muted\">(high-stakes)</span>")
         (when (= :interrupted status) " <span class=\"muted\">[interrupted]</span>"))

    :else "<span class=\"muted\">—</span>"))

(defn- run-row [{:keys [op subject confidence] :as r}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td class=\"num\">%s</td><td>%s</td></tr>"
          (esc (kw-str (or op :n-a))) (esc subject)
          (esc (if (number? confidence) (str confidence) "—"))
          (disposition-cell r)))

;; ---- governor holds ----

(defn- hold-rows [ledger]
  (for [f ledger
        :when (= :governor-hold (:t f))
        v (:violations f)]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
            (esc (kw-str (:op f))) (esc (:subject f))
            (esc (kw-str (:rule v))) (esc (:detail v)))))

;; ---- draft records ----

(defn- maintenance-draft-row [db rec]
  (let [mid (get rec "maintenance_id")
        m (store/maintenance db mid)]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td>"
                 "<td><code>%s</code></td><td>%s</td><td>%s</td></tr>")
            (esc (get rec "record_id")) (esc (get rec "kind"))
            (esc mid) (esc (get rec "equipment_id"))
            (esc (kw-str (:maintenance-type m)))
            (esc (or (:scheduled-date m) "—")))))

(defn- shipment-draft-row [db rec]
  (let [sid (get rec "shipment_id")
        s (store/shipment db sid)]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td>"
                 "<td><code>%s</code></td><td class=\"num\">%s</td><td>%s</td></tr>")
            (esc (get rec "record_id")) (esc (get rec "kind"))
            (esc sid) (esc (:batch-id s))
            (esc (num-str (:weight-kg s)))
            (esc (or (:destination s) "—")))))

(defn- concern-row [{:keys [id equipment-id severity description]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc equipment-id) (esc (kw-str severity)) (esc description)))

;; ---- phase gate (derived from refractorymfg.phase, not hand-described) ----

(defn- phase-gate-rows []
  (let [ph (:phase coordinator phase/default-phase)
        {:keys [label writes auto]} (get phase/phases ph)]
    (cons
     (format "        <tr><td colspan=\"2\"><strong>phase %s — %s</strong></td></tr>"
             (esc ph) (esc label))
     (for [o (sort-by kw-str governor/allowed-ops)]
       (format "        <tr><td><code>%s</code></td><td>%s</td></tr>"
               (esc (kw-str o))
               (cond
                 (not (contains? writes o))
                 "<span class=\"critical\">この phase では書き込み不可 (hold)</span>"
                 (contains? auto o)
                 "<span class=\"ok\">governor がクリーンなら自動 commit</span>"
                 :else
                 "<span class=\"warn\">常に人間の承認が必要 (どの phase の :auto にも属さない)</span>"))))))

;; ---- audit ledger ----

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str t)) (esc (kw-str (or op :n-a))) (esc subject)
          (esc (kw-str (or disposition "")))
          (esc (str/join ", " (map kw-str (or basis []))))))

(defn render
  "Renders the operator console from `{:db .. :runs ..}` produced by
  `run-demo!` (or any other real scenario run through this actor)."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        mnt-drafts (store/maintenance-history db)
        shp-drafts (store/shipment-history db)
        concerns (store/safety-concerns db)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2391 &middot; refractory-products plant operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>耐火物製造 (ISIC 2391) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · 保守作業予定は常に人間承認 · プレス成形機/窯業焼成ラインの直接操作は恒久禁止</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>生産バッチ (production batches)</h2>\n"
     "    <p class=\"muted\">実行後の <code>refractorymfg.store</code> の状態。出荷済み重量はガバナが独立に再計算する接地事実で、提案の自己申告ではない。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>バッチ</th><th>製品種別</th><th>材質</th><th>生産量 (kg)</th><th>出荷済 (kg)</th><th>残余 (kg)</th><th>QC 検証</th><th>登録</th><th>最終評価日</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map batch-row batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>設備 (pressing / kiln line equipment)</h2>\n"
     "    <p class=\"muted\">未検証または未登録の設備に対する保守作業予定提案は HARD hold。<code>refractorymfg.registry/equipment-ready?</code> で独立に再判定される。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>設備</th><th>種別</th><th>点検</th><th>登録</th><th>前回保守</th><th>予定済み保守日</th><th>予定ドラフト</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial equipment-row db) equipment)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>この実行の調整要求 (coordination requests)</h2>\n"
     "    <p class=\"muted\">1 行 = 1 グラフ実行。判定は各実行が返した state から読み出したもの。HARD hold は <code>:decide</code> から直接 <code>:hold</code> へ抜け、人間に到達しない。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>操作</th><th>対象</th><th>confidence</th><th>判定</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map run-row runs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>ガバナの HARD hold (Refractory Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">上書き不可。detail は <code>refractorymfg.governor</code> が実行時に生成した違反理由そのもの。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>操作</th><th>対象</th><th>規則</th><th>理由</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (hold-rows ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>操作ゲート (phase gate)</h2>\n"
     "    <p class=\"muted\"><code>refractorymfg.phase/phases</code> と <code>refractorymfg.governor/allowed-ops</code> から導出。手書きの説明ではない。許可された提案 effect: "
     (esc (str/join " / " (map kw-str (sort-by kw-str governor/allowed-proposal-effects))))
     " — これ以外の effect (窯・プレスの直接操作等) は恒久的に HARD hold。confidence 下限 "
     (esc (str governor/confidence-floor)) "。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>操作</th><th>ゲート</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (phase-gate-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>保守作業予定ドラフト (maintenance schedule drafts)</h2>\n"
     "    <p class=\"muted\">この actor が作るのは記録のドラフトのみ。証明書は未署名 (<code>status: draft-unsigned</code>) — 署名は人間の保守責任者の行為。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>記録番号</th><th>種別</th><th>保守 ID</th><th>設備</th><th>作業種別</th><th>予定日</th></tr></thead>\n"
     "      <tbody>\n"
     (if (seq mnt-drafts)
       (str (str/join "\n" (map (partial maintenance-draft-row db) mnt-drafts)) "\n")
       "        <tr><td colspan=\"6\"><span class=\"muted\">なし</span></td></tr>\n")
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>出荷調整ドラフト (shipment coordination drafts)</h2>\n"
     "    <p class=\"muted\">実運送の手配は一切行わない。重量はコミット前にバッチ自身の記録から独立に検算される。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>記録番号</th><th>種別</th><th>出荷 ID</th><th>バッチ</th><th>重量 (kg)</th><th>仕向先</th></tr></thead>\n"
     "      <tbody>\n"
     (if (seq shp-drafts)
       (str (str/join "\n" (map (partial shipment-draft-row db) shp-drafts)) "\n")
       "        <tr><td colspan=\"6\"><span class=\"muted\">なし</span></td></tr>\n")
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>安全懸念 (safety concerns)</h2>\n"
     "    <p class=\"muted\">安全懸念は常に high-stakes として人間へ escalate される。設備が未検証でも報告自体は妨げない。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>懸念 ID</th><th>設備</th><th>深刻度</th><th>内容</th></tr></thead>\n"
     "      <tbody>\n"
     (if (seq concerns)
       (str (str/join "\n" (map concern-row concerns)) "\n")
       "        <tr><td colspan=\"4\"><span class=\"muted\">なし</span></td></tr>\n")
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>監査台帳 (append-only audit ledger)</h2>\n"
     "    <p class=\"muted\">この実行が生成した決定事実の全件。commit も hold も同じ台帳に残る。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>事実</th><th>操作</th><th>対象</th><th>処理</th><th>根拠</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>Generated at build time by <code>refractorymfg.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from a real "
     "<code>refractorymfg.operation</code> actor run — no hand-written page data.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count runs) " graph runs, "
                  (count (store/ledger db)) " ledger facts, "
                  (count (filter #(= :governor-hold (:t %)) (store/ledger db))) " HARD holds, "
                  (count (store/maintenance-history db)) " maintenance drafts, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/safety-concerns db)) " safety concerns)"))))
