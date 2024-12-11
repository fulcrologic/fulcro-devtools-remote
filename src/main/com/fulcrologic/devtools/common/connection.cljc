(ns com.fulcrologic.devtools.common.connection
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.common.built-in-mutations :as bi]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.schemas :as schema]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [taoensso.timbre :as log]))

(>defn connection-config [^Object conn]
  [::dp/DevToolConnection => [:map
                              [:active-requests {:optional true} [:map-of :uuid :async/channel]]
                              [:target-id {:optional true} :uuid]
                              [:description {:optional true} :string]
                              [:send-ch :async/channel]
                              [:async-processor fn?]]]
  (deref (.-vconfig conn)))

(>defn handle-response [^Object conn {::mk/keys [request-id response] :as message}]
  [::dp/DevToolConnection [:or ::schema/devtool-error ::schema/devtool-response] => :any]
  (let [{:keys [active-requests]} (connection-config conn)
        chan (get active-requests request-id)]
    (when chan
      (try
        (vswap! (.-vconfig conn) update :active-requests dissoc request-id)
        (async/go
          (async/>! chan (or response (select-keys message [mk/error]))))
        (finally
          (async/close! chan))))))

(>defn handle-devtool-request [conn {::mk/keys [target-id request-id request]}]
  [::dp/DevToolConnection ::schema/devtool-request => :any]
  (let [{:keys [async-processor send-ch]} (connection-config conn)]
    (async/go
      (try
        (let [result (async/<! (async-processor request))]
          (async/>! send-ch {mk/request-id request-id
                             mk/target-id  target-id
                             mk/response   result}))
        (catch :default e
          (log/error e "Devtool client side processor failed.")
          (async/>! send-ch {mk/request-id request-id
                             mk/target-id  target-id
                             mk/error      (ex-message e)}))))))

(defn- handle-devtool-message [^:clj conn message]
  [::dp/DevToolConnection ::schema/devtool-message => :any]
  (let [{my-uuid :target-id
         :keys   [async-processor active-requests]} (connection-config conn)
        connected? (log/spy :info (mk/connected? message))
        target-id  (log/spy :info (mk/target-id message))]
    (if (some? connected?)
      (async-processor [(bi/devtool-connected {:connected? connected?})])
      ;; TASK: need to distinguish between server and client on my uuid...I think this is right?
      (when (or (nil? my-uuid) (= my-uuid target-id))
        (let [EQL        (log/spy :info (mk/request message))
              request-id (log/spy :info (mk/request-id message))]
          (cond
            (contains? active-requests request-id) (handle-response conn message)
            (and EQL request-id) (handle-devtool-request conn message)
            :else (log/error message)))))))

(deftype Connection [vconfig]
  dp/DevToolConnection
  (-transmit! [this target-id EQL]
    (let [response-channel (async/chan)
          request-id       (random-uuid)
          {:keys [target-id send-ch]
           :or   {target-id target-id}} (connection-config this)]
      (vswap! vconfig update :active-requests assoc request-id response-channel)
      (async/go
        (async/>! send-ch {mk/request    EQL
                           mk/target-id  target-id
                           mk/request-id request-id})
        (let [timeout (async/timeout 10000)
              [r channel] (async/alts! [response-channel timeout] :priority true)]
          (if (= channel timeout)
            (do
              (log/error "Request to devtool timed out" EQL)
              {mk/error "Request timed out"})
            r))))))

