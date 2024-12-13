(ns com.fulcrologic.devtools.common.fulcro-devtool-remote
  "A Fulcro Remote that will work as long as you supply it a valid DevtoolConnection. Such connections can be made
   from the devtool side, or the target application side (if it happens to be written in Fulcro). Of course you
   may also just use the raw dev connection in non-Fulcro apps."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.schemas]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.application]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(>defn eql-target-id
  "Returns the mk/target-id embedded in the params of the first node of the given EQL expression."
  [EQL]
  [:EQL/expression => (? :uuid)]
  (let [{:keys [params]} (eql/query->ast1 EQL)]
    (mk/target-id params)))

(defn devtool-remote
  "A Fulcro remote that will send/receive traffic using the given DevToolConnection. The `push-handler` will
   be called with an EQL transaction if the other end of the connection pushes a request; otherwise requests
   pushed through this remote will reach the other end as EQL requests.

   Both directions must include an `mk/target-id`. If 'this' end is the target, it sends its own target ID, and
   if 'this' end is the devtool, it indicates WHICH target it wants to talk to this way.

   Messages that are not responses to requests from 'this' end will be instead passed to your
   `push-notification-handler`, which is a `(fn [msg])`.

   Specifying the target id in a request (e.g. from a `df/load` or `comp/transact!`) requires that you include the
   desired `target-id` via the mk/target-id key in load's `:params` or the parameter map of the mutation you try to run.

   Requests have a timeout of 10 seconds."
  [devtool-connection]
  {:transmit! (fn transmit! [_ {::txn/keys [ast result-handler]}]
                (let [edn           (log/spy :info (eql/ast->query ast))
                      ok-handler    (fn [result]
                                      (try
                                        (result-handler (select-keys result #{:transaction :status-code :body :status-text}))
                                        (catch #?(:cljs :default :clj Throwable) e
                                          (log/error e "Result handler failed with an exception. See https://book.fulcrologic.com/#err-msr-res-handler-exc"))))
                      error-handler (fn [error-result]
                                      (try
                                        (result-handler (merge {:status-code 500} (select-keys error-result #{:transaction :status-code :body :status-text})))
                                        (catch #?(:cljs :default :clj Throwable) e
                                          (log/error e "Error handler failed with an exception. See https://book.fulcrologic.com/#err-msr-err-handler-exc"))))]
                  (try
                    (async/go
                      (let [target-id (eql-target-id edn)
                            result    (when target-id (async/<! (dp/transmit! devtool-connection target-id edn)))]
                        (cond
                          (nil? target-id) (error-handler {:transaction edn :status-code 500 :status-text "No target id"})
                          (mk/error result) (error-handler {:transaction edn :status-code 500 :status-text (mk/error result)})
                          :else (ok-handler {:transaction edn :status-code 200 :body (mk/response result)}))))
                    (catch #?(:cljs :default :clj Throwable) _
                      (error-handler {:transaction edn :status-code 500})))))
   :abort!    (fn abort! [this id])})
