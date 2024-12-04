(ns common.target-api
  (:require
    [com.fulcrologic.devtools.common.target :refer [ido]]
    [com.fulcrologic.devtools.common.resolvers :refer [remote-mutations]]))

(ido
  (remote-mutations restart))

