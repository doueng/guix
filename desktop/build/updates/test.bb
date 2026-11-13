#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[clojure.string :as str] '[clojure.test :refer [deftest is run-tests]])
(load-file (str (fs/path (fs/parent *file*) "../../bin/updates")))
(alias 'u 'updates)

(def now (java.time.Instant/parse "2026-11-10T12:00:00Z"))
(def a (apply str (repeat 40 "a")))
(def b (apply str (repeat 40 "b")))
(def c (apply str (repeat 40 "c")))

(deftest compare-links-run-from-current-to-latest
  (is (= "https://github.com/o/r/compare/aaa...bbb" (u/compare-url "https://github.com/o/r.git" "aaa" "bbb")))
  (is (= "https://git.sr.ht/~o/r/log/bbb" (u/compare-url "https://git.sr.ht/~o/r" "aaa" "bbb")))
  (is (= "https://github.com/o/r/compare/aaa...bbb (held: why)"
         (u/changelog {:compare "https://github.com/o/r" :current "aaa" :latest "bbb" :held "why"})))
  (is (= "https://x/notes" (u/changelog {:changelog "https://x/notes" :current "1" :latest "2"}))))

(deftest a-row-links-the-installed-commit-even-after-the-cache-was-written
  ;; Refreshed when the plugin was at A and B was upstream; since then it moved to C.
  (let [cache {:refreshed {"lazy" "2026-11-10T11:00:00Z"}
               :latest {["lazy" "p"] {:latest b :compare "https://github.com/o/p.git" :current a}}}
        [item] (u/with-latest cache [{:source "lazy" :name "p" :current c}])
        [[_ _ current latest url]] (u/table-rows [item])]
    (is (= [(subs c 0 12) (subs b 0 12)] [current latest]))
    (is (= (str "https://github.com/o/p/compare/" c "..." b) url))
    (is (not= (subs c 0 12) latest) "a shown row never compares a commit with itself")
    (is (= {"lazy" "pins changed"} (u/stale-sources cache [item] now)))))

(deftest an-up-to-date-row-shows-no-link
  (let [[[_ _ _ latest url]] (u/table-rows (u/with-latest {:latest {["doom" "d"] {:latest a :compare "https://github.com/d/d" :current a}}}
                                                          [{:source "doom" :name "d" :current a}]))]
    (is (= ["=" ""] [latest url]))))

(deftest lazy-current-is-the-installed-commit-and-a-differing-lock-is-one-line
  (is (nil? (u/lock-drift [{:name "p" :current a :locked a}])))
  (let [line (u/lock-drift [{:name "p" :current a :locked b} {:name "q" :current c :locked c}])]
    (is (str/starts-with? line "lazy: lazy-lock.json pins other commits than installed for 1 plugins (p);"))
    (is (= 1 (count (str/split-lines line))))))

(deftest the-cache-says-when-it-needs-a-refresh
  (let [items [{:source "pi" :name "x" :current "1"} {:source "doom" :name "d" :current a}]
        fresh {:refreshed {"pi" "2026-11-10T11:00:00Z" "doom" "2026-11-10T11:00:00Z"}
               :latest {["pi" "x"] {:latest "2" :current "1"} ["doom" "d"] {:latest b :current a}}}]
    (is (= {} (u/stale-sources fresh items now)))
    (is (= {"pi" "cached 1d 2h ago"}
           (u/stale-sources (assoc-in fresh [:refreshed "pi"] "2026-11-09T10:00:00Z") items now)))
    (is (= {"pi" "failed last time"} (u/stale-sources (assoc-in fresh [:latest ["pi" "x"] :error] "boom") items now)))
    (is (= {"doom" "new items"} (u/stale-sources (update fresh :latest dissoc ["doom" "d"]) items now)))
    (is (= {"doom" "cached by an older updates"}
           (u/stale-sources (update-in fresh [:latest ["doom" "d"]] dissoc :current) items now)))
    (is (= {"doom" "never refreshed" "pi" "never refreshed"} (u/stale-sources {:latest {}} items now)))
    (is (= {} (u/stale-sources {:latest {}} [{:source "pi" :name "?" :error "boom"}] now))
        "a source whose inventory failed is not refreshed")
    (is (= "2026-11-09T10:00:00Z" (u/cached-at {:refreshed-at "2026-11-09T10:00:00Z"} "pi")) "an old cache's stamp")))

(deftest ages-read-at-a-glance
  (is (= "7m" (u/age "2026-11-10T11:53:00Z" now)))
  (is (= "1h 28m" (u/age "2026-11-10T10:32:00Z" now)))
  (is (= "2d 0h" (u/age "2026-11-08T12:00:00Z" now))))

(deftest a-hand-updated-package-reads-its-own-version
  (is (= "1.4.0" (u/hand-version "(define-public zig\n (package (version \"0.17.0\")))\n(define-public ghostty\n  (package\n    (name \"ghostty\")\n    (version \"1.4.0\")))" "ghostty")))
  (is (= "1.4.0" (:current (first (u/hand-items)))) "ghostty.scm as checked in"))

(deftest a-release-that-does-not-build-is-left-as-it-was-and-the-rest-go-on
  (let [file (str (fs/create-temp-file {:suffix ".scm"}))
        built (atom [])]
    (spit file "before")
    (with-redefs [u/latest-file file
                  u/latest-build! (fn [name]
                                    (swap! built conj name)
                                    (when (= name "bad") (throw (ex-info "bad does not build" {}))))]
      (let [e (try (u/each-latest! (fn [{:keys [name]}] (spit file (str (slurp file) " " name)))
                                   [{:name "good"} {:name "bad"} {:name "also"}])
                   nil
                   (catch Exception e e))]
        (is (= ["good" "bad" "also"] @built))
        (is (= "before good also" (slurp file)) "bad's edit is undone, the others stay")
        (is (str/includes? (ex-message e) "bad") "the failure names the package")
        (is (:partial (ex-data e)) "the prebuild still runs for the others")))
    (fs/delete file)))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
