(ns devtool.electron-app
(:require
  [com.fulcrologic.devtools.electron.devtool :as dt]
  [com.fulcrologic.fulcro.application :as app]
  [com.fulcrologic.fulcro.application]
  [com.fulcrologic.fulcro.components]
  [com.fulcrologic.fulcro.react.version18 :refer [with-react18]]
  [devtool.ui :as ui]
  [taoensso.timbre :as log]))

(defn start! []
  (log/info "Starting devtool")
  (let [aa   (atom nil)
        app  (with-react18 (app/fulcro-app ))
        ]
    ;; TASK: Electron Stuff
    #_#_#_(js/document.body.appendChild node)
    (dt/watch-targets! app)
    (app/mount! app ui/Root node)
    app))

(defonce started (start!))
