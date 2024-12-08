(ns com.fulcrologic.devtools.protocols-test
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.protocols :as dp]
    [fulcro-spec.core :refer [=> assertions specification]]))

(def called (volatile! #{}))

(deftype FakeConn []
  dp/DevToolConnection
  (-on-status-change [this callback] (vswap! called conj ['on-status-change callback]))
  (-transmit! [this EQL] (vswap! called conj ['transmit EQL])
    (async/chan)))

(defn cb [])

(specification "DevToolConnection wrapper functions"
  (vreset! called #{})
  (let [conn (->FakeConn)]
    (dp/on-status-change conn cb)
    (dp/transmit! conn [:a])

    (assertions
      "on-status-change pass through to the instance"
      (contains? @called ['on-status-change cb]) => true
      "transmit! pass through to the instance"
      (contains? @called ['transmit [:a]]) => true)))
