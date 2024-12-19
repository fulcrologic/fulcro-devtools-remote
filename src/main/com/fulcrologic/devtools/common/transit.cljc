(ns com.fulcrologic.devtools.common.transit
  (:refer-clojure :exclude [read])
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as ft]
    [com.fulcrologic.devtools.common.utils :refer [strip-lambdas]]))

(defn read [s] (ft/transit-str->clj s))

(defn write [x] (ft/transit-clj->str (strip-lambdas x) {:metadata? false}))
