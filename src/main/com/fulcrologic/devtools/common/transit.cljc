(ns com.fulcrologic.devtools.common.transit
  (:refer-clojure :exclude [read])
  (:require
    [com.fulcrologic.devtools.common.utils :refer [strip-lambdas]]
    #?(:cljs
       [com.fulcrologic.fulcro.inspect.transit :as it]
       :clj [com.fulcrologic.fulcro.algorithms.transit :as ft])
    [taoensso.timbre :as log]))

(defn read [s] #?(:cljs (it/read s)
                  :clj  (ft/transit-str->clj s)))

(defn write [x]
  (try
    #?(:cljs (it/write x)
       :clj (ft/transit-clj->str (strip-lambdas x) {:metadata? false}))
    (catch #?(:cljs :default :clj Throwable) e
      (log/error e "Failed to serialize" x))))
