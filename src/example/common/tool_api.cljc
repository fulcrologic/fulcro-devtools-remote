(ns common.tool-api
  "In this namespace, the fulcro mutations are what the target application would use to call the resolvers of the tool, and
   the resolvers are handlers for these messages"
  (:require
    [com.fulcrologic.devtools.common.resolvers :refer [remote-mutations]]))

(remote-mutations counter-added)
