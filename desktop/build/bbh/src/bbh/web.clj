(ns bbh.web
  "The phone's window on live sessions. It serves the page in src/bbh/web/ on
  loopback and Tailscale publishes it to the tailnet. Sending here reaches an
  agent with a shell, so every request must come through Tailscale from this
  node's owner, and a POST must come from the page itself."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.inbox :as inbox]
            [bbh.super.client :as super]
            [bbh.transcript :as transcript]
            [bbh.version :as version]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]))

(def ^:private port 8766)
(def ^:private https-port 8443)
(def ^:private page-size 80)
(def ^:private max-text 100000)
(def ^:private max-images 8)
(def ^:private max-body (* 64 1024 1024))

(defn prompt-text
  "The person's words in a saved user message, without skill text or bbh notes."
  [content]
  (let [after-skills (last (str/split content #"</skill>" -1))]
    (str/trim (str/replace-first (str/trim after-skills) #"^\[bbh\] .*\n+" ""))))

(defn- view
  "Saved message M at index I, as the page shows it."
  [i {:keys [role content tool_calls tool_call_id]}]
  (let [text (str content)]
    (case role
      "user" (assoc (inbox/parts (prompt-text text)) :i i :role role)
      "tool" {:i i :role role :text text :call tool_call_id}
      {:i i :role role :text text
       :calls (mapv (fn [{:keys [id function]}]
                      {:id id :name (:name function)
                       :code (let [args (:arguments function)]
                               (or (try (:code (json/parse-string args true)) (catch Exception _ nil)) args))})
                    tool_calls)})))

(def ^:private cache
  "Each transcript's parsed messages by path. A transcript only grows, so its
  size says whether the entry is current."
  (atom {}))

(defn- messages-of [dir]
  (let [f (fs/path dir "messages.jsonl")
        size (if (fs/exists? f) (fs/size f) 0)
        entry (get @cache dir)]
    (if (= size (:size entry))
      (:messages entry)
      (try
        (let [messages (transcript/load-messages dir)]
          (swap! cache assoc dir {:size size :messages messages})
          messages)
        (catch Exception _
          (:messages entry))))))

(defn- conversation [root]
  (into [] (mapcat messages-of) (transcript/chain root)))

(defn- title [messages]
  (let [text (some #(when (= "user" (:role %))
                       (let [prompt (prompt-text (str (:content %)))]
                         (when-not (str/starts-with? prompt "[bbh]") (:text (inbox/parts prompt)))))
                    messages)
        line (str/replace (str text) #"\s+" " ")]
    (subs line 0 (min 80 (count line)))))

(defn agents
  "The live sessions among the conversations in ROOT, oldest first."
  [root]
  (vec (for [f (sort (map str (fs/glob root "*/agent.json")))
             :let [dir (str (fs/parent f))
                   {:keys [project busy] :as live} (inbox/agent dir)]
             :when live]
         {:id (str (fs/file-name dir)) :project (str (fs/file-name project)) :busy (boolean busy)
          :title (title (messages-of dir))})))

(defn- mail-view
  "Mailbox message M as the page shows it."
  [{:keys [id text]}]
  (assoc (inbox/parts text) :id id))

(defn- unseen
  "The mailbox messages not yet in MESSAGES. A message stays in the mailbox until
  its turn ends, by which time the transcript already shows it."
  [messages pending]
  (let [shown (set (for [m (take-last 50 messages) :when (= "user" (:role m))]
                     (prompt-text (str (:content m)))))]
    (mapv mail-view (remove #(shown (str/trim (:text %))) pending))))

(defn page
  "Conversation ROOT from index AFTER on, or else the page before index BEFORE,
  or else its last page."
  [root {:keys [after before]}]
  (let [messages (conversation root)
        total (count messages)
        [start end] (cond after [(min after total) total]
                          before [(max 0 (- before page-size)) (min before total)]
                          :else [(max 0 (- total page-size)) total])]
    {:total total
     :busy (boolean (:busy (inbox/agent root)))
     :pending (unseen messages (inbox/pending root))
     :messages (mapv (fn [i] (view i (nth messages i))) (range start end))}))

(defn- owner
  "This node's owner, asked once Tailscale can answer."
  []
  (let [status (json/parse-string (:out (p/shell {:out :string :err :string :continue true}
                                                 "tailscale" "status" "--json")) true)]
    (get-in status [:User (keyword (str (get-in status [:Self :UserID]))) :LoginName])))

(defn- json-response [status body]
  {:status status :headers {"Content-Type" "application/json"
                            "Cache-Control" "no-store"}
   :body (json/generate-string body)})

(def ^:private assets
  {"/" ["index.html" "text/html"]
   "/app.cljs" ["app.cljs" "text/plain"]
   "/conversation.cljc" ["conversation.cljc" "text/plain"]
   "/markdown.cljc" ["markdown.cljc" "text/plain"]
   "/style.css" ["style.css" "text/css"]
   "/highlights.js" ["highlights.js" "text/javascript"]
   "/scittle.js" ["scittle.js" "text/javascript"]
   "/scittle.replicant.js" ["scittle.replicant.js" "text/javascript"]})

(def ^:private csp
  (str "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
       "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"))

(defn- asset [uri]
  (when-let [[file content-type] (assets uri)]
    {:status 200 :headers {"Content-Type" (str content-type "; charset=utf-8")
                           "Content-Security-Policy" csp
                           "Cache-Control" (if (str/starts-with? file "scittle") "max-age=604800" "no-cache")}
     :body (slurp (io/resource (str "bbh/web/" file)))}))

(defn- same-origin? [{:keys [headers]}]
  (and (= (headers "origin") (str "https://" (headers "host")))
       (str/starts-with? (str (headers "content-type")) "application/json")))

(defn- query [{:keys [query-string]}]
  (into {} (for [[_ k v] (re-seq #"(after|before)=(\d+)" (str query-string))]
             [(keyword k) (parse-long v)])))

(defn- jpeg
  "The bytes of the base64 JPEG ENCODED, or nil when it is not one."
  [encoded]
  (let [image (try (.decode (java.util.Base64/getDecoder) (str encoded))
                   (catch IllegalArgumentException _ nil))]
    (when (and image (< 3 (alength image))
               (= [-1 -40 -1] [(aget image 0) (aget image 1) (aget image 2)]))
      image)))

(defn- send-message [root request]
  (let [{:keys [text images]} (json/parse-string (slurp (:body request)) true)
        text (str/trim (str text))
        photos (mapv jpeg images)]
    (cond (not (same-origin? request)) (json-response 403 {:error "Cross-origin request"})
          (and (str/blank? text) (empty? photos)) (json-response 400 {:error "Empty message"})
          (> (count text) max-text) (json-response 413 {:error "Message too long"})
          (> (count photos) max-images) (json-response 413 {:error "Too many images"})
          (some nil? photos) (json-response 400 {:error "Not a JPEG image"})
          :else (json-response 202 (mail-view (inbox/send! root text photos))))))

(defn- image [root file]
  (let [f (fs/file root "images" file)]
    (if (and (re-matches #"[0-9a-f-]+\.jpg" file) (fs/exists? f))
      {:status 200 :body f
       :headers {"Content-Type" "image/jpeg" "Cache-Control" "private, max-age=604800"}}
      (json-response 404 {:error "No such image"}))))

(defn- api [conversations {:keys [uri request-method] :as request}]
  (let [[_ id file] (re-matches #"/api/agents/([^/]+)/(?:messages|images/([^/]+))" uri)
        live (agents conversations)
        root (when (some #(= id (:id %)) live) (str (fs/path conversations id)))]
    (cond (and (= uri "/api/agents") (= :get request-method)) (json-response 200 live)
          (nil? root) (json-response 404 {:error "No such session"})
          file (if (= :get request-method) (image root file) (json-response 405 {:error "Method not allowed"}))
          (= :get request-method) (json-response 200 (page root (query request)))
          (= :post request-method) (send-message root request)
          :else (json-response 405 {:error "Method not allowed"}))))

(defn- when-super-up
  "(F) as the response, or 503 when the controller is down."
  [f]
  (try (f)
       (catch java.io.IOException _ (json-response 503 {:error "bbh super is not running"}))
       (catch clojure.lang.ExceptionInfo e
         (if (= :down (:error (ex-data e)))
           (json-response 503 {:error "bbh super is not running"})
           (json-response 400 {:error (ex-message e)})))))

(defn- super-call
  "REQUEST to the controller under DIR as a JSON response: 200 or 202 with the
  result, 409 with the current asks when a Send is stale, 503 when the
  controller is down."
  [dir request ok-status]
  (when-super-up
   #(let [{:keys [ok error message data]} (super/call! dir request)]
      (cond (nil? error) (json-response ok-status ok)
            (= :stale error) (json-response 409 {:error "stale" :current (:current data) :asks (:asks data)})
            :else (json-response 400 {:error (str (name error) (when message (str ": " message)))})))))

(defn- picks?
  "Whether PICKS from JSON is a map of ask id to option id."
  [picks]
  (and (map? picks) (every? (fn [[k v]] (and (keyword? k) (string? v))) picks)))

(defn- cursors [{:keys [query-string]}]
  (let [[_ q] (re-find #"(?:^|&)cursors=([^&]*)" (str query-string))]
    (into {} (for [[_ k v] (re-seq #"(T\d+):(\d+)" (str q))] [k (parse-long v)]))))

(defn- shown
  "The order the page last showed, from shown=T1:stopped,T3:working, or nil."
  [{:keys [query-string]}]
  (when-let [[_ q] (re-find #"(?:^|&)shown=([^&]*)" (str query-string))]
    (vec (for [[_ k v] (re-seq #"(T\d+):([a-z-]+)" q)] [k (keyword v)]))))

(defn- super-api
  "/api/super: the inbox for the phone, over the controller under DIR."
  [dir {:keys [uri request-method] :as request}]
  (let [[_ id verb] (re-matches #"/api/super/threads/(T\d+)(?:/(reply|approve|dismiss|archive|draft|discard-draft))?" uri)
        body (when (= :post request-method)
               (try (json/parse-string (slurp (:body request)) true) (catch Exception _ ::bad)))]
    (cond (and (= uri "/api/super/threads") (= :get request-method))
          (super-call dir {:command :sections :args (cond-> {:cursors (cursors request)} (shown request) (assoc :shown (shown request)))} 200)
          (= uri "/api/super/say")
          (cond (not= :post request-method) (json-response 405 {:error "Method not allowed"})
                (not (same-origin? request)) (json-response 403 {:error "Cross-origin request"})
                (= ::bad body) (json-response 400 {:error "Bad JSON"})
                (str/blank? (str (:text body))) (json-response 400 {:error "Empty message"})
                (> (count (str (:text body))) max-text) (json-response 413 {:error "Message too long"})
                :else (super-call dir {:command :say! :id (:id body) :args {:text (str/trim (str (:text body))) :origin :phone}} 202))
          (nil? id) (json-response 404 {:error "Not found"})
          (and (= :get request-method) (nil? verb))
          (when-super-up
           #(let [thread (super/call! dir {:command :thread :args {:id id}})
                  events (super/call! dir {:command :events :args {:thread id}})]
              (cond (nil? (:ok thread)) (json-response 404 {:error "No such thread"})
                    (:error events) (json-response 400 {:error (str (name (:error events)))})
                    :else (json-response 200 (assoc (:ok thread) :events (:ok events))))))
          (not= :post request-method) (json-response 405 {:error "Method not allowed"})
          (not (same-origin? request)) (json-response 403 {:error "Cross-origin request"})
          (= ::bad body) (json-response 400 {:error "Bad JSON"})
          (= "reply" verb)
          (let [{:keys [picks note asks-version]} body]
            (cond (> (count (str note)) max-text) (json-response 413 {:error "Note too long"})
                  (not (string? (:id body))) (json-response 400 {:error "A reply needs an id"})
                  (not (or (nil? picks) (picks? picks))) (json-response 400 {:error "Picks map an ask id to an option id"})
                  :else (super-call dir {:command :reply! :id (:id body)
                                         :args {:thread id :asks-version asks-version
                                                :picks (into {} (map (fn [[k v]] [(name k) v])) picks)
                                                :note (str/trim (str note))}}
                                    202)))
          ;; A draft is the same whatever retries it, so it needs no id.
          (= "draft" verb)
          (let [{:keys [ask option note]} body]
            (cond (> (count (str note)) max-text) (json-response 413 {:error "Note too long"})
                  (and (some? ask) (not (and (string? ask) (string? option)))) (json-response 400 {:error "A pick names its ask and option"})
                  (and (nil? ask) (not (string? note))) (json-response 400 {:error "A draft change is a pick or a note"})
                  :else (super-call dir {:command :draft! :args (cond-> {:thread id} ask (assoc :pick [ask option]) (string? note) (assoc :note note))} 200)))
          (= "approve" verb)
          (if (and (string? (:id body)) (every? (comp string? body) [:ask :revision :evidence-sha]))
            (super-call dir {:command :approve! :id (:id body)
                             :args {:thread id :ask (:ask body) :revision (:revision body) :evidence-sha (:evidence-sha body)}}
                        202)
            (json-response 400 {:error "An approval names its id, ask, revision and evidence-sha"}))
          ;; The id makes a retry the same command, so it is required here as for a reply.
          (not (string? (:id body))) (json-response 400 {:error (str "A " verb " needs an id")})
          :else (super-call dir {:command (keyword (str verb "!")) :id (:id body) :args {:thread id}} 202))))

(defn handler
  "The app for the conversations under CONVERSATIONS, open to tailnet user OWNER,
  a function so that a slow Tailscale start does not stick. SUPER-DIR is the
  controller's directory for the inbox."
  ([conversations owner] (handler conversations owner (super/super-dir)))
  ([conversations owner super-dir]
  (fn [{:keys [uri headers] :as request}]
    (let [login (headers "tailscale-user-login")]
      (cond (or (nil? login) (not= login (owner))) (json-response 403 {:error "Tailnet owner only"})
            (str/starts-with? uri "/api/super/") (super-api super-dir request)
            (str/starts-with? uri "/api/") (api conversations request)
            :else (or (asset uri) (json-response 404 {:error "Not found"})))))))

(defn- remembered
  "F's first non-nil answer, kept."
  [f]
  (let [answer (atom nil)]
    (fn [] (or @answer (reset! answer (f))))))

(defn- counted
  "HANDLER that counts the requests it is answering in IN-FLIGHT."
  [handler in-flight]
  (fn [request]
    (swap! in-flight inc)
    (try (handler request) (finally (swap! in-flight dec)))))

(defn- publish!
  "Have Tailscale serve loopback PORT to the tailnet over HTTPS."
  [port]
  (let [{:keys [exit err]} (p/shell {:out :string :err :string :continue true}
                                    "tailscale" "serve" "--bg" (str "--https=" https-port)
                                    (str "http://127.0.0.1:" port))]
    (when-not (zero? exit)
      (binding [*out* *err*] (println "tailscale serve:" err)))))

(defn serve!
  "Serve the site for the conversations under CONVERSATIONS, and the inbox of
  the controller in SUPER-DIR, on loopback PORT, published to the tailnet when
  PUBLISH?. Returns {:stop :in-flight}, or nil when PORT is taken."
  [{:keys [conversations super-dir publish?] :as opts}]
  (let [in-flight (atom 0)
        at (or (:port opts) port)
        stop (try (server/run-server (counted (handler conversations (remembered owner) super-dir) in-flight)
                                     {:ip "127.0.0.1" :port at :max-body max-body})
                  (catch java.net.BindException _ nil))]
    (when stop
      (when publish? (publish! at))
      {:stop stop :in-flight in-flight})))

(defn -main
  "Serve the site for the conversations under DATA-DIR on its own, for working
  on it. The controller serves it otherwise. Exits once the bbh source changed
  and no request is being answered."
  [data-dir]
  (let [boot (version/fingerprint)
        {:keys [stop in-flight]} (or (serve! {:conversations (str (fs/path data-dir "conversations"))
                                              :super-dir (super/super-dir) :publish? true})
                                     (throw (ex-info (str "bbh web: port " port " is taken") {})))]
    (version/record! data-dir "web" boot)
    (println "bbh web on port" port)
    (version/follow! {:boot boot :idle? #(zero? @in-flight)
                      :exit! (fn [why]
                               (println "bbh web: exiting because" why)
                               (stop :timeout 5000)
                               (System/exit 0))}))
  @(promise))
