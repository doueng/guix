(ns bbh.web.conversation)

(defn merge-page
  "CONVERSATION with PAGE's messages in order. A page holds older or newer ones,
  and two polls can race, so a message already held is dropped."
  [conversation {:keys [messages] :as page}]
  (let [held (:messages conversation [])]
    (assoc (dissoc page :messages)
           :messages (if (empty? held)
                       (vec messages)
                       (let [first-held (:i (first held))
                             last-held (:i (peek held))]
                         (vec (concat (filter #(< (:i %) first-held) messages)
                                      held
                                      (filter #(> (:i %) last-held) messages))))))))
