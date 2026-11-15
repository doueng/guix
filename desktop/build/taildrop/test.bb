(require '[babashka.fs :as fs] '[clojure.string :as str])
(def root (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/real-path *file*)))))))
(load-file (str root "/desktop/bin/taildrop"))

(def results
  [["a file URI becomes its path"
    (= "/home/me/a b+c.png" (uri->path "file:///home/me/a%20b+c.png"))]
   ["a URI with a host still gives the path"
    (= "/tmp/x" (uri->path "file://laptop/tmp/x"))]
   ["a web URL is not a file" (nil? (uri->path "https://example.com/x"))]
   ["images are previewed in the notification"
    (every? #(re-find image-re %) ["IMG_1.HEIC" "a.jpeg" "b.png"])]
   ["sending nothing is refused before asking for a device"
    (= "Nothing to send" (try (send! {:files []}) nil (catch Exception e (ex-message e))))]
   ["a missing file is refused and the staging dir removed"
    (let [tmp (str (fs/create-temp-dir))]
      (and (str/starts-with? (try (send! {:files ["/nonexistent/x"] :tmp tmp}) "" (catch Exception e (ex-message e)))
                             "Not a file")
           (not (fs/exists? tmp))))]])

(doseq [[label ok] results] (println (if ok "ok  " "FAIL") label))
(System/exit (if (every? second results) 0 1))
