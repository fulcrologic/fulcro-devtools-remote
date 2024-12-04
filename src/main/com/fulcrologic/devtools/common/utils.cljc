(ns com.fulcrologic.devtools.common.utils
  #?(:cljs (:require-macros com.fulcrologic.devtools.common.utils))
  (:require
    #?@(:cljs [[goog.object :as gobj]])
    [com.fulcrologic.devtools.common.schemas]
    [com.fulcrologic.guardrails.malli.core :refer [>defn => ?]]
    [clojure.walk :refer [prewalk]]))

(defn strip-lambdas
  "Replace functions in data structures from data structure x with their name or `\"FN\"`. "
  [x]
  (prewalk (fn [ele] (if (fn? ele)
                       (or
                         (:name (meta ele))
                         "FN")
                       ele)) x))

(>defn isoget
  "Like get, but for js objects, and in CLJC. In clj, it is just `get`. In cljs it is
  `gobj/get`."
  ([obj k]
   [(? :cljc/map) :cljc/map-key => :any]
   (isoget obj k nil))
  ([obj k default]
   [(? :cljc/map) :cljc/map-key :any => :any]
   (let [sk (name k)]
     #?(:clj  (get obj k (get obj sk default))
        :cljs (or (gobj/get obj sk) (get obj k default))))))

(>defn isoget-in
  "Like get-in, but for js objects, and in CLJC. In clj, it is just get-in. In cljs it is
  gobj/getValueByKeys."
  ([obj kvs]
   [(? :cljc/map) [:vector {:error/message "kvs must be a vector"} :cljc/map-key] => :any]
   (isoget-in obj kvs nil))
  ([obj kvs default]
   [(? :cljc/map) [:vector {:error/message "kvs must be a vector"} :cljc/map-key] :any => :any]
   #?(:clj (get-in obj kvs (get-in obj (mapv name kvs) default))
      :cljs
      (or (apply gobj/getValueByKeys obj (mapv name kvs)) default))))

