(ns vocalchat-frontend.db)

(def default-db
  {:server {:connected? false
            :channel nil}
   :queue {:queued? true
           :language :english
           :skill-level :buh
           :queued-since :datetime}})
