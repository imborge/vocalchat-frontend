(ns vocalchat-frontend.views
  (:require [reagent.core :as r]
            [re-frame.core :as rf]))


;; home

(defn queue-form []
  (r/with-let [lang (r/atom :spanish)
               skill-level (r/atom :conversational)]
    [:div#queue-form
     [:form {:on-submit #(do
                           (.preventDefault %)
                           (rf/dispatch [:queue/init @lang @skill-level]))}
      [:select.language {:value @lang
                         :on-change #(reset! lang (-> % .-target .-value keyword))}
       [:option {:value :english} "English"]
       [:option {:value :spanish} "Spanish"]
       [:option {:value :french} "French"]
       [:option {:value :german} "German"]]
      [:select.skill {:value @skill-level
                      :on-change #(reset! skill-level (-> % .-target .-value keyword))}
       [:option {:value :beginner} "Beginner"]
       [:option {:value :conversational} "Conversational"]
       [:option {:value :fluent} "Fluent"]]
      [:button [:i.fa.fa-phone] " Start talking"]]]))

(defn queue-wait-panel [queue-info]
  [:div#wait-box
   [:h1 "In queue"]
   [:p "You are queued for " (:language @queue-info) " (" (:skill-level @queue-info) ")"]
   [:button {:on-click (fn [event]
                         (.preventDefault event)
                         (rf/dispatch [:queue/unqueue]))} "Leave queue"]])

(defn in-call-panel []
  (let [ice-state (rf/subscribe [:ice-state])]
    [:div#call-box
     [:h1 "In call"]
     [:p "State: " @ice-state]
     [:button {:on-click (fn [event]
                           (.preventDefault event)
                           (rf/dispatch [:hangup]))} "Hang up"]]))

(defn home-panel []
  (let [status     (rf/subscribe [:status])
        queue-info (rf/subscribe [:queue])]
   [:div
    #_[:h1 "Talk your way to language mastery"]
    [:h1 "Talk your way to fluency"]
    (when (= :error @status)
      [:div.error
       [:h3 "An error occured."]
       [:p "We couldn't access your microphone, please try again in Chrome or Firefox."]])
    (condp = (:status @queue-info) 
      :queued [queue-wait-panel queue-info]
      :matched [in-call-panel]
      [queue-form])
    #_[:p "When you are learning a language, nothing beats speaking it with others. Vocal.chat sets up a voice chat between you and another so you can talk to each other."]
    #_[:h2 "Don't know what to talk about? Don't worry!"]
    #_[:p "We'll suggest topics you can talk about, depending on what skill level you choose."]
    #_[:h2 "How does it work?"]
    #_[:p "Vocal.chat sets up a voice chat between you and a stranger. When you start a voice chat, the system matches you with another person who wants to practice the same language, at the same skill level, and a voice chat is started when a match is found."]]))


;; about

(defn about-panel []
  (fn []
    [:div 
     [:h1 "About Vocal.chat"]
     [:p "Vocal.chat is a platform that lets you speak with others to practice a language."]]))


;; main

(defn- panels [panel-name]
  (case panel-name
    :home-panel [home-panel]
    :about-panel [about-panel]
    [:div]))

(defn show-panel [panel-name]
  [panels panel-name])

(defn header []
  (r/with-let [connected? (rf/subscribe [:server/connected?])]
    [:div#top
     [:div.inner
      [:h1.logo
       [:a {:href "#"} "Vocal.Chat"
        [:span "BETA"]]]
      [:nav
       [:ul
        [:li
         [:a {:href "#"} "Home"]]
        [:li
         [:a {:href "#/about"} "About"]]
        [:li (if @connected?
               {:class "connection connected"}
               {:class "connection disconnected"})]]]]]))

(defn footer []
  [:footer
   [:div.inner
    [:p "Vocal.chat - Voice chat for language learners - Created by Børge André Jensen"]]])

(defn main-panel []
  (r/with-let [active-panel (rf/subscribe [:active-panel])
               remote-stream (rf/subscribe [:remote-stream])]
    (fn []
      [:div
       [header]
       [:div.container
        [show-panel @active-panel]]
       [footer]])))
