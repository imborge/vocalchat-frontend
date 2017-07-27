(ns vocalchat-frontend.subs
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [re-frame.core :as rf]))

(rf/reg-sub
 :active-panel
 (fn [db _]
   (:active-panel db)))

(rf/reg-sub
 :server/connected?
 (fn [db _]
   (get-in db [:server :connected?])))

(rf/reg-sub
 :status
 (fn [db _]
   (get-in db [:user-media :state])))

(rf/reg-sub
 :remote-stream
 (fn [db _]
   (:remote-stream db)))

(rf/reg-sub
 :queue
 (fn [db _]
   (:queue db)))
