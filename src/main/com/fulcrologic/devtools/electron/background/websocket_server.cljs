(ns com.fulcrologic.devtools.electron.background.websocket-server
  (:require
    ["electron" :refer [ipcMain]]
    ["electron-settings" :as settings]
    [cljs.core.async :as async :refer [<! >! put! take!] :refer-macros [go go-loop]]
    [cljs.nodejs :as nodejs]
    [cognitect.transit :as transit]
    [com.fulcrologic.devtools.electron.background.agpatch] ; patches goog global for node
    [com.fulcrologic.devtools.common.transit :as encode]
    [com.fulcrologic.devtools.common.transit-packer :as tp]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [goog.object :as gobj]
    [taoensso.sente.server-adapters.community.express :as sente-express]
    [taoensso.timbre :as log]))

(defonce channel-socket-server (atom nil))
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
      (.send @content-atom "devtool" (encode/write msg))
      (log/warn "Message ignored. Content atom not ready."))
    (catch :default e
      (log/error e))))

(defn start-ws! []
  (when-not @channel-socket-server
    (reset! channel-socket-server
      (sente-express/make-express-channel-socket-server!
        {:packer        (tp/make-packer {})
         :csrf-token-fn nil
         :user-id-fn    :client-id})))
  (go-loop []
    (when-some [{:keys [client-id event]} (<! (:ch-recv @channel-socket-server))]
      (let [[event-type event-data] event]
        (log/debug "Server received:" event-type)
        (log/debug "-> with event data:" event-data)
        (case event-type
          :fulcrologic.devtool/event
          (send-to-devtool! event-data)
          :chsk/uidport-close
          (log/debug "lost connection" client-id)
          :chsk/uidport-open
          (log/debug "opened connection" client-id)
          :chsk/ws-ping
          (log/debug "ws-ping from client:" client-id)
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

(defn send-to-target! [{::mk/keys [target-id] :as msg}]
  (if-not target-id
    (log/warn "Unable to find app-uuid in message:" msg)
    (let [{:keys [send-fn]} @channel-socket-server]
      (send-fn (pr-str target-id) [:fulcrologic.devtool/event msg]))))

(defn start!
  "Call for overall devtool startup (once)"
  [^js web-content]
  (log/info "Setting web content to" web-content)
  (reset! content-atom web-content)
  (start-ws!)
  (.on web-content "dom-ready" (fn on-inspect-reload-request-app-state [] (log/info "DOM READY")))
  ;; LANDMARK: Hook up of incoming messages from dev tool
  (.on ipcMain "devtool"
    (fn handle-devtool-message [_ message]
      (log/trace "Server received message from renderer")
      (let [msg (encode/read message)]
        (send-to-target! msg)))))
