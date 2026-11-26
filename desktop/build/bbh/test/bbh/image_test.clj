(ns bbh.image-test
  (:require [babashka.fs :as fs]
            [bbh.docs :as docs]
            [bbh.hermetic :as hermetic]
            [bbh.image :as image]
            [bbh.sysenv :as sysenv]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(def ^:private png
  "A 1x1 PNG."
  (.decode (java.util.Base64/getDecoder) "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))

(defn- fake
  "A provider that records each request in CALLS and returns the 1x1 PNG,
  failing on the requests FAIL? picks."
  [calls & {:keys [fail?] :or {fail? (constantly false)}}]
  (fn [req]
    (let [i (dec (count (swap! calls conj req)))]
      (when (fail? i) (throw (ex-info "fake refused" {})))
      {:bytes png :revised-prompt (str "revised: " (:prompt req)) :size "1024x1024" :quality (:quality req) :model "fake"})))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "bbh-image-test"})))

(deftest a-batch-saves-images-sidecars-a-gallery-and-an-org-page
  (let [calls (atom []) root (tmp)
        {:keys [dir images gallery org estimate-usd]}
        (image/generate! {:prompt "A red fox in snow" :n 2 :quality "low" :provider (fake calls) :dir root :thread "T235"})]
    (is (= 2 (count @calls)))
    (is (= 2 (count images)))
    (is (every? fs/regular-file? images))
    (is (str/starts-with? dir root))
    (is (re-find #"/\d{6}-a-red-fox-in-snow$" dir))
    (is (< (abs (- 0.022 estimate-usd)) 1e-9))
    (testing "each image has an EDN sidecar with the prompt and parameters"
      (let [m (edn/read-string (slurp (str (fs/strip-ext (first images)) ".edn")))]
        (is (= "A red fox in snow" (:prompt m)))
        (is (= "low" (:quality m)))
        (is (= "revised: A red fox in snow" (:revised-prompt m)))
        (is (= "01.png" (:file m)))))
    (testing "the gallery is a Docs page with the images inline"
      (let [html (slurp gallery)
            doc (docs/read-doc gallery html)]
        (is (= "T235" (:thread doc)))
        (is (str/includes? (:title doc) "A red fox in snow"))
        (is (= 2 (count (re-seq #"src=\"data:image/png;base64," html))))
        (is (= [gallery] (map :path (docs/listing [[root "**/gallery.html"]]))))))
    (testing "the Org page links each image for inline display"
      (let [text (slurp org)]
        (is (str/includes? text "#+startup: inlineimages"))
        (is (str/includes? text "[[file:01.png]]"))
        (is (str/includes? text "[[file:02.png]]"))))
    (testing "the ledger counts the batch"
      (with-redefs [sysenv/getenv (fn ([] {}) ([k] (when (= k "BBH_IMAGE_DIR") root)))]
        (is (= {:batches 1 :images 2} (select-keys (image/spend "2000-01-01") [:batches :images])))))))

(deftest a-batch-over-the-threshold-needs-confirmation
  (let [calls (atom []) root (tmp)
        refused (fn [opts]
                  (try (image/generate! (merge {:prompt "p" :provider (fake calls) :dir root} opts)) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (= :needs-confirmation (:error (refused {:n 5 :quality "low"}))) "more images than the count limit")
    (is (= :needs-confirmation (:error (refused {:n 4 :quality "high"}))) "over the dollar limit")
    (is (empty? @calls) "nothing is generated before confirmation")
    (is (= 5 (count (:images (image/generate! {:prompt "p" :n 5 :quality "low" :provider (fake calls) :dir root :confirmed true})))))
    (with-redefs [sysenv/getenv (fn ([] {}) ([k] (when (= k "BBH_IMAGE_CONFIRM_USD") "0.01")))]
      (is (= :needs-confirmation (:error (refused {:n 1 :quality "medium"}))) "the threshold comes from the environment"))))

(deftest bad-options-are-refused-before-any-call
  (let [calls (atom []) root (tmp)
        error (fn [opts] (try (image/generate! (merge {:prompt "p" :provider (fake calls) :dir root} opts)) nil
                              (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (is (re-find #"prompt" (error {:prompt " "})))
    (is (re-find #":size" (error {:size "999x999"})))
    (is (re-find #":quality" (error {:quality "ultra"})))
    (is (re-find #"no image at" (error {:images ["/nonexistent.png"]})))
    (is (re-find #":mask needs" (let [m (str (fs/path root "m.png"))] (fs/write-bytes m png) (error {:mask m}))))
    (is (empty? @calls))))

(deftest edit-and-variations-pass-the-input-images
  (let [calls (atom []) root (tmp)
        src (str (fs/path root "src.png")) mask (str (fs/path root "mask.png"))]
    (fs/write-bytes src png)
    (fs/write-bytes mask png)
    (image/edit! src "add a moon" :mask mask :provider (fake calls) :dir root)
    (is (= {:prompt "add a moon" :images [src] :mask mask} (select-keys (first @calls) [:prompt :images :mask])))
    (reset! calls [])
    (let [{:keys [images]} (image/variations! src :prompt "in blue" :provider (fake calls) :dir root)]
      (is (= 4 (count images)))
      (is (every? #(= [src] (:images %)) @calls))
      (is (str/includes? (:prompt (first @calls)) "in blue")))))

(deftest a-failed-image-is-reported-and-the-rest-kept
  (let [calls (atom []) root (tmp)
        r (image/generate! {:prompt "p" :n 3 :provider (fake calls :fail? #{1}) :dir root})]
    (is (= 2 (count (:images r))))
    (is (= ["fake refused"] (:errors r)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no image made"
                          (image/generate! {:prompt "p" :provider (fake (atom []) :fail? (constantly true)) :dir root})))))

(deftest the-codex-request-forces-the-image-tool
  (let [root (tmp) src (str (fs/path root "a.png")) mask (str (fs/path root "m.png"))]
    (fs/write-bytes src png)
    (fs/write-bytes mask png)
    (let [body (image/codex-request {:model "m" :prompt "edit it" :images [src] :mask mask :size "1024x1024"
                                     :quality "low" :format "png" :background "auto"})
          [text img] (get-in body [:input 0 :content])]
      (is (= {:type "image_generation"} (:tool_choice body)))
      (is (= {:type "input_text" :text "edit it\n\nOutput size: 1024x1024 pixels."} text) "the backend honours the size only when the prompt names it")
      (is (str/starts-with? (:image_url img) "data:image/png;base64,"))
      (is (= {:type "image_generation" :size "1024x1024" :quality "low" :output_format "png" :background "auto"}
             (dissoc (first (:tools body)) :input_image_mask)))
      (is (str/starts-with? (get-in body [:tools 0 :input_image_mask :image_url]) "data:image/png;base64,"))
      (is (false? (:store body))))))

(defn- sse [& events]
  (str/join (for [e events] (str "event: " (:type e) "\ndata: " (json/generate-string e) "\n\n"))))

(deftest the-codex-stream-yields-the-image-or-says-why-not
  (let [b64 (.encodeToString (java.util.Base64/getEncoder) png)
        ok (image/codex-result
            (image/sse-events
             (sse {:type "response.created"}
                  {:type "response.output_item.done"
                   :item {:type "image_generation_call" :result b64 :revised_prompt "rp" :size "1254x1254" :quality "low"}}
                  {:type "response.completed" :response {:tools [{:type "image_generation" :model "gpt-image-2-codex"}]}})))]
    (is (= (seq png) (seq (:bytes ok))))
    (is (= {:revised-prompt "rp" :size "1254x1254" :quality "low" :model "gpt-image-2-codex"} (dissoc ok :bytes))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Codex image: blocked"
                        (image/codex-result (image/sse-events (sse {:type "response.failed" :response {:error {:message "blocked"}}})))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"made no image: sorry"
                        (image/codex-result (image/sse-events (sse {:type "response.output_text.delta" :delta "sorry"}))))))

(deftest the-cli-parses-options-and-the-prompt
  (is (= {:n 2 :quality "low" :images ["a.png" "b.png"] :confirmed true :thread "T1" :prompt "a red fox"}
         (#'image/parse-cli ["--n" "2" "--quality" "low" "a" "--image" "a.png" "--image" "b.png"
                             "--confirmed" "red" "--thread" "T1" "fox"]))))
