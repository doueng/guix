(ns vimium-cljs.config-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vimium-cljs.commands :as commands]
            [vimium-cljs.config :as config]
            [vimium-cljs.keys :as k]))

(deftest key-mappings-are-well-formed
  (doseq [[key cmd opts :as e] config/key-mappings]
    (is (and (string? key) (seq key)) (str "key in " e))
    (is (or (nil? cmd) (commands/by-name cmd)) (str "known command in " e))
    (is (or (nil? opts) (map? opts)) (str "options map in " e)))
  (is (= [] (:errors (k/parse-mappings (k/mappings-text config/key-mappings))))))

(deftest same-as-the-vimium-export
  (let [export "# Insert your preferred key mappings here.\nmap i focusInput\nmap w previousTab\nmap e nextTab\nunmap J\nunmap K\nmap h goBack\nmap l goForward\nmap p Vomnibar.activateTabSelection\nmap <c-o> visitPreviousTab\nunmap N"]
    (is (= (k/effective-mappings export) (k/effective-mappings (k/mappings-text config/key-mappings))))))

(deftest the-users-keys
  (let [m (k/effective-mappings (k/mappings-text config/key-mappings))
        cmd (fn [key] (:command ((:entries m) key)))]
    (testing "w and e move to the previous and next tab"
      (is (= "previousTab" (cmd "w")))
      (is (= "nextTab" (cmd "e"))))
    (is (= "focusInput" (cmd "i")))
    (is (= "Vomnibar.activateTabSelection" (cmd "p")))
    (is (= "visitPreviousTab" (cmd "<c-o>")))
    (is (= "goBack" (cmd "h")))
    (is (nil? (cmd "J")))
    (is (nil? (cmd "N")))))

(deftest other-settings-are-well-formed
  (is (>= (count (distinct (str/lower-case config/link-hint-characters))) 2))
  (doseq [{:keys [pattern pass-keys]} config/exclusions]
    (is (and (string? pattern) (seq pattern)))
    (is (string? pass-keys)))
  (doseq [[kw {:keys [url name]}] config/search-engines]
    (is (str/includes? url "%s") kw)
    (is (string? name) kw))
  (is (str/starts-with? config/search-url "https://"))
  (is (pos? config/scroll-step))
  (is (or (nil? config/new-tab-url) (string? config/new-tab-url))))
