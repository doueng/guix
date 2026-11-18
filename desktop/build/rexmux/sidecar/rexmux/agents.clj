(ns rexmux.agents
  "Agents as Rex sees them: the Program Status Protocol (OSC 7501) records a
  program reports in its terminal block. A block can hold several records (by
  :id); its status is the most urgent one.

    state  {:blocks {block-ref {:session s :records {id record} :seen? bool}}}
    record {:state \"blocked\" :kind \"question\" :app \"pi\" :msg \"..\"}

  The attention list orders blocks that want the person: blocked, then error,
  then unseen done, as Herdr's agent picker does (blocked before done before
  working before idle). Focusing a done block marks it seen; nothing but a new
  report takes a block out of blocked or error, so a blocked agent can always
  be reached with next-blocked."
  (:require [clojure.string :as str]))

(def states ["blocked" "error" "done" "working" "idle"])
(def rank (zipmap states (range)))
(def icon {"blocked" "!" "error" "✗" "done" "✓" "working" "●" "idle" "○"})

(def empty-state {:blocks {}})

(defn status
  "The most urgent record of BLOCK-ENTRY, or nil."
  [{:keys [records]}]
  (first (sort-by #(rank (:state %) 99) (vals records))))

(defn report
  "Fold one program_status_changed or _removed payload into STATE. RECORD
  nil, or state clear, removes the record ID; an unknown state is ignored,
  as the protocol says. A new non-done state clears :seen?."
  [state {:keys [session server block id record]}]
  (let [id (or id "")
        st (:state record)]
    (cond
      (or (nil? record) (= "clear" st))
      (let [s (update-in state [:blocks block :records] dissoc id)]
        (if (empty? (get-in s [:blocks block :records])) (update s :blocks dissoc block) s))

      (not (contains? rank st)) state

      :else
      (-> state
          (assoc-in [:blocks block :session] session)
          (cond-> server (assoc-in [:blocks block :server] server))
          (assoc-in [:blocks block :records id] (select-keys record [:state :kind :app :msg :title :updated_at]))
          (update-in [:blocks block] #(cond-> % (not= "done" st) (assoc :seen? false)))))))

(defn block-closed [state block] (update state :blocks dissoc block))

(defn focused
  "The person looked at BLOCK: a done status there is now seen."
  [state block]
  (if (get-in state [:blocks block]) (assoc-in state [:blocks block :seen?] true) state))

(defn wants-attention? [{:keys [seen?] :as entry}]
  (let [st (:state (status entry))]
    (or (= "blocked" st) (= "error" st) (and (= "done" st) (not seen?)))))

(defn attention
  "[{:block :session :state :kind :app :msg}] of blocks that want the person,
  most urgent first, then by block id for a stable order."
  [state]
  (->> (:blocks state)
       (filter (comp wants-attention? val))
       (map (fn [[b e]] (merge {:block b :session (:session e) :server (:server e)} (status e))))
       (sort-by (juxt #(rank (:state %)) :block))
       vec))

(defn listing
  "Every agent block, most urgent first: the agent picker's rows."
  [state]
  (->> (:blocks state)
       (map (fn [[b e]] (merge {:block b :session (:session e) :server (:server e) :seen? (boolean (:seen? e))} (status e))))
       (sort-by (juxt #(rank (:state %) 99) :block))
       vec))

(defn next-in
  "The block after CURRENT in ITEMS (wrapping), or the first when CURRENT is
  not among them. nil when ITEMS is empty."
  [items current]
  (when (seq items)
    (let [blocks (mapv :block items)
          i (.indexOf ^java.util.List blocks current)]
      (nth items (if (neg? i) 0 (mod (inc i) (count items)))))))

(defn next-blocked
  "The next blocked agent after CURRENT, or nil when none is blocked."
  [state current]
  (next-in (filterv #(= "blocked" (:state %)) (attention state)) current))

(defn next-attention
  "The next agent wanting attention after CURRENT (blocked, error, done)."
  [state current]
  (next-in (attention state) current))

(defn line
  "One picker or sidebar line for an agent ROW."
  [{:keys [state kind app msg block]}]
  (str (icon state "?") " " (or app "agent")
       (when kind (str " (" kind ")"))
       (when-not (str/blank? msg) (str ": " (str/replace msg #"\s+" " ")))
       "  " (subs (str block) (max 0 (- (count (str block)) 6)))))

(defn from-event
  "The report for a Rex events stream message, or nil when it is not a
  program status event. Messages look like
  {:type session_event :payload {:session_id .. :event {:type block_event
   :payload {:block_id .. :name program_status_changed :payload {:id :record}}}}}"
  [msg]
  (let [sid (get-in msg [:payload :session_id])
        ev (get-in msg [:payload :event])
        bp (:payload ev)]
    (case (:type ev)
      "block_event" (case (:name bp)
                      "program_status_changed" {:session sid :block (:block_id bp) :id (get-in bp [:payload :id]) :record (get-in bp [:payload :record])}
                      "program_status_removed" {:session sid :block (:block_id bp) :id (get-in bp [:payload :id]) :record nil}
                      nil)
      "block_closed" {:session sid :block (:block_id bp) :closed true}
      nil)))
