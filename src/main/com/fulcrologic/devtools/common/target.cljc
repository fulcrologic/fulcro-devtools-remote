(ns com.fulcrologic.devtools.common.target
  "
  Target helpers for installing devtool connections in dev environments, and redacting all tool code in production builds.

  The `ido` and `ilet` macros in the ns emit nothing when goog.DEBUG and INSPECT (in this ns) are false-ish. In CLJS, this forces the
  tool code to be present in all but release builds.

  But, you can ALWAYS enable tools in production by adding the following to your shadow-cljs compiler config:

  :closure-defines {\"com.fulcrologic.devtools.target.INSPECT\" true}

  Setting INSPECT to the value \"disabled\" FORCES these macros to emit nothing, ignoring other settings. Letting
  you turn off tooling for things like performance profiling.

  If your tool TARGET is CLJ, then use Java properties
  \"com.fulcrologic.devtools.target.DEBUG\" and \"com.fulcrologic.devtools.target.INSPECT\" for the same effects.

  To use this namespace, make sure you require the correct preload for establishing the kind of devtool connection
  you expect (e.g. chrome.target, or electron.target).
  "
  #?(:cljs (:require-macros com.fulcrologic.devtools.common.target))
  (:require
    [com.fulcrologic.devtools.common.fulcro-devtool-remote :as fdr]
    [com.fulcrologic.devtools.common.resolvers :as res]
    [com.fulcrologic.devtools.common.schemas]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.guardrails.malli.core :refer [>defn => ?]]
    [taoensso.timbre :as log]))

(def INSPECT
  #?(:clj  (System/getProperty "com.fulcrologic.devtools.enabled")
     :cljs (goog-define INSPECT false)))

(def DEBUG #?(:cljs goog.DEBUG :clj (or
                                      (System/getProperty "test")
                                      (System/getProperty "dev"))))

#?(:clj
   (defn cljs?
     "Returns true when env is a cljs macro &env"
     [env]
     (boolean (:ns env))))

#?(:clj
   (defmacro ido
     "Like `do`, but acts like a comment when devtools are disabled (See ns docstring)."
     [& body]
     (if (cljs? &env)
       `(when (and (or DEBUG INSPECT) (not= "disabled" INSPECT))
          (try
            ~@body
            (catch :default ~'e)))
       `(when (and (or DEBUG INSPECT) (not= "disabled" INSPECT))
          (try
            ~@body
            (catch Throwable ~'e))))))

#?(:clj
   (defmacro ilet
     "Like `clojure.core/let`, but acts like `comment` when disabled. See ns docstring."
     [bindings & body]
     (when (cljs? &env)
       `(ido
          (let ~bindings
            ~@body)))))


(def -current-devtool-connection-factory-atom (atom nil))
(>defn set-factory!
  "Configure the dev tool connection factory. Use one of the preloads to do this for you."
  [f]
  [::dp/DevToolConnectionFactory => :nil]
  (reset! -current-devtool-connection-factory-atom f)
  nil)

(>defn connect!
  "Create a connection to a Devtool, if available. See ns docstring for
   instructions on enabling/disabling tooling.

   Returns a DevToolConnection, which can be used directly to transmit requests, or can be wrapped as a Fulcro Remote.

   `tool-type` is available in case you have more than one kind of chrome extension, so you can specify which to connect to.
   `target-description` is a string that will be sent to label the target.

   The default async-processor is the one from the resolvers namespace, and the env for such resolvers will include
   the `:devtool/connection`.

   Will do NOTHING if the devtool connection factory has not been initialized. Be sure you have done a preload
   that calls the `set-factory!` function of this ns.

   Returns a DevToolConnection (see protocols) against which you can interact, or nil if tools are disabled."
  [{:keys [target-id tool-type description async-processor] :as options}]
  [[:map
    [:target-id {:optional true} :uuid]
    [:tool-type :qualified-keyword]
    [:description :string]
    [:async-processor {:optional true} fn?]] => (? ::dp/DevToolConnection)]
  (com.fulcrologic.devtools.common.target/ido
    (log/info "Devtool attempting to connect")
    (if @-current-devtool-connection-factory-atom
      (let [c               (volatile! nil)
            async-processor (or async-processor
                              (fn [EQL] (res/process-async-request {:devtool/connection @c} EQL)))
            options         (assoc options :async-processor async-processor)
            connection      (dp/-connect! @-current-devtool-connection-factory-atom options)]
        (vreset! c connection)
        connection)
      (log/warn "Cannot connect to devtool. No factory set. Did you make sure a preload was loaded?"))))

(defn add-devtool-remote!
  "If your target is Fulcro, this will add a Devtool remote to your `app` so you can talk to the devtool using
   normal Fulcro `transact!` and `load`. The env in the resolvers will include :fulcro/app and :devtool/connection."
  [app app-name]
  (let [app-id     (::app/id app)
        c          (volatile! nil)
        connection (connect! {:target-id       app-id
                              :tool-type       :dev/tool
                              :description     app-name
                              :async-processor (fn [EQL]
                                                 (res/process-async-request {:fulcro/app         app
                                                                             :devtool/connection @c} EQL))})]
    (vreset! c connection)
    (when connection
      (app/set-remote! app :devtool-remote (fdr/devtool-remote connection)))))
