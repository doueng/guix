(ns bbh.image
  "Image generation for Workers, the CLI and Emacs. The default provider is the
  ChatGPT subscription: the Codex Responses API's image_generation tool, under
  Pi's openai-codex login, so an image spends plan quota rather than money. Each
  batch lands in its own folder under `image-dir` with the images, one EDN
  sidecar per image (prompt, parameters, revised prompt, size), a gallery.html
  tagged for the phone's Docs and view, and an index.org that Emacs shows
  inline. A batch whose estimate passes `confirm-usd` or `confirm-count` is
  refused unless the call says :confirmed true."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bbh.auth :as auth]
            [bbh.explainer :as explainer]
            [bbh.sysenv :as sysenv]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.time LocalDateTime ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Base64]))

(defn image-dir
  "Where batches go: BBH_IMAGE_DIR, else ~/Pictures/bbh-images."
  []
  (str (fs/expand-home (or (sysenv/getenv "BBH_IMAGE_DIR") "~/Pictures/bbh-images"))))

(def sizes #{"auto" "1024x1024" "1536x1024" "1024x1536"})
(def qualities #{"auto" "low" "medium" "high"})
(def formats #{"png" "jpeg" "webp"})
(def backgrounds #{"auto" "opaque" "transparent"})

(def price-usd
  "OpenAI's API list price per 1024x1024 gpt-image image by quality, late 2025.
  On the subscription nothing is billed; the figure stands for the quota an image
  uses and keeps the habit of counting. Auto is priced as high."
  {"low" 0.011 "medium" 0.042 "high" 0.167 "auto" 0.167})

(defn confirm-usd
  "A batch estimated above this many dollars needs :confirmed true:
  BBH_IMAGE_CONFIRM_USD, else 0.50."
  []
  (or (some-> (sysenv/getenv "BBH_IMAGE_CONFIRM_USD") parse-double) 0.50))

(def confirm-count "A batch of more images than this needs :confirmed true." 4)

(defn estimate
  "The API-equivalent dollars of N images at QUALITY, larger sizes counted as 1.5."
  [{:keys [n quality size]}]
  (* n (price-usd quality 0.167) (if (#{"1536x1024" "1024x1536"} size) 1.5 1.0)))

(defn- encode [bytes] (.encodeToString (Base64/getEncoder) ^bytes bytes))
(defn- decode [^String s] (.decode (Base64/getDecoder) s))

(defn- mime [path]
  (case (str/lower-case (or (fs/extension path) ""))
    ("jpg" "jpeg") "image/jpeg"
    "webp" "image/webp"
    "gif" "image/gif"
    "image/png"))

(defn data-url
  "PATH's bytes as a data: URL."
  [path]
  (str "data:" (mime path) ";base64," (encode (fs/read-all-bytes path))))

;; Providers. A provider takes one request, {:prompt :images :mask :size
;; :quality :format :background}, where :images and :mask are paths, and
;; returns {:bytes :revised-prompt :size :quality :model} for one image.

(defn codex-request
  "The Codex Responses API body that makes one image with the image_generation tool."
  [{:keys [prompt images mask size quality format background model]}]
  {:model model
   :instructions (str "You make images with the image_generation tool. Call it exactly once, "
                      "with the user's description as faithfully as you can, then stop. "
                      "When images are attached, edit or vary them as the user asks.")
   :input [{:type "message" :role "user"
            :content (into [{:type "input_text" :text (cond-> prompt (not= "auto" size) (str "\n\nOutput size: " size " pixels."))}]
                           (for [p images] {:type "input_image" :image_url (data-url p)}))}]
   :tools [(cond-> {:type "image_generation" :size size :quality quality
                    :output_format format :background background}
             mask (assoc :input_image_mask {:image_url (data-url mask)}))]
   :tool_choice {:type "image_generation"}
   :store false
   :stream true})

(defn sse-events
  "The JSON events of server-sent-events text S."
  [s]
  (->> (str/split (str s) #"\r?\n\r?\n")
       (keep #(second (re-find #"(?m)^data: (.*)$" %)))
       (keep #(try (json/parse-string % true) (catch Exception _ nil)))))

(defn codex-result
  "The image in EVENTS, the streamed reply to `codex-request`, or an exception
  saying why there is none."
  [events]
  (let [items (keep #(when (= "response.output_item.done" (:type %)) (:item %)) events)
        call (some #(when (= "image_generation_call" (:type %)) %) items)
        err (some #(or (:error %) (get-in % [:response :error])) events)
        text (str/join (keep #(when (= "response.output_text.delta" (:type %)) (:delta %)) events))]
    (cond (:result call) {:bytes (decode (:result call))
                          :revised-prompt (:revised_prompt call)
                          :size (:size call)
                          :quality (:quality call)
                          :model (some #(get-in % [:response :tools 0 :model]) (reverse events))}
          err (throw (ex-info (str "Codex image: " (or (:message err) err)) {:error err}))
          :else (throw (ex-info (str "Codex made no image" (when (seq text) (str ": " text))) {:text text})))))

(defn codex
  "The ChatGPT subscription provider. BBH_IMAGE_MODEL picks the Codex model that
  calls the tool (default gpt-6.1-sol); the tool's image model is the backend's."
  [req]
  (let [{:keys [access accountId expires]} (auth/pi-entry "openai-codex")]
    (when-not (and access (> (or expires 0) (System/currentTimeMillis)))
      (throw (ex-info "Log in to OpenAI Codex in Pi; its token is missing or expired." {})))
    (let [body (codex-request (assoc req :model (or (sysenv/getenv "BBH_IMAGE_MODEL") "gpt-6.1-sol")))
          resp (http/post "https://chatgpt.com/backend-api/codex/responses"
                          {:headers {"Authorization" (str "Bearer " access)
                                     "chatgpt-account-id" accountId
                                     "Content-Type" "application/json"
                                     "Accept" "text/event-stream"
                                     "originator" "codex_cli_rs"
                                     "User-Agent" "codex_cli_rs"}
                           :body (json/generate-string body)
                           :timeout 300000
                           :throw false})]
      (when-not (= 200 (:status resp))
        (throw (ex-info (str "Codex image: HTTP " (:status resp) " " (subs (str (:body resp)) 0 (min 400 (count (str (:body resp))))))
                        {:status (:status resp)})))
      (codex-result (sse-events (:body resp))))))

(def providers "Provider functions by name." {"codex" codex})

;; Batches.

(defn- slug [s]
  (let [t (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))]
    (subs t 0 (min 40 (count t)))))

(defn- batch-dir [root prompt]
  (let [now (LocalDateTime/now)
        dir (fs/path root (.format now (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
                     (str (.format now (DateTimeFormatter/ofPattern "HHmmss")) "-" (let [s (slug prompt)] (if (seq s) s "image"))))]
    (loop [d dir i 2]
      (if (fs/exists? d) (recur (fs/path (str dir "-" i)) (inc i)) (str d)))))

(defn- validate [{:keys [prompt n size quality format background images mask]}]
  (let [bad (fn [msg] (throw (ex-info (str "bbh.image: " msg) {:error :invalid})))]
    (when (str/blank? prompt) (bad "needs a :prompt"))
    (when-not (and (int? n) (pos? n)) (bad ":n is a positive integer"))
    (when-not (sizes size) (bad (str ":size is one of " (str/join " " (sort sizes)))))
    (when-not (qualities quality) (bad (str ":quality is one of " (str/join " " (sort qualities)))))
    (when-not (formats format) (bad (str ":format is one of " (str/join " " (sort formats)))))
    (when-not (backgrounds background) (bad (str ":background is one of " (str/join " " (sort backgrounds)))))
    (doseq [p (cond-> (vec images) mask (conj mask))]
      (when-not (fs/regular-file? p) (bad (str "no image at " p))))
    (when (and mask (empty? images)) (bad ":mask needs an image in :images to edit"))))

(defn- sidecar [m]
  (with-out-str (pprint/pprint m)))

(defn gallery-html
  "The batch page: each image inline as a data URL, so the phone's Docs and
  view show it with nothing else served, followed by its parameters."
  [{:keys [prompt thread params images estimate created]}]
  (explainer/page
   {:title (str "Images: " (let [p (str/replace prompt #"\s+" " ")] (if (> (count p) 70) (str (subs p 0 70) "…") p)))
    :description (str (count images) " image" (when (not= 1 (count images)) "s") " from " (:provider params) ", " (str/replace (subs created 0 (min 16 (count created))) "T" " "))
    :thread (or thread "images")
    :style ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px}.grid figure{margin:0}.grid img{width:100%;height:auto;border-radius:8px;border:1px solid var(--line);background:#fff}figcaption{font-size:13px;color:var(--mut)}pre{white-space:pre-wrap;overflow-wrap:anywhere}"
    :body (str (when (> (count prompt) 70) (str "<p class=sub>" (explainer/escape prompt) "</p>"))
               "<div class=grid>"
               (str/join (for [{:keys [path revised-prompt size]} images]
                           (str "<figure><a href=\"file://" (explainer/escape path) "\"><img loading=lazy alt=\"" (explainer/escape (or revised-prompt prompt))
                                "\" src=\"" (data-url path) "\"></a><figcaption><span class=cite>" (explainer/escape path) " " (explainer/escape (str size)) "</span></figcaption></figure>")))
               "</div><h2>Parameters</h2><pre><code>"
               (explainer/escape (sidecar (assoc params :estimate-usd estimate)))
               "</code></pre>")}))

(defn index-org
  "An Org page of the batch that Emacs shows with the images inline."
  [{:keys [prompt images]}]
  (str "#+title: " (str/replace prompt #"\s+" " ") "\n#+startup: inlineimages\n\n"
       (str/join "\n" (for [{:keys [path revised-prompt]} images]
                         (str "#+attr_org: :width 480\n[[file:" (fs/file-name path) "]]\n"
                              (when revised-prompt (str "- " revised-prompt "\n")))))))

(defn- ledger! [root entry]
  (fs/create-dirs root)
  (spit (str (fs/path root "ledger.edn")) (str (pr-str entry) "\n") :append true))

(defn spend
  "Batches and images in the ledger since SINCE (an ISO date string, default
  today), with their API-equivalent estimate."
  ([] (spend (str (java.time.LocalDate/now))))
  ([since]
   (let [f (fs/path (image-dir) "ledger.edn")
         rows (if (fs/exists? f)
                (keep #(try (edn/read-string %) (catch Exception _ nil)) (str/split-lines (slurp (str f))))
                [])
         rows (filter #(>= (compare (str (:created %)) since) 0) rows)]
     {:since since :batches (count rows) :images (reduce + 0 (map :made rows))
      :estimate-usd (reduce + 0.0 (map :estimate-usd rows))})))

(defn generate!
  "Make images and return {:dir :images [path ...] :gallery :org :estimate-usd
  :errors}. OPTS: :prompt (required), :n (1), :size (auto, 1024x1024,
  1536x1024, 1024x1536), :quality (auto low medium high; default medium),
  :format (png jpeg webp), :background (auto opaque transparent), :images
  (paths to edit or combine), :mask (a png of the first image's size whose transparent
  pixels ask for the edit there; the model treats it as a hint), :thread (such as \"T12\", for the Docs list),
  :provider (a name in `providers` or a function, default codex), :dir (the
  root, default `image-dir`) and :confirmed (true after the person agreed to a
  batch over the threshold). Images are made up to three at a time; a failed
  one is listed in :errors and the rest are kept."
  [opts]
  (let [{:keys [prompt n size quality format background images mask thread provider dir confirmed]
         :as opts} (merge {:n 1 :size "auto" :quality "medium" :format "png" :background "auto" :provider "codex"} opts)
        _ (validate opts)
        cost (estimate opts)
        provider-fn (if (fn? provider) provider (or (providers (name provider)) (throw (ex-info (str "bbh.image: no provider " provider) {:error :invalid}))))]
    (when (and (not confirmed) (or (> cost (confirm-usd)) (> n confirm-count)))
      (throw (ex-info (clojure.core/format "bbh.image: %d images at %s quality are about $%.2f in API terms, over the $%.2f or %d-image limit; ask the person, then pass :confirmed true"
                              n quality cost (confirm-usd) confirm-count)
                      {:error :needs-confirmation :n n :estimate-usd cost})))
    (let [root (or dir (image-dir))
          out (batch-dir root prompt)
          created (str (ZonedDateTime/now))
          params {:prompt prompt :n n :size size :quality quality :format format :background background
                  :images (vec images) :mask mask :provider (if (fn? provider) "custom" (name provider))}
          req {:prompt prompt :images (vec images) :mask mask :size size :quality quality :format format :background background}
          results (->> (range n)
                       (partition-all 3)
                       (mapcat (fn [chunk] (mapv deref (mapv (fn [i] (future (try {:i i :ok (provider-fn req)}
                                                                                  (catch Exception e {:i i :error (ex-message e)}))))
                                                             chunk))))
                       vec)
          made (vec (for [{:keys [i ok]} results :when ok
                          :let [path (str (fs/path out (clojure.core/format "%02d.%s" (inc i) (if (= "jpeg" format) "jpg" format))))]]
                      (do (fs/create-dirs out)
                          (fs/write-bytes path (:bytes ok))
                          (spit (str (fs/strip-ext path) ".edn")
                                (sidecar (merge params {:created created :file (str (fs/file-name path))
                                                        :revised-prompt (:revised-prompt ok) :actual-size (:size ok)
                                                        :actual-quality (:quality ok) :model (:model ok)})))
                          {:path path :revised-prompt (:revised-prompt ok) :size (:size ok)})))
          errors (vec (keep :error results))
          batch {:prompt prompt :thread thread :params params :images made :estimate cost :created created}]
      (ledger! root {:created created :dir out :n n :made (count made) :quality quality :size size
                     :provider (:provider params) :estimate-usd cost})
      (if (empty? made)
        (throw (ex-info (str "bbh.image: no image made: " (first errors)) {:error :failed :errors errors}))
        (let [gallery (str (fs/path out "gallery.html"))
              org (str (fs/path out "index.org"))]
          (spit gallery (gallery-html batch))
          (spit org (index-org batch))
          (cond-> {:dir out :images (mapv :path made) :gallery gallery :org org :estimate-usd cost}
            (seq errors) (assoc :errors errors)))))))

(defn edit!
  "Edit IMAGES (a path or paths) as PROMPT says; OPTS as for `generate!`, with
  :mask to confine the edit (inpainting)."
  [images prompt & {:as opts}]
  (generate! (assoc opts :prompt prompt :images (if (string? images) [images] (vec images)))))

(defn variations!
  "N variations (default 4) of the image at PATH, steered by OPTS :prompt."
  [path & {:keys [n prompt] :or {n 4} :as opts}]
  (generate! (assoc opts :n n :images [path]
                    :prompt (str "Make a variation of this image: keep its subject and composition but vary the details."
                                 (when prompt (str " " prompt))))))

(defn- parse-cli [args]
  (loop [[a b & more :as xs] args opts {} words []]
    (cond (empty? xs) (cond-> opts (seq words) (assoc :prompt (str/join " " words)))
          (= "--confirmed" a) (recur (rest xs) (assoc opts :confirmed true) words)
          (str/starts-with? a "--")
          (let [k (keyword (subs a 2))]
            (when (nil? b) (throw (ex-info (str "bbh image: " a " needs a value") {})))
            (recur more (case k
                          :n (assoc opts :n (parse-long b))
                          (:image :images) (update opts :images (fnil conj []) b)
                          (:size :quality :format :background :mask :thread :provider :dir) (assoc opts k b)
                          (throw (ex-info (str "bbh image: unknown option " a) {})))
                   words))
          :else (recur (rest xs) opts (conj words a)))))

(defn -main
  "bbh image [--n N] [--size S] [--quality Q] [--format F] [--background B]
  [--image PATH]... [--mask PATH] [--thread T12] [--confirmed] PROMPT...
  Prints the result as EDN."
  [& args]
  (if (or (empty? args) (#{"-h" "--help"} (first args)))
    (println "usage: bbh image [--n N] [--size auto|1024x1024|1536x1024|1024x1536] [--quality auto|low|medium|high] [--format png|jpeg|webp] [--background auto|opaque|transparent] [--image PATH]... [--mask PATH] [--thread T12] [--confirmed] PROMPT...")
    (try (prn (generate! (parse-cli args)))
         (catch clojure.lang.ExceptionInfo e
           (binding [*out* *err*] (println (ex-message e)))
           (System/exit 1)))))
