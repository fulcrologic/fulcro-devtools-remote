(ns com.fulcrologic.devtools.electron.target
  (:require
    [cljs.core.async :as async :refer [<! >!] :refer-macros [go go-loop]]
    [com.fulcrologic.devtools.transit :as encode]
    [taoensso.encore :as enc]
    [taoensso.sente :as sente]
    [taoensso.timbre :as log]))

(defonce started?* (atom false))
(defonce send-ch #?(:clj nil :cljs (async/chan (async/dropping-buffer 50000))))

(deftype TransitPacker []
  taoensso.sente.interfaces/IPacker
  (pack [_ x] (encode/write x))
  (unpack [_ s] (encode/read s)))

(defn make-packer [] (->TransitPacker))

(goog-define SERVER_PORT "8237")
(goog-define SERVER_HOST "localhost")

(defonce sente-socket-client (atom nil))

(def backoff-ms #(enc/exp-backoff % {:max 1000}))

(defn start-ws-messaging!
  [& [{:keys [channel-type] :or {channel-type :auto}}]]
  (when-not @sente-socket-client
    (reset! sente-socket-client
      (let [socket-client-opts {:type           channel-type
                                :host           SERVER_HOST
                                :port           SERVER_PORT
                                :packer         (make-packer)
                                :wrap-recv-evs? false
                                :backoff-ms-fn  backoff-ms}]
        (sente/make-channel-socket-client! "/chsk" "no-token-desired"
          (if (= (:protocol (enc/get-win-loc)) "file:")
            (assoc socket-client-opts :protocol :http)
            socket-client-opts))))
    (log/debug "Starting websockets at:" SERVER_HOST ":" SERVER_PORT)
    (go-loop [attempt 1]
      (if-not @sente-socket-client
        (log/info "Shutting down inspect ws async loops.")
        (let [{:keys [state send-fn]} @sente-socket-client
              open? (:open? @state)]
          (if open?
            (when-let [[type data] (<! send-ch)]
              (send-fn [:fulcro.inspect/message {:type type :data data :timestamp (js/Date.)}]))
            (do
              (log/trace (str "Waiting for channel to be ready"))
              (async/<! (async/timeout (backoff-ms attempt)))))
          (recur (if open? 1 (inc attempt))))))
    (go-loop [attempt 1]
      (if-not @sente-socket-client
        (log/info "Shutting down inspect ws async loops.")
        (let [{:keys [state ch-recv]} @sente-socket-client
              open? (:open? @state)]
          (if open?
            (enc/when-let [[event-type message] (:event (<! ch-recv))
                           _   (= :fulcro.inspect/event event-type)
                           msg message]
              (handle-devtool-message msg))
            (do
              (log/trace (str "Waiting for channel to be ready"))
              (async/<! (async/timeout (backoff-ms attempt)))))
          (recur (if open? 1 (inc attempt))))))))

(defn install-ws []
  (when-not started?*
    (log/info "Installing Fulcro 3.x Inspect over Websockets targeting port " SERVER_PORT)
    (reset! started?* true)
    (start-ws-messaging!)))

(defn stop-ws []
  (log/info "Shutting down inspect websockets.")
  (reset! sente-socket-client nil)
  (reset! started?* false))
