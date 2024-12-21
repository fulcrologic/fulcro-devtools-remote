(ns com.fulcrologic.devtools.electron.devtool
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.common.connection :as cc]
    [com.fulcrologic.devtools.common.devtool-default-mutations]
    [com.fulcrologic.devtools.common.fulcro-devtool-remote :as fdr]
    [com.fulcrologic.devtools.common.resolvers :as resolvers]
    [com.fulcrologic.devtools.common.schemas]
    [com.fulcrologic.devtools.common.transit :as encode]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [taoensso.encore :as enc]))

(>defn post-message! [msg]
  [:transit/encoded-string => :any]
  #?(:cljs (js/window.electronAPI.send msg)))

(defn service-worker-message-handler [^clj conn _evt msg]
  (let [decoded-message (enc/catching (encode/read msg))]
    (cc/handle-devtool-message conn decoded-message)))

(defn add-message-listener! [listener] (js/window.electronAPI.listen listener))

(defn new-electron-ui-connection
  [async-processor]
  (let [send-ch (async/chan (async/dropping-buffer 10000))
        conn    (cc/->Connection (volatile! {:async-processor async-processor
                                             :active-requests {}
                                             :send-ch         send-ch}))]
    (async/go-loop []
      (let [v (async/<! send-ch)]
        (post-message! (encode/write v)))
      (recur))
    (add-message-listener! (partial service-worker-message-handler conn))
    conn))

(defn add-devtool-remote! [app]
  (let [c    (atom nil)
        conn (new-electron-ui-connection
               (fn [EQL] (resolvers/process-async-request {:devtool/connection @c
                                                           :fulcro/app         app} EQL)))]
    (reset! c conn)
    (app/set-remote! app :devtool-remote (fdr/devtool-remote conn))
    app))
