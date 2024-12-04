(ns com.fulcrologic.devtools.electron.renderer.main
  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.transit :as encode]
    [com.fulcrologic.fulcro.application]
    [com.fulcrologic.fulcro.networking.mock-server-remote :as mock-net]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defonce active-requests (volatile! {}))

(def ^:js ipcRenderer js/window.ipcRenderer)

(defn send-to-target! [target-id request-id EQL]
  (.send ipcRenderer "event"
    #js {"data" (encode/write {mk/request    EQL
                               mk/target-id  target-id
                               mk/request-id request-id})}))

(defn event-loop! [push-handler]
  (.on ipcRenderer "event"
    (fn [^js event]
      (log/info "Event from BG server" (encode/read (.-data event))))))

(defn devtools-websocket-remote [push-notification-handler]
  (log/info "Devtool websocket created")
  (event-loop! push-notification-handler)
  (mock-net/mock-http-server
    {:parser (fn [EQL]
               (let [{:keys [params] :as ast} (eql/query->ast1 EQL)
                     request-id       (random-uuid)
                     target-id        (mk/target-id params)
                     response-channel (async/chan)]
                 (vswap! active-requests assoc request-id response-channel)
                 (send-to-target! target-id request-id EQL)
                 (async/go
                   (let [timer (async/timeout 10000)
                         [result c] (async/alts! [response-channel timer] :priority true)]
                     (if (= c response-channel)
                       (mk/response result)
                       {mk/error "Timed out"})))))}))
