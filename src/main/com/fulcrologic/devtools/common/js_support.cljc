(ns com.fulcrologic.devtools.common.js-support
  #?(:cljs (:require-macros com.fulcrologic.devtools.common.js-support)))

(defn cljs?
  "Returns true when env is a cljs macro &env"
  [env]
  (boolean (:ns env)))

#?(:clj (declare clj->js))

#?(:clj
   (defn key->js
     ([k] (key->js k clj->js))
     ([k primitive-fn]
      (cond
        (or (string? k)
          (number? k)
          (keyword? k)
          (symbol? k)) (primitive-fn k)
        :default (pr-str k)))))

#?(:clj
   (defn clj->js
     "Recursively transforms ClojureScript values to JavaScript.
     sets/vectors/lists become Arrays, Keywords and Symbol become Strings,
     Maps become Objects. Arbitrary keys are encoded to by `key->js`.
     Options is a key-value pair, where the only valid key is
     :keyword-fn, which should point to a single-argument function to be
     called on keyword keys. Default to `name`."
     [x & {:keys [keyword-fn]
           :or   {keyword-fn name}
           :as   options}]
     (letfn [(keyfn [k] (key->js k thisfn))
             (thisfn [x] (cond
                           (nil? x) nil
                           (keyword? x) (keyword-fn x)
                           (symbol? x) (str x)
                           (map? x) (reduce-kv
                                      (fn [acc k v] (assoc acc (keyfn k) (thisfn v)))
                                      (with-meta {} {:mockjs? true})
                                      x)
                           (coll? x) (with-meta (vec x) {:mockjs? true})
                           :else x))]
       (thisfn x))))

(defmacro js [obj]
  (if (cljs? &env)
    `(cljs.core/clj->js ~obj)
    `(com.fulcrologic.devtools.common.js-support/clj->js ~obj)))

(defn js? [x]
  (boolean
    #?(:clj  (or (number? x) (string? x) (:mockjs? (meta x)))
       :cljs (or (number? x) (string? x) (array? x) (object? x)))))

(defn log! [& args]
  #?(:clj
     (apply println args)
     :cljs
     (apply js/console.log args)))
