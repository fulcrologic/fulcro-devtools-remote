(ns com.fulcrologic.devtools.electron.websockets
  (:require [cljs.core.async :as async :refer [<! >!] :refer-macros [go go-loop]]
            [com.fulcrologic.devtools.common.message-keys :as mk]
            [com.fulcrologic.devtools.common.target :as target]
            [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
            [com.fulcrologic.fulcro.inspect.inspect-ws :as fiws]
            [taoensso.encore :as enc]
            [taoensso.sente :as sente]
            [taoensso.timbre :as log]))

(defn start-ws-messaging!
  [& [{:keys [channel-type] :or {channel-type :auto}}]]
  (when-not @fiws/sente-socket-client
    (reset! fiws/sente-socket-client
      (let [socket-client-opts {:type           channel-type
                                :host           fiws/SERVER_HOST
                                :port           fiws/SERVER_PORT
                                :protocol       :http
                                :packer         (fiws/make-packer)
                                :wrap-recv-evs? false
                                :backoff-ms-fn  fiws/backoff-ms}]
        (sente/make-channel-socket-client! "/chsk" "no-token-desired"
          socket-client-opts)))
    (log/debug "Starting websockets at:" fiws/SERVER_HOST ":" fiws/SERVER_PORT)
    (go-loop [attempt 1]
      (if-not @fiws/sente-socket-client
        (log/info "Shutting down inspect ws async loops.")
        (let [{:keys [state send-fn]} @fiws/sente-socket-client
              open? (:open? @state)]
          (if open?
            (when-let [[type data :as msg] (<! inspect/send-ch)]
              (log/info "Sending" msg)
              (send-fn [:fulcro.inspect/message {:type type :data data :timestamp (js/Date.)}]))
            (do
              (log/trace (str "Waiting for channel to be ready"))
              (async/<! (async/timeout (fiws/backoff-ms attempt)))))
          (recur (if open? 1 (inc attempt))))))
    (go-loop [attempt 1]
      (if-not @fiws/sente-socket-client
        (log/info "Shutting down inspect ws async loops.")
        (let [{:keys [state ch-recv]} @fiws/sente-socket-client
              open? (:open? @state)]
          (if open?
            (enc/when-let [[event-type message] (:event (<! ch-recv))
                           _   (= :fulcro.inspect/event event-type)
                           msg message]
              (log/info "devtool message" msg)
              (inspect/handle-devtool-message msg))
            (do
              (log/trace (str "Waiting for channel to be ready"))
              (async/<! (async/timeout (fiws/backoff-ms attempt)))))
          (recur (if open? 1 (inc attempt))))))))

(defn install-ws []
  (js/console.log "Starting Fulcro Inspect over Websockets to devtool")
  (reset! inspect/started?* true)
  (start-ws-messaging!))

(defonce started (install-ws))

#_#_#_(deftype WebsocketConnection [my-target-uuid active-requests]
  target/DevToolConnection
  (transmit! [this EQL]
    ;; TASK: Implement capturing the return value
    (let [id               (random-uuid)
          response-channel (async/chan)]
      (vswap! active-requests id response-channel)
      (TODO! my-target-uuid {mk/request    EQL
                             mk/request-id id})
      (async/go
        (let [timeout (async/timeout 10000)
              [r channel] (async/alts! [response-channel timeout] :priority true)]
          (if (= channel timeout)
            (do
              (log/error "Request to devtool timed out" EQL)
              {mk/error "Request timed out"})
            r))))))

(deftype WebsocketDevtoolConnectionFactory []
  target/DevToolConnectionFactory
  (-connect! [this target-description async-pathom-processor]
    ;; TASK: Register a target ID
    (let [target-id (random-uuid)]
      ;; TASK: Connect async processor for incoming requests
      (->WebsocketConnection target-id (volatile! {})))))

(target/set-factory! (->WebsocketDevtoolConnectionFactory))
