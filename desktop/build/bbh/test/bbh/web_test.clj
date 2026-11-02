(ns bbh.web-test
  (:require [babashka.fs :as fs]
            [bbh.inbox :as inbox]
            [bbh.super]
            [bbh.super.client]
            [bbh.transcript :as transcript]
            [bbh.web :as web]
            [bbh.web.conversation :as conversation]
            [bbh.web.markdown :as md]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

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

(deftest sessions-are-listed-with-a-readable-title
  (let [s (site)
        d (live! s "a" "/work/site")]
    (transcript/append! d {:role "user" :content "<skill name=\"x\">rules</skill>\n\nfix   the\nlogin"})
    (fs/create-dirs ((:dir s) "saved"))
    (is (= [{:id "a" :project "site" :busy false :title "fix the login"}]
           (second (get-json s "/api/agents"))))))

(deftest a-conversation-shows-prompts-replies-and-tool-calls
  (let [s (site)
        d (live! s "a" "/p")]
    (transcript/append! d {:role "user" :content "hello"})
    (transcript/append! d {:role "assistant" :content ""
                           :tool_calls [{:id "t1" :function {:name "clojure" :arguments "{\"code\":\"(+ 1 2)\"}"}}]})
    (transcript/append! d {:role "tool" :tool_call_id "t1" :content "=> 3"})
    (is (= [{:i 0 :role "user" :text "hello"}
            {:i 1 :role "assistant" :text "" :calls [{:id "t1" :name "clojure" :code "(+ 1 2)"}]}
            {:i 2 :role "tool" :text "=> 3" :call "t1"}]
           (:messages (second (get-json s "/api/agents/a/messages")))))))

(deftest a-rollover-joins-the-whole-conversation-and-pages-work
  (let [s (site)
        d (live! s "a" "/p")
        _ (dotimes [n 100] (transcript/append! d {:role "user" :content (str "m" n)}))
        rolled (transcript/continue! d "b")
        _ (transcript/append! rolled {:role "user" :content "m100"})
        texts (fn [query] (map :text (:messages (second (get-json s "/api/agents/a/messages" query)))))]
    (is (= 101 (:total (second (get-json s "/api/agents/a/messages")))))
    (is (= "m21" (first (texts nil))))
    (is (= "m100" (last (texts nil))))
    (is (= ["m99" "m100"] (texts "after=99")))
    (is (= ["m1" "m2" "m3"] (take 3 (texts "before=81"))))
    (is (= [] (texts "after=101")))))

(deftest a-sent-message-lands-in-the-mailbox-and-shows-as-pending-until-the-transcript-has-it
  (let [s (site)
        d (live! s "a" "/p")
        response (post-message s "a" "  hello from the phone " from-page)]
    (is (= 202 (:status response)))
    (is (= ["hello from the phone"] (map :text (inbox/pending d))))
    (is (= ["hello from the phone"] (map :text (:pending (second (get-json s "/api/agents/a/messages"))))))
    (transcript/append! d {:role "user" :content "hello from the phone"})
    (is (= [] (:pending (second (get-json s "/api/agents/a/messages")))))))

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

(deftest a-blank-message-is-refused
  (let [s (site)
        d (live! s "a" "/p")]
    (is (= 400 (:status (post-message s "a" "   " from-page))))
    (is (empty? (inbox/pending d)))))

(def ^:private jpeg-base64
  (.encodeToString (java.util.Base64/getEncoder) (byte-array [-1 -40 -1 -32 1 2 3])))

(defn- post-json [{:keys [app]} id body]
  (app {:uri (str "/api/agents/" id "/messages") :request-method :post
        :headers (merge owner-headers from-page)
        :body (java.io.ByteArrayInputStream. (.getBytes (json/generate-string body)))}))

(deftest a-sent-image-shows-as-a-picture-and-is-served-to-the-owner
  (let [{:keys [app] :as s} (site)
        d (live! s "a" "/p")
        response (post-json s "a" {:text "look" :images [jpeg-base64]})
        {:keys [text images id]} (json/parse-string (:body response) true)
        file (first images)
        fetch (fn [uri headers] (app {:uri uri :request-method :get :headers headers}))]
    (is (= 202 (:status response)))
    (is (= "look" text))
    (is (= (str id "-0.jpg") file))
    (is (= [["look" [file]]] (map (juxt :text :images) (:pending (second (get-json s "/api/agents/a/messages"))))))
    (transcript/append! d {:role "user" :content (:text (first (inbox/pending d)))})
    (is (= {:i 0 :role "user" :text "look" :images [file]}
           (first (:messages (second (get-json s "/api/agents/a/messages"))))))
    (let [{:keys [status headers body]} (fetch (str "/api/agents/a/images/" file) owner-headers)]
      (is (= 200 status))
      (is (= "image/jpeg" (headers "Content-Type")))
      (is (= [-1 -40 -1 -32 1 2 3] (vec (fs/read-all-bytes body)))))
    (is (= 403 (:status (fetch (str "/api/agents/a/images/" file) {}))))))

(deftest an-image-alone-is-a-message
  (let [s (site)
        d (live! s "a" "/p")]
    (is (= 202 (:status (post-json s "a" {:text "" :images [jpeg-base64]}))))
    (is (= 1 (count (inbox/pending d))))))

(deftest what-is-not-a-jpeg-is-refused
  (let [s (site)
        d (live! s "a" "/p")]
    (is (= 400 (:status (post-json s "a" {:images ["PGh0bWw+PC9odG1sPg=="]}))))
    (is (= 400 (:status (post-json s "a" {:images ["not base64!"]}))))
    (is (= 413 (:status (post-json s "a" {:images (repeat 9 jpeg-base64)}))))
    (is (empty? (inbox/pending d)))))

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

(deftest markdown-renders-the-shapes-agents-write
  (is (= [[:h3 "Title"]] (md/render "## Title")))
  (is (= [[:p "a " [:code "b"] " " [:strong "c"]]] (md/render "a `b` **c**")))
  (is (= [[:ul [:li "one"] [:li "two more"]]] (md/render "- one\n- two\n  more")))
  (is (= [[:ol [:li "one"]]] (md/render "1. one")))
  (is (= [[:pre [:code "x\n\ny"]]] (md/render "```clj\nx\n\ny\n```")))
  (is (= [[:div.table [:table [:thead [:tr [:th "a"] [:th "b"]]]
                       [:tbody [:tr [:td "1"] [:td [:code "2"]]]]]]]
         (md/render "| a | b |\n|---|---|\n| 1 | `2` |")))
  (is (= [[:p "one"] [:p "two"]] (md/render "one\n\ntwo"))))

(deftest a-fence-goes-to-the-highlighter-with-its-language
  (is (= [[:pre [:code [:b "clj"] [:i "(+ 1 2)"]]]]
         (md/render "```clj\n(+ 1 2)\n```" (fn [lang code] [[:b lang] [:i code]]))))
  (is (= [[:pre [:code [:b ""] [:i "plain"]]]]
         (md/render "```\nplain\n```" (fn [lang code] [[:b lang] [:i code]])))))

(deftest markdown-never-makes-a-script-link-or-raw-html
  (is (= [[:p "[x](javascript:alert(1))"]] (md/render "[x](javascript:alert(1))")))
  (is (= [[:p "<img src=x onerror=alert(1)>"]] (md/render "<img src=x onerror=alert(1)>")))
  (is (= [[:p [:a {:href "https://a.example/b" :target "_blank" :rel "noopener noreferrer"} "b"]]]
         (md/render "[b](https://a.example/b)"))))

;; ---- the inbox over /api/super -------------------------------------------

(defn- with-super
  "A site whose inbox is a controller in a temporary directory, with one thread,
  a Worker and an open ask. Calls F with the site, the controller and the
  Worker's conversation root."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-web-super"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-web-super-conv"}))
        c (bbh.super/start! {:dir dir :conversations conversations :scan-ms 10})
        s (assoc (site) :app (web/handler conversations (constantly "owner@example.com") dir))
        call (fn [req] (bbh.super.client/call! dir req))]
    (try (call {:command :thread! :args {:title "Herdr key for the thread picker"}})
         (let [token (:token (:ok (call {:command :attach-worker! :args {:thread "T1" :conversation "w1"}})))]
           (call {:token token :command :ask! :args {:kind :question :text "Which key should open the inbox pane?"
                                                     :options [{:id "t" :label "Alt+Space t"} {:id "a" :label "Alt+Space a"}]
                                                     :recommended "t" :stopped true}}))
         (f s c (str (fs/path conversations "w1")))
         (finally (bbh.super/stop! c)))))

