(ns com.fulcrologic.devtools.electron.target
  (:require
    [cljs.core.async :as async :refer [<! >!] :refer-macros [go go-loop]]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.connection :as cc]
    [com.fulcrologic.devtools.common.target :as target]
    [com.fulcrologic.devtools.common.transit :as encode]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn ?]]
    [taoensso.encore :as enc]
    [taoensso.sente :as sente]
    [taoensso.timbre :as log]))

(deftype TransitPacker []
  taoensso.sente.interfaces/IPacker
  (pack [_ x] (encode/write x))
  (unpack [_ s] (encode/read s)))

(defn make-packer [] (->TransitPacker))

(goog-define SERVER_PORT "8237")
(goog-define SERVER_HOST "localhost")

(def backoff-ms #(enc/exp-backoff % {:max 1000}))

(>defn start-ws-messaging!
  [^clj conn]
  [::dp/DevToolConnection => :any]
  (let [vconfig (.-vconfig conn)
        {:keys [target-id sente-socket-client send-ch]} (cc/connection-config conn)]
    (when-not sente-socket-client
      (log/info "Connecting to websockets")
      (try
        (vswap! (.-vconfig conn) assoc :sente-socket-client
          (let [socket-client-opts {:type           :auto
                                    :protocol       :http
                                    :host           SERVER_HOST
                                    :port           SERVER_PORT
                                    :client-id      (pr-str target-id)
                                    :packer         (make-packer)
                                    :wrap-recv-evs? false
                                    :backoff-ms-fn  backoff-ms}
                client             (sente/make-channel-socket-client! "/chsk" "no-token-desired"
                                     (if (= (:protocol (enc/get-win-loc)) "https:")
                                       (assoc socket-client-opts :protocol :https)
                                       socket-client-opts))]
            (add-watch (:state client) ::open-watch
              (fn [_ _ _ {:keys [open?]}]
                (async/go
                  (async/>! (:ch-recv client) [:fulcrologic/devtool {mk/connected? open?
                                                                     mk/target-id  target-id}]))))
            client))
        (catch :default e
          (log/error e)))
      (log/info "Starting websockets at:" SERVER_HOST ":" SERVER_PORT)
      (go-loop [attempt 1]
        (let [client (get @vconfig :sente-socket-client)]
          (if-not client
            (log/info "Shutting down inspect ws async loops.")
            (let [{:keys [state send-fn]} client
                  open? (:open? @state)]
              (if open?
                (when-let [data (<! send-ch)]
                  (send-fn [:fulcrologic.devtool/event data]))
                (do
                  (log/trace (str "Waiting for channel to be ready"))
                  (async/<! (async/timeout (backoff-ms attempt)))))
              (recur (if open? 1 (inc attempt)))))))
      (go-loop [attempt 1]
        (let [client (get @vconfig :sente-socket-client)]
          (if-not client
            (log/info "Shutting down inspect ws async loops.")
            (let [{:keys [state ch-recv]} client
                  open? (:open? @state)]
              (if open?
                (let [[event-type message] (:event (<! ch-recv))]
                  (when (= :fulcrologic.devtool/event event-type)
                    (cc/handle-devtool-message conn message)))
                (do
                  (log/trace (str "Waiting for channel to be ready"))
                  (async/<! (async/timeout (backoff-ms attempt)))))
              (recur (if open? 1 (inc attempt))))))))))

(deftype WebsocketClientConnectionFactory []
  dp/DevToolConnectionFactory
  (-connect! [this {:keys [target-id] :as config}]
    (let [target-id (or target-id (random-uuid))
          vconfig   (volatile! (assoc config
                                 :send-ch (async/dropping-buffer 10000)
                                 :active-requests {}
                                 :target-id target-id))
          conn      (cc/->Connection vconfig)]
      (start-ws-messaging! conn)
      conn)))

(defn install! []
  (target/set-factory! (->WebsocketClientConnectionFactory)))