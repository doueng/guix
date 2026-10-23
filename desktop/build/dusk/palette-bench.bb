#!/usr/bin/env bb
;; Pure phase measurements. No IPC or changes to the live Dusk shell.
(require '[clojure.edn :as edn]
         '[cheshire.core :as json]
         '[dusk.core :as core]
         '[dusk.runtime :as runtime])
(let [[config-path wire-path output-path] *command-line-args*]
  (load-file config-path)
  (let [wire (slurp wire-path)
        timed (fn [f] (let [start (System/nanoTime) value (f)]
                        [value (/ (- (System/nanoTime) start) 1e6)]))
        [request parse-ms] (timed #(edn/read-string wire))
        args (:args request)
        panel #(deref (ns-resolve 'config 'launcher-panel))
        render #((deref (ns-resolve 'dusk.runtime 'serialize)) ((panel)) "launcher" (volatile! {}))
        [_ open-ms] (timed #((get @core/request-handlers :palette) args identity))
        [_ empty-render-ms] (timed render)
        input (get-in ((panel)) [:children 1 :children 0 :props :on-change])
        [_ search-ms] (timed #(input "save-buffer"))
        [tree render-ms] (timed render)
        [encoded encode-ms] (timed #(pr-str tree))
        [_ navigation-ms] (timed #(do ((deref (ns-resolve 'config 'launcher-key)) {:key "down"}) (render)))
        rows (-> tree :children second :children rest)]
    (assert (seq rows) "query must produce real visible rows")
    (assert (some #(= "save-buffer" (get-in % [:children 0 :children 0])) rows)
            "save-buffer must be among the results")
    (spit output-path (json/generate-string
                      {:runtime_parse_ms parse-ms :open_ms open-ms :empty_render_ms empty-render-ms
                       :search_ms search-ms :render_ms render-ms :layout_encode_ms encode-ms
                       :navigation_ms navigation-ms :visible_rows (count rows) :layout_bytes (count encoded)}))))