(defn- post-super [{:keys [app]} uri body headers]
  (let [{:keys [status] :as r} (app {:uri uri :request-method :post :headers (merge owner-headers headers)
                                     :body (java.io.ByteArrayInputStream. (.getBytes (json/generate-string body)))})]
    [status (json/parse-string (:body r) true)]))

(deftest the-inbox-lists-sections-with-the-badge-and-a-thread-with-its-asks
  (with-super
    (fn [s _ _]
      (let [[status {:keys [badge sections]}] (get-json s "/api/super/threads")]
        (is (= 200 status))
        (is (= 1 badge))
        (is (= ["stopped"] (map :section sections)))
        (is (= "Which key should open the inbox pane?" (get-in sections [0 :threads 0 :ask :text]))))
      (let [[status t] (get-json s "/api/super/threads/T1")]
        (is (= 200 status))
        (is (= ["open"] (map :state (:asks t))))
        (is (= ["thread-created" "worker-attached" "token-issued" "ask-opened"] (map :kind (:events t)))))
      (is (= 404 (first (get-json s "/api/super/threads/T9")))))))

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

(deftest an-approval-from-the-page-is-accepted-once-and-a-stale-one-is-a-conflict
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-web-ap"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-web-apconv"}))
        c (bbh.super/start! {:dir dir :conversations conversations :scan-ms 10})
        s (assoc (site) :app (web/handler conversations (constantly "owner@example.com") dir))
        call (fn [req] (bbh.super.client/call! dir req))]
    (try
      (call {:command :thread! :args {:title "review"}})
      (let [token (:token (:ok (call {:command :attach-worker! :args {:thread "T1" :conversation "w1"}})))
            a (:ok (call {:token token :command :ask! :args {:kind :review :text "Ship?" :revision "c068de11"}}))
            sha (:evidence-sha (first (:asks (second (get-json s "/api/super/threads/T1")))))
            body {:id "ap-1" :ask a :revision "c068de11" :evidence-sha (str sha)}]
        (is (= 400 (first (post-super s "/api/super/threads/T1/approve" (dissoc body :revision) from-page))))
        (is (= 409 (first (post-super s "/api/super/threads/T1/approve" (assoc body :id "ap-0" :revision "deadbeef") from-page))))
        (is (= [202 "ap-1"] (post-super s "/api/super/threads/T1/approve" body from-page)))
        (is (= 409 (first (post-super s "/api/super/threads/T1/approve" (assoc body :id "ap-2") from-page))) "approved once"))
      (finally (bbh.super/stop! c)))))
