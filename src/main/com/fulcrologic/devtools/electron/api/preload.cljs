(ns com.fulcrologic.devtools.electron.api.preload
  (:require
    ["electron" :as e]
    [taoensso.timbre :as log]))

(defn ^:export init []
  (e/contextBridge.exposeInMainWorld
    "electronAPI"
    #js {:listen (fn [f] (e/ipcRenderer.on "devtool" f))
         :send   (fn [msg] (e/ipcRenderer.send "send" msg))}))
