#!/usr/bin/env bb
;; quint-language-server over stdio, as an editor uses it: diagnostics for a
;; name that is not defined, then, once the file typechecks, hover and
;; go-to-definition. Uses the server on PATH, else this repo's package.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def modules (str (fs/normalize (fs/path (fs/parent (fs/absolutize *file*)) ".." ".." ".." ".." "modules"))))

(def server
  (or (some-> (fs/which "quint-language-server") str)
      (let [{:keys [out exit err]} (p/sh "guix" "build" "-L" modules "-e"
                                         "(@ (engstrand packages quint) quint-language-server)")]
        (when-not (zero? exit) (binding [*out* *err*] (println err)) (System/exit 1))
        (str (last (str/split-lines (str/trim out))) "/bin/quint-language-server"))))

(defn frame [m]
  (let [body (json/generate-string m)]
    (str "Content-Length: " (count (.getBytes body "UTF-8")) "\r\n\r\n" body)))

(defn read-message [^java.io.InputStream in]
  (let [header (loop [acc ""]
                 (cond (str/ends-with? acc "\r\n\r\n") acc
                       :else (let [c (.read in)] (when-not (neg? c) (recur (str acc (char c)))))))
        n (some->> header (re-find #"Content-Length: (\d+)") second parse-long)]
    (when n
      (let [buf (byte-array n)]
        (loop [off 0] (when (< off n) (recur (+ off (.read in buf off (- n off))))))
        (json/parse-string (String. buf "UTF-8") true)))))

(def tmp (fs/create-temp-dir {:prefix "quint-lsp-"}))
(def file (str (fs/path tmp "demo.qnt")))
(def uri (str "file://" file))
(def broken "module demo {\n  var x: int\n  pure def double(n: int): int = n * 2\n  action init = x' = double(1)\n  action step = x' = missing + 1\n}\n")
(def fixed (str/replace broken "missing + 1" "x + 1"))
(spit file broken)

(def proc (p/process [server "--stdio"] {:err :inherit}))
(def messages (atom []))
(future (loop [] (when-let [m (read-message (:out proc))] (swap! messages conj m) (recur))))
(defn send! [m] (doto ^java.io.OutputStream (:in proc) (.write (.getBytes (frame (assoc m :jsonrpc "2.0")) "UTF-8")) (.flush)))
(defn await! [pred]
  (loop [waited 0]
    (or (some pred @messages)
        (when (< waited 30000) (Thread/sleep 100) (recur (+ waited 100))))))
(defn reply [id] (await! #(when (= id (:id %)) %)))
(defn diagnostics [version]
  (await! #(when (and (= "textDocument/publishDiagnostics" (:method %)) (= version (get-in % [:params :version] version))
                      (= uri (get-in % [:params :uri])))
             (get-in % [:params :diagnostics]))))

(def failures (atom []))
(defn check [what ok] (println (if ok "ok  " "FAIL") what) (when-not ok (swap! failures conj what)))
(def position {:textDocument {:uri uri} :position {:line 3 :character 22}})

(send! {:id 1 :method "initialize" :params {:processId nil :rootUri (str "file://" tmp) :capabilities {}}})
(check "initialize" (reply 1))
(send! {:method "initialized" :params {}})
(send! {:method "textDocument/didOpen" :params {:textDocument {:uri uri :languageId "quint" :version 1 :text broken}}})
(check "a diagnostic names the missing name"
       (some #(str/includes? (:message %) "'missing' not found") (diagnostics 1)))
(reset! messages [])
(send! {:method "textDocument/didChange" :params {:textDocument {:uri uri :version 2} :contentChanges [{:text fixed}]}})
(check "the fixed file has no diagnostics" (= [] (await! #(when (= "textDocument/publishDiagnostics" (:method %)) (get-in % [:params :diagnostics])))))
(send! {:id 2 :method "textDocument/hover" :params position})
(check "hover shows the type" (str/includes? (str (get-in (reply 2) [:result :contents :value])) "double: (int) => int"))
(send! {:id 3 :method "textDocument/definition" :params position})
(check "go-to-definition finds the def" (= 2 (get-in (reply 3) [:result 0 :targetSelectionRange :start :line])))
(p/destroy-tree proc)
(fs/delete-tree tmp)
(System/exit (if (empty? @failures) 0 1))
