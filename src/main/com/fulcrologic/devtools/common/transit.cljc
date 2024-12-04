(ns com.fulcrologic.devtools.common.transit
  (:refer-clojure :exclude [read])
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as ft]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]))

(>defn read [s] [:string => :any] (ft/transit-str->clj s))

(>defn write [x] [:any => :string] (ft/transit-clj->str x {:metadata? false}))
