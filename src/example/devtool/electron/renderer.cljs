(ns devtool.electron.renderer
  (:require
    [com.fulcrologic.devtools.electron.devtool :as tool]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components]
    [com.fulcrologic.fulcro.react.version18 :refer [with-react18]]
    [devtool.ui :as ui]
    [taoensso.timbre :as log]))

(defonce app-atom (atom nil))

(defn start []
  (log/info "Starting devtool")
  (let [app (with-react18 (app/fulcro-app))]
    (tool/add-devtool-remote! app)
    (app/mount! app ui/Root "app")
    (reset! app-atom app)
    app))

(defn refresh [] (app/mount! @app-atom ui/Root "app"))
