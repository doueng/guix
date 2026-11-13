#!/usr/bin/env bb
(require '[clojure.test :refer [deftest is run-tests]])
(load-file (str (babashka.fs/parent *file*) "/builder.bb"))
(alias 'b 'second-brain-builder)

(deftest accepts-known-kinds
  (is (= {:file "/var/lib/second-brain/inbox/chrome.json"
          :args ["import-chrome" "/var/lib/second-brain/inbox/chrome.json"]}
         (b/parse-command "chrome")))
  (is (= ["import-html" "/var/lib/second-brain/inbox/bookmarks.html" "safari"]
         (:args (b/parse-command "html safari"))))
  (is (= ["import-x" "likes" "/var/lib/second-brain/inbox/like.js"]
         (:args (b/parse-command " x-likes "))))
  (is (= ["import-x" "export" "/var/lib/second-brain/inbox/x-export.json"]
         (:args (b/parse-command "x-export")))))

(deftest refuses-everything-else
  (doseq [c [nil "" "sh" "chrome extra" "html" "html ../../etc" "html Safari"
             "html safari more" "x-likes /etc/passwd" "rm -rf /" "chrome; id"]]
    (is (nil? (b/parse-command c)) (pr-str c))))

(deftest owner-from-status
  (is (= "me@example.com"
         (b/owner-login {"Self" {"UserID" 42}
                         "User" {"42" {"LoginName" "me@example.com"}
                                 "7" {"LoginName" "other@example.com"}}})))
  (is (nil? (b/owner-login {"Self" {}}))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
