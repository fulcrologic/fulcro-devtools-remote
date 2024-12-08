(ns com.fulcrologic.devtools.chrome-preload
  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.devtools.chrome.target :as chrome]
    [com.fulcrologic.devtools.target :as target]
    [com.fulcrologic.devtools.protocols :as dp]
    [com.fulcrologic.devtools.message-keys :as mk]
    [taoensso.timbre :as log]))

(target/set-factory! (chrome/->ChromeDevtoolConnectionFactory))
(chrome/install!)
