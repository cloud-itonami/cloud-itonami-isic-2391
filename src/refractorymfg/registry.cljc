(ns refractorymfg.registry
  "Pure-function domain logic for the refractory products (fire brick,
  kiln lining, furnace lining materials) plant-operations coordination
  actor -- equipment/batch verification, shipment-weight recompute,
  product-type validation, thermal-shock-resistance plausibility
  validation, cold-crushing-strength plausibility validation, and
  draft maintenance-schedule/shipment-coordination record
  construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/refractorymfg`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `refractorymfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `claymfg.registry/shipment-weight-exceeded?` from
  `cloud-itonami-isic-2392`, the closest architectural sibling): never
  trust a proposal's own self-reported weight/status when the inputs
  needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating a pressing-line
  press or kiln line, or dispatching a real freight carrier (this
  actor NEVER does either -- see README `What this actor does NOT
  do`).

  SCOPE NOTE: ISIC 2391 (this actor) covers REFRACTORY PRODUCTS --
  high-temperature-service ceramics (fire brick, kiln lining, furnace
  lining materials) for industrial furnaces, kilns and other
  high-temperature process equipment: raw-material (chamotte, alumina,
  magnesia, silica) batching -> uniaxial/isostatic pressing or casting
  -> drying -> kiln-firing production lines that produce fireclay
  brick, silica brick, high-alumina brick, magnesia brick, insulating
  firebrick, monolithic castable, ramming mix, and kiln furniture. This
  is distinct from `cloud-itonami-isic-2392` (Manufacture of clay
  building materials), which covers building-material brick/tile for
  construction rather than high-temperature-service furnace/kiln
  linings, and from `cloud-itonami-isic-2393` (Manufacture of other
  porcelain and ceramic products), a separate ceramics vertical with a
  different feedstock and firing profile. This actor's own hazard
  profile is centered on the kiln-firing line and raw-material
  batching/pressing: kiln-fire/thermal-hazard (radiant heat and burn
  risk at the firing zone, typically hotter service than building-
  material brick firing), refractory-dust hazard (crystalline
  silica/alumina/magnesia respirable dust exposure at batching and
  pressing -- a well-documented silicosis risk in this industry), and
  pressing-line-equipment pinch-point/crush hazard (uniaxial/isostatic
  hydraulic press).")

;; ----------------------------- constants -----------------------------

(def valid-product-types
  "The closed set of product-type values a production batch (a
  kiln-fired lot) record may declare -- the standard refractory
  product families this actor's plant may produce. Anything else is a
  fabricated/unrecognized product type -- the governor HARD-holds
  rather than let an invented product type pass through."
  #{:fireclay-brick :silica-brick :high-alumina-brick :magnesia-brick
    :insulating-firebrick :monolithic-castable :ramming-mix :kiln-furniture})

(def thermal-shock-cycles-min
  "Physical floor for a batch's own thermal-shock-resistance reading
  (a water-quench cycle-to-failure test per e.g. ASTM C1171 -- zero
  cycles survived is the worst possible outcome, never negative)."
  0.0)

(def thermal-shock-cycles-max
  "Physical ceiling for a batch's own thermal-shock-resistance reading
  -- no known refractory brick survives beyond this many quench
  cycles in the standard test. A reading above this is implausible
  gauge/QC data, not a real batch."
  200.0)

(def cold-crushing-strength-min-mpa
  "Physical floor for a batch's own cold-crushing-strength (CCS)
  reading in MPa (zero strength is the worst possible outcome, never
  negative)."
  0.0)

(def cold-crushing-strength-max-mpa
  "Physical ceiling for a batch's own cold-crushing-strength (CCS)
  reading in MPa -- no known refractory product exceeds this
  compressive strength. A reading above this is implausible
  sensor/QC data, not a real batch."
  300.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its product-type/weight/thermal-shock/cold-crushing-strength
  claims have actually been QC-inspected, not merely logged from an
  unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-weight-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-kg` + `new-weight-kg` exceed `batch`'s own
  recorded `:weight-kg` (the batch's own logged production weight)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-weight-kg]
  (let [capacity (:weight-kg batch)
        so-far (:shipped-weight-kg batch 0.0)]
    (and (number? capacity)
         (number? new-weight-kg)
         (number? so-far)
         ;; Compared at 1/10000 of a unit, not on raw doubles. A shipment
         ;; that fills a batch EXACTLY to its recorded capacity is legal,
         ;; and comparing the raw sum flagged such shipments as over
         ;; because the sum is not the double nearest the true total.
         (> (Math/round (* 10000 (+ (double so-far) (double new-weight-kg))))
            (Math/round (* 10000 (double capacity))))))) 

(defn shipment-weight-exceeded-checkable?
  "Can `batch`'s headroom actually be computed for `new-weight-kg`?

  `shipment-weight-exceeded?` answers only `over` / `not over`, and its
  `(and (number? ...) ...)` guard made every un-checkable case fall
  through as `not over` -- a batch with no recorded capacity, or a
  shipment stating no amount, passed the over-capacity check silently.
  Callers must ask this first: un-checkable is not headroom."
  [batch new-weight-kg]
  (boolean (and (map? batch)
                (number? (:weight-kg batch))
                (number? (:shipped-weight-kg batch 0.0))
                (number? new-weight-kg))))

(defn product-type-valid?
  "Is `product-type` one of the closed, known refractory product
  values? nil/blank is treated as invalid (a production-batch patch
  must declare a real product type, not omit it silently)."
  [product-type]
  (contains? valid-product-types product-type))

(defn thermal-shock-cycles-valid?
  "Is `cycles` a physically plausible batch thermal-shock-resistance
  reading (water-quench cycles survived before failure)? Rejects nil,
  non-numbers, negative values, and values beyond
  `thermal-shock-cycles-max` -- a fabricated or gauge-error reading,
  never let through as a real batch fact."
  [cycles]
  (and (number? cycles)
       (>= (double cycles) thermal-shock-cycles-min)
       (<= (double cycles) thermal-shock-cycles-max)))

(defn cold-crushing-strength-valid?
  "Is `mpa` a physically plausible batch cold-crushing-strength (CCS)
  reading, in MPa? Rejects nil, non-numbers, negative values, and
  values beyond `cold-crushing-strength-max-mpa` -- a fabricated or
  sensor-error reading, never let through as a real batch fact."
  [mpa]
  (and (number? mpa)
       (>= (double mpa) cold-crushing-strength-min-mpa)
       (<= (double mpa) cold-crushing-strength-max-mpa)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
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

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  pressing-line/kiln-line-equipment maintenance window against a
  verified, registered piece of equipment. Pure function -- does not
  actuate the pressing-line press or kiln line or execute any
  maintenance; it builds the RECORD a plant coordinator would keep.
  `refractorymfg.governor` independently re-verifies the equipment's
  own verified/registered ground truth, and permanently blocks any
  attempt to directly actuate the pressing-line press/kiln line (see
  README `Actuation`), before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound refractory product shipment against a verified, registered
  production batch. Pure function -- does not dispatch any real
  freight carrier; it builds the RECORD a plant coordinator would
  keep. `refractorymfg.governor` independently re-verifies the
  shipment's own claimed weight against `shipment-weight-exceeded?`,
  before this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
