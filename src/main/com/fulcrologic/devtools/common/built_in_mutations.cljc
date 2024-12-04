(ns com.fulcrologic.devtools.common.built-in-mutations
  (:require
    [com.fulcrologic.fulcro.mutations :as m]))

(m/declare-mutation devtool-connected `devtool-connected)
(m/declare-mutation target-started `target-started)
