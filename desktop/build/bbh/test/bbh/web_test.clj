(ns bbh.web-test
  (:require [bbh.hermetic :as hermetic]
            [babashka.fs :as fs]
            [bbh.inbox :as inbox]
            [bbh.super]
            [bbh.super.client]
            [bbh.web :as web]
            [bbh.web.conversation :as conversation]
            [bbh.web.markdown :as md]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(defn- wait-for [pred]
  (loop [n 0]
    (cond (pred) true
          (> n 300) false
          :else (do (Thread/sleep 10) (recur (inc n))))))

(def ^:private owner-headers
  {"tailscale-user-login" "owner@example.com"
   "host" "box.example.ts.net:8443"})

(defn- site []
  (let [root (str (fs/create-temp-dir {:prefix "bbh-web"}))]
    {:root root
     :app (web/handler root (constantly "owner@example.com"))
     :dir #(str (fs/path root %))}))

(defn- live! [{:keys [dir]} id project]
  (let [d (dir id)]
    (fs/create-dirs d)
    (spit (str (fs/path d "agent.json"))
          (json/generate-string {:pid (.pid (java.lang.ProcessHandle/current)) :project project :busy false}))
    d))

(defn- get-json [{:keys [app]} uri & [query]]
  (let [{:keys [status body]} (app {:uri uri :request-method :get :headers owner-headers :query-string query})]
    [status (json/parse-string body true)]))

(defn- post-message [{:keys [app]} id text headers]
  (app {:uri (str "/api/agents/" id "/messages") :request-method :post
        :headers (merge owner-headers headers)
        :body (java.io.ByteArrayInputStream. (.getBytes (json/generate-string {:text text})))}))

(def ^:private from-page
  {"origin" "https://box.example.ts.net:8443" "content-type" "application/json"})

(deftest only-the-owner-through-tailscale-gets-in
  (let [{:keys [app] :as s} (site)]
    (live! s "a" "/work/site")
    (is (= 403 (:status (app {:uri "/api/agents" :request-method :get :headers {}}))))
    (is (= 403 (:status (app {:uri "/" :request-method :get
                              :headers {"tailscale-user-login" "guest@example.com"}}))))
    (is (= 200 (first (get-json s "/api/agents"))))))

(deftest a-post-from-another-site-is-refused
  (let [s (site)
        d (live! s "a" "/p")]
    (is (= 403 (:status (post-message s "a" "rm -rf" {"origin" "https://evil.example" "content-type" "application/json"}))))
    (is (= 403 (:status (post-message s "a" "rm -rf" {"content-type" "application/json"}))))
    (is (= 403 (:status (post-message s "a" "rm -rf" (assoc from-page "content-type" "text/plain")))))
    (is (empty? (inbox/pending d)))))

(deftest an-id-that-is-not-a-live-session-is-refused
  (let [s (site)]
    (live! s "a" "/p")
    (fs/create-dirs ((:dir s) "saved"))
    (is (= 404 (:status (post-message s "saved" "hi" from-page))))
    (is (= 404 (:status (post-message s ".." "hi" from-page))))
    (is (= 404 (first (get-json s "/api/agents/saved/messages"))))))

(deftest an-image-name-cannot-leave-the-conversation
  (let [{:keys [app] :as s} (site)]
    (live! s "a" "/p")
    (is (= 404 (:status (app {:uri "/api/agents/a/images/..%2Fagent.json" :request-method :get :headers owner-headers}))))
    (is (= 404 (:status (app {:uri "/api/agents/a/images/missing.jpg" :request-method :get :headers owner-headers}))))))

(deftest the-page-loads-with-a-content-security-policy
  (let [{:keys [app]} (site)
        {:keys [status headers body]} (app {:uri "/" :request-method :get :headers owner-headers})]
    (is (= 200 status))
    (is (str/includes? body "app.cljs"))
    (is (str/includes? (headers "Content-Security-Policy") "default-src 'none'"))))

(deftest pages-merge-into-one-ordered-conversation
  (let [m (fn [& is] (mapv (fn [i] {:i i}) is))
        merged (fn [held page] (map :i (:messages (conversation/merge-page {:messages held} {:messages page}))))]
    (testing "the first page" (is (= [5 6] (merged nil (m 5 6)))))
    (testing "newer" (is (= [5 6 7] (merged (m 5 6) (m 7)))))
    (testing "older" (is (= [3 4 5 6] (merged (m 5 6) (m 3 4)))))
    (testing "a racing poll" (is (= [5 6 7] (merged (m 5 6) (m 6 7)))))
    (testing "nothing new" (is (= [5 6] (merged (m 5 6) []))))))

(deftest markdown-never-makes-a-script-link-or-raw-html
  (is (= [[:p "[x](javascript:alert(1))"]] (md/render "[x](javascript:alert(1))")))
  (is (= [[:p "<img src=x onerror=alert(1)>"]] (md/render "<img src=x onerror=alert(1)>")))
  (is (= [[:p [:a {:href "https://a.example/b" :target "_blank" :rel "noopener noreferrer"} "b"]]]
         (md/render "[b](https://a.example/b)"))))

;; ---- the inbox over /api/super -------------------------------------------

(defn- start-super
  "A controller in a temporary directory whose launch makes plain directories
  and keeps the open! spec instead of opening Emacs."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-web-super"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-web-super-conv"}))
        opened (atom nil)]
    (assoc (bbh.super/start! {:dir dir :conversations conversations :scan-ms 10
                              :launch {:workspace! (fn [_ w] (str (fs/create-dirs (fs/path dir "ws" (:thread w)))))
                                       :open! (fn [spec] (reset! opened spec) "buf")
                                       :retire! (constantly nil) :release! (constantly nil)}})
           ::opened opened)))

(defn- run-worker
  "Start a Worker on T1 of controller C and take its brief, as its session
  would. Returns its token."
  [c]
  (bbh.super.client/call! (:dir c) {:command :start-worker! :args {:thread "T1" :workflow :investigation :brief "look" :repo (str (fs/home))}})
  (let [mailbox (fs/path (:conversations c) "super-W1" "mailbox")]
    (wait-for #(seq (fs/glob mailbox "*.json")))
    (doseq [f (fs/glob mailbox "*.json")] (fs/delete f))
    (wait-for #(= :done (get-in @(:state c) [:outbox "o1" :state])))
    (:token @(::opened c))))

(defn- with-super
  "A site whose inbox is a controller in a temporary directory, with one thread,
  a Worker and an open ask. Calls F with the site, the controller and the
  Worker's conversation root."
  [f]
  (let [c (start-super)
        dir (:dir c)
        s (assoc (site) :app (web/handler (:conversations c) (constantly "owner@example.com") dir))
        call (fn [req] (bbh.super.client/call! dir req))]
    (try (call {:command :thread! :args {:title "Herdr key for the thread picker"}})
         (let [token (run-worker c)]
           (call {:token token :command :ask! :args {:kind :question :text "Which key should open the inbox pane?"
                                                     :options [{:id "t" :label "Alt+Space t"} {:id "a" :label "Alt+Space a"}]
                                                     :recommended "t" :stopped true}}))
         (f s c (str (fs/path (:conversations c) "super-W1")))
         (finally (bbh.super/stop! c)))))

(defn- post-super [{:keys [app]} uri body headers]
  (let [{:keys [status] :as r} (app {:uri uri :request-method :post :headers (merge owner-headers headers)
                                     :body (java.io.ByteArrayInputStream. (.getBytes (json/generate-string body)))})]
    [status (json/parse-string (:body r) true)]))

(deftest a-send-from-the-page-lands-in-the-workers-mailbox-once-and-a-stale-one-is-a-conflict
  (with-super
    (fn [s c root]
      (let [[_ t] (get-json s "/api/super/threads/T1")
            reply {:id "p-1" :asks-version (:asks-version t) :picks {:a1 "t"} :note ""}]
        (is (= 403 (first (post-super s "/api/super/threads/T1/reply" reply {}))) "a cross-origin POST is refused")
        (is (= [202 "p-1"] (post-super s "/api/super/threads/T1/reply" reply from-page)))
        (is (= [202 "p-1"] (post-super s "/api/super/threads/T1/reply" reply from-page)) "the retry is the same reply")
        (is (wait-for #(seq (fs/glob root "mailbox/*.json"))))
        (Thread/sleep 50)
        (is (= 1 (count (fs/glob root "mailbox/*.json"))))
        (let [[status body] (post-super s "/api/super/threads/T1/reply" (assoc reply :id "p-2") from-page)]
          (is (= 409 status))
          (is (= "stale" (:error body)))
          (is (= [] (:asks body)) "nothing is open any more"))
        (is (= [202 "T1"] (post-super s "/api/super/threads/T1/archive" {:id "a-1"} from-page)))
        (is (= ["archived"] (map :section (:sections (second (get-json s "/api/super/threads"))))))))))

(deftest the-page-and-the-inbox-share-one-draft-per-thread
  (with-super
    (fn [s c _]
      (let [dir (:dir c)
            call (fn [req] (bbh.super.client/call! dir req))]
        (is (= [200 {:picks {:a1 "t"} :note "" :asks-version (:asks-version (second (get-json s "/api/super/threads/T1")))}]
               (post-super s "/api/super/threads/T1/draft" {:ask "a1" :option "t"} from-page)))
        (is (= 400 (first (post-super s "/api/super/threads/T1/draft" {:ask "a1" :option "zz"} from-page))) "an option the ask lacks")
        (is (= 400 (first (post-super s "/api/super/threads/T1/draft" {} from-page))) "neither a pick nor a note")
        (is (= 403 (first (post-super s "/api/super/threads/T1/draft" {:note "x"} {}))) "a cross-origin POST is refused")
        ;; The Inbox in Emacs adds a note through the controller, and the page reads both.
        (call {:command :draft! :args {:thread "T1" :note "from emacs"}})
        (is (= {:picks {:a1 "t"} :note "from emacs"} (select-keys (:draft (second (get-json s "/api/super/threads/T1"))) [:picks :note])))
        (is (= 202 (first (post-super s "/api/super/threads/T1/discard-draft" {:id "dd-1"} from-page))))
        (is (nil? (:draft (second (get-json s "/api/super/threads/T1")))))))))

(deftest an-approval-from-the-page-is-accepted-once-and-a-stale-one-is-a-conflict
  (let [c (start-super)
        dir (:dir c)
        s (assoc (site) :app (web/handler (:conversations c) (constantly "owner@example.com") dir))
        call (fn [req] (bbh.super.client/call! dir req))]
    (try
      (call {:command :thread! :args {:title "review"}})
      (let [token (run-worker c)
            a (:ok (call {:token token :command :ask! :args {:kind :review :text "Ship?" :revision "c068de11"}}))
            sha (:evidence-sha (first (:asks (second (get-json s "/api/super/threads/T1")))))
            body {:id "ap-1" :ask a :revision "c068de11" :evidence-sha (str sha)}]
        (is (= 400 (first (post-super s "/api/super/threads/T1/approve" (dissoc body :revision) from-page))))
        (is (= 409 (first (post-super s "/api/super/threads/T1/approve" (assoc body :id "ap-0" :revision "deadbeef") from-page))))
        (is (= [202 "ap-1"] (post-super s "/api/super/threads/T1/approve" body from-page)))
        (is (= 409 (first (post-super s "/api/super/threads/T1/approve" (assoc body :id "ap-2") from-page))) "approved once"))
      (finally (bbh.super/stop! c)))))

(deftest the-page-and-the-inbox-share-what-has-been-read
  (with-super
    (fn [s c _]
      (bbh.super.client/call! (:dir c) {:command :thread! :args {:title "another"}})
      (let [section #(some (fn [{:keys [section threads]}] (when (some (comp #{"T2"} :id) threads) section))
                           (:sections (second (get-json s "/api/super/threads"))))
            unread (fn [id] (:unread (first (filter #(= id (:id %)) (mapcat :threads (:sections (second (get-json s "/api/super/threads"))))))))]
        (is (= "new" (section)))
        (is (true? (unread "T2"))
            "a row says it is unread, so no view works it out")
        (is (= 403 (first (post-super s "/api/super/threads/T2/read" {} {}))) "a cross-origin POST is refused")
        (is (= [200 ["T2"]] (post-super s "/api/super/threads/T2/read" {} from-page)))
        (is (= "quiet" (section)) "opening it on the page marks it read")
        (is (false? (unread "T2")))
        (bbh.super.client/call! (:dir c) {:command :read! :args {:threads ["T2"] :read false}})
        (is (= "new" (section)) "unread in Emacs shows on the page")))))

(deftest the-inbox-holds-the-order-the-page-showed
  (with-super
    (fn [s c _]
      (let [[_ first-read] (get-json s "/api/super/threads")]
        (is (= [["T1" "stopped"]] (:order first-read)))
        (bbh.super.client/call! (:dir c) {:command :thread! :args {:title "another"}})
        (let [[_ held] (get-json s "/api/super/threads" "shown=T1:stopped")]
          (is (= 1 (:fresh held)))
          (is (= ["T1"] (mapv :id (mapcat :threads (:sections held))))))
        (is (= 2 (count (mapcat :threads (:sections (second (get-json s "/api/super/threads"))))))
            "without shown the page gets everything")))))

(deftest the-page-hears-each-change-of-the-controller-on-its-stream
  (let [c (start-super)
        port (+ 20000 (rand-int 20000))
        {:keys [stop]} (web/serve! {:conversations (:conversations c) :super-dir (:dir c) :port port
                                    :owner (constantly "owner@example.com")})
        open (fn [login]
               (let [sock (doto (java.net.Socket. "127.0.0.1" (int port)) (.setSoTimeout 5000))
                     out (.getOutputStream sock)]
                 (.write out (.getBytes (str "GET /api/super/stream HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                                             "tailscale-user-login: " login "\r\n\r\n")))
                 (.flush out)
                 (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock)))))
        head (fn [lines] (vec (take-while seq (repeatedly #(.readLine lines)))))]
    (try
      (is (str/includes? (first (head (open "guest@example.com"))) "403") "only the owner may listen")
      (let [lines (open "owner@example.com")
            h (head lines)
            body-lines (fn [] (remove #(re-matches #"[0-9a-f]+" %) (repeatedly #(.readLine lines))))]
        (is (str/includes? (first h) "200"))
        (is (some #(re-find #"(?i)content-type: text/event-stream" %) h))
        (is (= "retry: 3000" (first (body-lines))))
        (bbh.super.client/call! (:dir c) {:command :thread! :args {:title "heard"}})
        (is (= "event: changed" (first (remove str/blank? (body-lines)))) "a new thread reaches the page without a reload"))
      (finally (stop) (bbh.super/stop! c)))))

(deftest bad-inbox-requests-are-refused-and-a-stopped-controller-is-503
  (with-super
    (fn [s c _]
      (doseq [picks [[1 2] "a1" {:a1 3} {:a1 nil}]]
        (is (= 400 (first (post-super s "/api/super/threads/T1/reply" {:id "p-9" :asks-version 1 :picks picks} from-page)))
            (pr-str picks)))
      (doseq [verb ["dismiss" "archive"] id [nil 7]]
        (is (= 400 (first (post-super s (str "/api/super/threads/T1/" verb) (cond-> {} id (assoc :id id)) from-page)))
            (str verb " " (pr-str id))))
      (bbh.super/stop! c)
      (is (= 503 (first (get-json s "/api/super/threads/T1"))) "opening a thread with the controller down"))))
