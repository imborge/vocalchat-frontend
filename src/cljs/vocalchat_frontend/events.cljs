(ns vocalchat-frontend.events
  (:require [re-frame.core :as rf]
            [vocalchat-frontend.config :as config]
            [vocalchat-frontend.db :as db]
            [vocalchat-frontend.transit :as t]))

;; effects

(rf/reg-fx
 :receive-answer
 (fn [{:keys [msg peer-connection]}]
   (let [desc (js/RTCSessionDescription. (clj->js (:sdp msg)))]
     (-> (.setRemoteDescription peer-connection desc)
         (.catch #(println "Error setting remote description in :receive-answer fx:" %))))))

(rf/reg-fx
 :ws
 (fn [value]
   (.send (:channel value) (t/write (:message value)))))

(rf/reg-fx
 :get-user-media
 (fn [{:keys [config on-success on-error]}]
   (-> js/navigator
       .-mediaDevices
       (.getUserMedia (clj->js config))
       (.then (fn [stream] 
                ;; Add stream to the window object to make it available to console
                (set! (.-stream js/window) stream)
                stream))
       (.then #(rf/dispatch (conj on-success %)))
       (.catch #(rf/dispatch (conj on-error %))))))

(rf/reg-fx
 :rtc-peer-connection
 (fn [{:keys [config stream caller?]}]
   (let [pc (js/RTCPeerConnection. (clj->js config))]
     (set! (.-onicecandidate pc) (fn [event]
                                   (rf/dispatch [:rtc-peer-connection/send-ice-candidate (-> event .-candidate)])))
     (set! (.-onaddstream pc) (fn [event]
                                (rf/dispatch [:set-remote-stream (-> event .-stream)])))
     (set! (.-oniceconnectionstatechange pc) (fn [event]
                                               (rf/dispatch [:ice-state (.-iceconnectionstate pc)])))
     (set! (.-onnegotiationneeded pc) (fn [event]
                                        (when caller?
                                          (-> (.createOffer pc)
                                              (.then (fn [offer]
                                                       (.setLocalDescription pc offer)))
                                              (.then #(rf/dispatch [:rtc-peer-connection/send-offer pc caller?]))
                                              (.catch #(println "onnegotiationneeded error:" %))))))
     (when caller?
       (.addStream pc stream))
     (rf/dispatch [:rtc-peer-connection/init-complete pc]))))

(rf/reg-fx
 :receive-offer
 (fn [{:keys [peer-connection msg local-stream]}]
   (let [desc (js/RTCSessionDescription. (clj->js (:sdp msg)))]
     (-> (.setRemoteDescription peer-connection desc)
         (.then #(.addStream peer-connection local-stream))
         (.then #(.createAnswer peer-connection))
         (.then #(.setLocalDescription peer-connection %))
         (.then #(rf/dispatch [:rtc-peer-connection/send-answer peer-connection]))
         (.catch #(println "receive-offer error:" %))))))

(rf/reg-fx
 :receive-ice-candidate
 (fn [{:keys [candidate peer-connection]}]
   (when (:candidate candidate)
     (let [candidate-obj (js/RTCIceCandidate. candidate)]
       (-> (.addIceCandidate peer-connection candidate-obj)
           (.catch #(println "Error adding ICE candidate: " %)))))))

(rf/reg-fx
 :remote-stream
 (fn [stream]
   (let [audio (js/Audio.)]
     (set! (.-autoplay audio) true)
     (set! (.-srcObject audio) stream)
     (set! (.-remoteAudio js/window) audio)
     (rf/dispatch [:remote-audio-initialized audio]))))

;; events

(rf/reg-event-db
 :initialize-db
 (fn  [_ _]
   db/default-db))

(rf/reg-event-db
 :set-active-panel
 (fn [db [_ active-panel]]
   (assoc db :active-panel active-panel)))

(rf/reg-event-db
 :server/channel
 (fn [db [_ channel]]
   (assoc-in db [:server :channel] channel)))

(rf/reg-event-db
 :server/connected
 (fn [db _]
   (assoc-in db [:server :connected?] true)))

(rf/reg-event-db
 :server/disconnected
 (fn [db _]
   (-> db
       (assoc-in [:server :connected?] false)
       (assoc-in [:server :channel] nil))))

(rf/reg-event-fx
 :queue/init
 (fn [{:keys [db]} [_ language skill-level]]
   (let [chan (-> db :server :channel)]
     {:get-user-media {:on-success [:get-user-media/success language skill-level]
                       :on-error   [:get-user-media/error]
                       :config     {:audio true}}
      :dispatch       [:get-user-media/init]
      :db             (assoc db :queue {:status      :queued
                                        :language    language
                                        :skill-level skill-level})})))

(rf/reg-event-fx
 :queue/enqueue
 (fn [cofx [_ language skill-level]]
   (let [chan (-> cofx :db :server :channel)]
     {:ws {:channel chan
           :message {:type        :queue/join
                     :language    language
                     :skill-level skill-level}}})))

(rf/reg-event-fx
 :queue/unqueue
 (fn [{:keys [db]} _]
   {:ws                 {:channel (get-in db [:server :channel])
                         :message {:type        :queue/leave
                                   :language    (-> db :queue :language)
                                   :skill-level (-> db :queue :skill-level)}}
    :db                 (dissoc db :queue)
    :close-local-stream (-> db :user-media :stream)}))

(rf/reg-event-fx
 :queue/match-found
 (fn [{:keys [db]} [_ message]]
   {:db                  (-> db 
                             (assoc-in [:match] {:room-uuid (:room-uuid message)
                                                 :caller?   (:caller? message)})
                             (assoc-in [:queue :status] :matched))
    :rtc-peer-connection {:config    {:iceServers [{:urls ["stun:stun.l.google.com:19302"
                                                           "stun:stun1.l.google.com:19302"
                                                           "stun:stun2.l.google.com:19302"
                                                           "stun:stun3.l.google.com:19302",
                                                           "turn:vocal.chat"]}]}
                          :stream    (get-in db [:user-media :stream])
                          :caller?   (:caller? message)
                          :user-uuid (:user-uuid message)}}))

(rf/reg-event-db
 :get-user-media/init
 (fn [db _]
   (assoc-in db [:user-media :state] :init)))

(rf/reg-event-db
 :get-user-media/error
 (fn [db [_ error]]
   (-> db
       (assoc-in [:user-media :state] :error)
       (dissoc :queue))))

(rf/reg-event-fx
 :get-user-media/success
 (fn [{:keys [db]} [_ language skill-level stream]]
   {:dispatch-n (list [:get-user-media/got-stream stream] 
                      [:queue/enqueue language skill-level])}))

(rf/reg-event-db
 :get-user-media/got-stream
 (fn [db [_ stream]]
   (assoc-in db [:user-media :stream] stream)))

(rf/reg-event-fx
 :server/got-message
 (fn [{:keys [db]} [_ message]]
   (condp = (:type message)
     :match-found                 {:dispatch [:queue/match-found message]}
     :signaling/new-ice-candidate {:dispatch [:rtc-peer-connection/receive-ice-candidate message]}
     :signaling/audio-offer       {:dispatch [:rtc-peer-connection/receive-offer message]}
     :signaling/audio-answer      {:dispatch [:rtc-peer-connection/receive-answer message]}
     :signaling/hangup            {:dispatch [:hangup :got-hungup]}
     nil
     #_(do
       (println "Unknown message")
       (println message)))))

(rf/reg-event-db
 :rtc-peer-connection/init-complete
 (fn [db [_ pc]]
   (assoc db :peer-connection pc)))

(rf/reg-event-fx
 :rtc-peer-connection/receive-offer
 (fn [{:keys [db]} [_ msg]]
   {:receive-offer {:msg             msg
                    :peer-connection (:peer-connection db)
                    :local-stream    (get-in db [:user-media :stream])}}))

(rf/reg-event-fx
 :rtc-peer-connection/receive-answer
 (fn [{:keys [db]} [_ msg]]
   {:receive-answer {:msg msg
                     :peer-connection (:peer-connection db)}}))

(rf/reg-event-fx
 :rtc-peer-connection/send-offer
 (fn [cofx [_ pc caller?]]
   (let [channel (get-in cofx [:db :server :channel])]
     {:ws {:channel channel
           :message {:type      :signaling/audio-offer
                     :target    (if caller? :callee :caller)
                     :room-uuid (get-in cofx [:db :match :room-uuid])
                     :sdp       (-> pc .-localDescription (.toJSON))}}})))

(rf/reg-event-fx
 :rtc-peer-connection/send-answer
 (fn [{:keys [db]} [_ pc]]
   (let [channel (get-in db [:server :channel])
         caller? (get-in db [:match :caller?])
         room-uuid (get-in db [:match :room-uuid])]
     {:ws {:channel channel
           :message {:type :signaling/audio-answer
                     :target (if caller? :callee :caller)
                     :room-uuid room-uuid
                     :sdp (-> pc .-localDescription (.toJSON))}}})))

(rf/reg-event-fx
 :rtc-peer-connection/receive-ice-candidate
 (fn [{:keys [db]} [_ msg]]
   {:receive-ice-candidate {:candidate       (:candidate msg)
                            :peer-connection (:peer-connection db)}}))

(rf/reg-event-fx
 :rtc-peer-connection/send-ice-candidate
 (fn [{:keys [db]} [_ candidate]]
   {:ws {:channel (get-in db [:server :channel])
         :message {:type      :signaling/new-ice-candidate
                   :target    (if (get-in db [:match :caller?]) :callee :caller)
                   :room-uuid (get-in db [:match :room-uuid])
                   :candidate (when candidate
                                (.toJSON candidate))}}}))

(rf/reg-event-fx
 :set-remote-stream
 (fn [{:keys [db]} [_ stream]]
   {:db (assoc db :remote-stream stream)
    :remote-stream stream}))

(rf/reg-event-db
 :remote-audio-initialized
 (fn [db [_ audio]]
   (assoc db :remote-audio audio)))

(rf/reg-fx
 :close-local-stream
 (fn [local-stream]
   (doall (map (fn [track]
                 (.stop track)) (js->clj (.getTracks local-stream))))))

(rf/reg-fx
 :close-call
 (fn [{:keys [audio peer-connection local-stream]}]
   (when peer-connection
     (set! (.-onaddstream peer-connection) nil)
     (set! (.-onaddtrack peer-connection) nil)
     (set! (.-onicecandidate peer-connection) nil)
     (set! (.-onaddstream peer-connection) nil)
     (doall (map (fn [track] 
                   #_(println "stopping track")
                   (.stop track)) (js->clj (.getTracks local-stream))))
     (set! (.-srcObject audio) nil)
     (set! (.-src audio) nil)
     (.close peer-connection))))

(rf/reg-event-fx
 :hangup
 (fn [{:keys [db]} [_ got-hungup?]]
   (let [output-map
         {:close-call {:audio           (:remote-audio db)
                       :peer-connection (:peer-connection db)
                       :local-stream    (get-in db [:user-media :stream])}
          :db         (-> db
                          (dissoc :match)
                          (dissoc :queue)
                          (dissoc :remote-audio)
                          (dissoc :peer-connection))}]
     (if-not got-hungup?
       (assoc output-map :ws {:channel (get-in db [:server :channel])
                              :message {:type      :signaling/hangup
                                        :room-uuid (get-in db [:match :room-uuid])
                                        :caller?   (get-in db [:match :caller?])}})
       output-map))))

(rf/reg-event-db
 :ice-state
 (fn [db [_ state]]
   (assoc db :ice-state state)))
