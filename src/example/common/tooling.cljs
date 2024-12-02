(ns common.tooling
  (:require
    [com.fulcrologic.fulcro.mutations :as m]))

(m/defmutation restart [_]
  (ok-action [env] (js/console.log "Mutation returned result" (keys env)))
  (error-action [env] (js/console.log "Restart failed (timeout?)"))
  (remote [_] true))
