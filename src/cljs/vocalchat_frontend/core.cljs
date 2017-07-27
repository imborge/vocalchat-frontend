(ns vocalchat-frontend.core
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [vocalchat-frontend.events]
            [vocalchat-frontend.subs]
            [vocalchat-frontend.routes :as routes]
            [vocalchat-frontend.views :as views]
            [vocalchat-frontend.config :as config]
            [vocalchat-frontend.transit :as t]))

(defn dev-setup []
  ;when config/debug?
  (enable-console-print!)
  (println "dev mode"))

(defn mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [views/main-panel]
                  (.getElementById js/document "app")))

(defn make-websocket! [url receive-handler]
  (println "attempting to connect to matching server")
  (when-let [chan (js/WebSocket. url)]
    (do
      (set! (.-onmessage chan) #(re-frame/dispatch [:server/got-message (-> % .-data (t/read))]))
      (set! (.-onopen chan) #(do
                               (println "connection established with matching server")
                               (re-frame/dispatch [:server/channel chan])
                               (re-frame/dispatch [:server/connected])))
      (set! (.-onclose chan) #(do
                                (println "connection with matching server closed")
                                (re-frame/dispatch [:server/disconnected]))))))

(defn ^:export init []
  (routes/app-routes)
  (re-frame/dispatch-sync [:initialize-db])
  (dev-setup)
  (make-websocket! (if config/debug?
                     "ws://localhost:3000/ws"
                     "wss://ws.vocal.chat/ws") nil)
  (mount-root))
