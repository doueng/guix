#!/usr/bin/env bb
;; A stand-in for pi in ghostty-rex's switch timing check (verify.clj): a
;; 24 MB Kitty graphics image, as pi shows screenshots inline, then a line
;; marking its directory, then it waits. Rex keeps the image, so the pane's
;; snapshot is as large as the real pi's (51 MB on personal-site, T210).
(let [w 2048 h 3072
      px (byte-array (* w h 4))
      _ (java.util.Arrays/fill px (unchecked-byte 0x7f))
      b64 (.encodeToString (java.util.Base64/getEncoder) px)
      out (java.io.BufferedOutputStream. System/out 1048576)
      put (fn [^String s] (.write out (.getBytes s "ISO-8859-1")))]
  (loop [i 0 first? true]
    (when (< i (count b64))
      (let [j (min (count b64) (+ i 4096))]
        (put (str "\u001b_G" (when first? (str "a=T,f=32,s=" w ",v=" h ",c=40,r=10,"))
                  "m=" (if (< j (count b64)) 1 0) ";" (subs b64 i j) "\u001b\\"))
        (recur j false))))
  (put "\r\n")
  (.flush out)
  ;; The directory's first letter twenty times: rexg-render rewrites only the
  ;; cells that change, so a marker must differ from the other pane's in each.
  (println (apply str (repeat 20 (first (.getName (java.io.File. (System/getProperty "user.dir")))))))
  (Thread/sleep 100000000))
