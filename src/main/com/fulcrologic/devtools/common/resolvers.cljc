(ns com.fulcrologic.devtools.common.resolvers
  "Support for responding to EQL requests from the other end of the connection. If you are working on the target
   side tooling, things defined as resolvers will handle requests FROM the devtool. If you're working on the devtool
   then resolvers will be things that will handle requests FROM the target application.

   In all cases the idea is that hot code reload should refresh your async processing so that you don't have to reload
   the entire all (target side). Unfortunately, chrome extension dev tools cannot do hot code reload (eval is not allowed)
   and you must manually reload them on each change."
  #?(:cljs (:require-macros com.fulcrologic.devtools.common.resolvers))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as futil]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]))

(defonce pathom-registry (atom {}))
(defn register! [resolver] (swap! pathom-registry assoc (::pc/sym resolver) resolver))

(s/def ::check (s/or :sym symbol? :expr list?))
(s/def ::mutation-args (s/cat
                         :sym simple-symbol?
                         :doc (s/? string?)
                         :arglist vector?
                         :config map?
                         :body (s/* any?)))

(defn defpathom-backend-endpoint* [endpoint args update-database?]
  (let [{:keys [sym arglist doc config body]} (futil/conform! ::mutation-args args)
        internal-fn-sym (symbol (str (name sym) "__internal-fn__"))
        env-arg         (first arglist)
        fqsym           (symbol (name (ns-name *ns*)) (name sym))
        params-arg      (second arglist)]
    `(do
       ;; Use this internal function so we can dynamically update a resolver in
       ;; dev without having to restart the whole pathom parser.
       (defn ~internal-fn-sym [env# params#]
         (let [~env-arg env#
               ~params-arg params#
               result# (do ~@body)]
           ;; Pathom doesn't expect nils
           (cond
             (sequential? result#) (vec (remove nil? result#))
             (nil? result#) {}
             :else result#)))
       (~endpoint ~(cond-> sym
                     doc (with-meta {:doc doc})) [env# params#]
         ~config
         (~internal-fn-sym env# params#))
       (register! ~sym)
       :ok)))

(defmacro
  ^{:doc      "Defines a server-side PATHOM mutation.

               Example:

               (defmutation do-thing
                 \"Optional docstring\"
                 [params]
                 {::pc/input [:param/name]  ; PATHOM config (optional)
                  ::pc/output [:result/prop]
                  :check security-check}
                 ...)  ; actual action (required)"
    :arglists '([sym docstring? arglist config & body])} defmutation
  [& args]
  (defpathom-backend-endpoint* `pc/defmutation args true))

(defmacro ^{:doc      "Defines a pathom resolver but with authorization.

                        Example:

                        (defresolver resolver-name [env input]
                          {::pc/input [:customer/id]
                           :check s.checks/ownership
                           ...}
                          {:customer/name \"Bob\"})
                        "
            :arglists '([sym docstring? arglist config & body])} defresolver
  [& args]
  (defpathom-backend-endpoint* `pc/defresolver args false))

(defn build-parser []
  (p/async-parser {::p/mutate  pc/mutate-async
                   ::p/env     {::p/reader [p/map-reader
                                            pc/async-reader2
                                            pc/open-ident-reader]}
                   ::p/plugins [(pc/connect-plugin {::pc/register (vals @pathom-registry)})
                                p/error-handler-plugin]}))

(let [static-parser (build-parser)]
  (defn process-async-request
    "Used in the handling of requests from the 'other side' of the connection."
    [env EQL]
    (if #?(:clj true :cljs goog.DEBUG)
      ((build-parser) env EQL)
      (static-parser env EQL))))

#?(:clj
   (defmacro remote-mutations [& syms]
     (let [declarations (mapv
                          (fn [sym]
                            `(m/defmutation ~sym [_#]
                               (~'devtool-remote [_env#] true)))
                          syms)]
       `(do
          ~@declarations))))

