(ns com.fulcrologic.devtools.electron.background.websocket-server
  (:require
    ["electron" :as e :refer [ipcMain]]
    ["electron-settings" :as settings]
    [cljs.core.async :as async :refer [<! >! put! take!] :refer-macros [go go-loop]]
    [cljs.nodejs :as nodejs]
    [clojure.set :as set]
    [com.fulcrologic.devtools.common.built-in-mutations :as bi]
    [com.fulcrologic.devtools.common.message-keys :as mk]   ; patches goog global for node
    [com.fulcrologic.devtools.common.transit :as encode]
    [com.fulcrologic.devtools.common.transit-packer :as tp]
    [com.fulcrologic.devtools.electron.background.agpatch]
    [taoensso.encore :as enc]
    [taoensso.sente.server-adapters.community.express :as sente-express]
    [taoensso.timbre :as log]))

(defonce channel-socket-server (atom nil))
(defonce target-id->client-id (atom {}))
(defonce content-atom (atom nil))
(defonce server-atom (atom nil))
(def vwebsocket-port (volatile! 8237))

(defn websocket-port [] @vwebsocket-port)
(defn set-websocket-port! [port] (vreset! vwebsocket-port port))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Express Boilerplate Plumbing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def http (nodejs/require "http"))
(def express (nodejs/require "express"))
(def express-ws (nodejs/require "express-ws"))
(def ws (nodejs/require "ws"))
(def cors (nodejs/require "cors"))
(def ^js body-parser (nodejs/require "body-parser"))

(defn routes [^js express-app {:keys [ajax-post-fn ajax-get-or-ws-handshake-fn]}]
  (doto express-app
    (.use (cors))
    (.ws "/chsk"
      (fn [ws req next]
        (ajax-get-or-ws-handshake-fn req nil nil
          {:websocket? true
           :websocket  ws})))
    (.get "/chsk" ajax-get-or-ws-handshake-fn)
    (.post "/chsk" ajax-post-fn)
    (.use (fn [^js req res next]
            (log/warn "Unhandled request: %s" (.-originalUrl req))
            (next)))))

(defn wrap-defaults [^js express-app routes ch-server]
  (doto express-app
    (.use (fn [^js req res next]
            (log/trace "Request: %s" (.-originalUrl req))
            (next)))
    (.use (.urlencoded body-parser #js {:extended false}))
    (routes ch-server)))

(defn start-web-server! [port]
  (log/trace "Starting express...")
  (let [^js express-app       (express)
        ^js express-ws-server (express-ws express-app)]
    (wrap-defaults express-app routes @channel-socket-server)
    (let [http-server (.listen express-app port)]
      (reset! server-atom
        {:express-app express-app
         :ws-server   express-ws-server
         :http-server http-server
         :stop-fn     #(.close http-server)
         :port        port}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Real Comms Logic:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn send-to-devtool! [msg]
  (try
    (if @content-atom
      (try
        (.send @content-atom "devtool" (encode/write msg))
        (catch :default e
          (log/error e "Send failed")))
      (log/warn "Message ignored. Content atom not ready."))
    (catch :default e
      (log/error e))))

(defn send-to-target! [{::mk/keys [target-id] :as msg}]
  (if-not target-id
    (log/warn "Unable to find app-uuid in message:" msg)
    (let [{:keys [send-fn]} @channel-socket-server
          client-id (get @target-id->client-id target-id)]
      (send-fn client-id [:fulcrologic.devtool/event msg]))))

(defn start-ws! []
  (when-not @channel-socket-server
    (reset! channel-socket-server
      (sente-express/make-express-channel-socket-server!
        {:packer        (tp/make-packer {})
         :csrf-token-fn nil
         :user-id-fn    :client-id})))
  (go-loop []
    (when-some [{:keys [client-id event] :as msg} (<! (:ch-recv @channel-socket-server))]
      (let [[event-type event-data] event]
        (case event-type
          :fulcrologic.devtool/event
          (let [target-id (mk/target-id event-data)]
            (when target-id
              (swap! target-id->client-id assoc target-id client-id))
            (send-to-devtool! event-data))
          :chsk/uidport-close
          (do
            (log/spy :info msg)
            (let [cid->tid  (set/map-invert @target-id->client-id)
                  target-id (log/spy :info (cid->tid (log/spy :info client-id)))]
              (send-to-devtool! {mk/request    [(bi/devtool-connected {:connected? false
                                                                       :target-id  target-id})]
                                 mk/target-id  target-id
                                 mk/request-id (random-uuid)})
              (swap! target-id->client-id dissoc target-id)))
          (:chsk/uidport-open
            :chsk/ws-pong
            :chsk/ws-ping)
          (log/trace "ignored msg:" client-id)
          #_else
          (log/warn "Unsupported event:" event "from client:" client-id))))
    (recur))
  (async/go
    (let [port (websocket-port)]
      (log/info "Devtool listening on port " port)
      (reset! server-atom (start-web-server! port)))))

(defn restart!
  "Stop/start the websockets. Useful when changing ports."
  []
  (log/info "Stopping websockets.")
  (when @server-atom ((:stop-fn @server-atom)))
  (reset! channel-socket-server nil)
  (reset! server-atom nil)
  (start-ws!))

(defn start!
  "Call for overall devtool startup (once)"
  [^js web-content]
  (reset! content-atom web-content)
  (start-ws!)
  ;(.on web-content "dom-ready" (fn on-inspect-reload-request-app-state [] (log/info "DOM READY")))
  ;; LANDMARK: Hook up of incoming messages from dev tool
  (e/ipcMain.on "send"
    (fn handle-devtool-message [_ message]
      (let [msg (encode/read message)]
        (enc/try*
          (send-to-target! msg)
          (catch :all t
            (log/warn t "Failed to send to target" {:msg msg})))))))
