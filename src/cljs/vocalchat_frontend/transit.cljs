(ns vocalchat-frontend.transit
  (:require [cognitect.transit :as t]))

(defn write [o]
  (let [w (t/writer :json)]
    (t/write w o)))

(defn read [s]
  (let [r (t/reader :json)]
    (t/read r s)))
