(ns vimium-cljs.exclusions-test
  (:require [cljs.test :refer [deftest is]]
            [vimium-cljs.exclusions :as x]))

(def rules [{:pass-keys "" :pattern "https?://go.example/*"}
            {:pass-keys "j k" :pattern "https?://docs.example/*"}
            {:pass-keys "kx" :pattern "*://docs.example/a*"}])

(deftest rules-for-urls
  (is (nil? (x/rule-for-url rules "https://other.example/")))
  (is (= {:enabled false :pass-keys ""} (x/rule-for-url rules "https://go.example/doc")))
  (is (= {:enabled false :pass-keys ""} (x/rule-for-url rules "http://go.example/")) "? is a regexp ?")
  (is (nil? (x/rule-for-url rules "https://go.example")) "anchored at the end")
  (is (= {:enabled true :pass-keys "jk"} (x/rule-for-url rules "https://docs.example/b")))
  (is (= {:enabled true :pass-keys "jkx"} (x/rule-for-url rules "https://docs.example/a1")) "pass keys merge")
  (is (nil? (x/rule-for-url [{:pattern "(" :pass-keys ""}] "https://x/")) "a broken pattern matches nothing"))
