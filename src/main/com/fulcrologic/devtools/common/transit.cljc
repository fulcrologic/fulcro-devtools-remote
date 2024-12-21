(ns com.fulcrologic.devtools.common.transit
  (:refer-clojure :exclude [read])
  (:require
    [com.fulcrologic.devtools.common.utils :refer [strip-lambdas]]
    [com.fulcrologic.fulcro.algorithms.transit :as ft]
    [taoensso.timbre :as log]))

(defn read [s] (ft/transit-str->clj s))

(defn write [x]
  (try
    (ft/transit-clj->str (strip-lambdas x) {:metadata? false})
    (catch #?(:cljs :default :clj Throwable) e
      (log/error e "Failed to serialize" x))))
