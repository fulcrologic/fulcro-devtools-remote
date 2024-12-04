(ns com.fulcrologic.devtools.chrome-preload
  "A script to put in your application's shadow-cljs preload so that devtool support is installed before you use it. Each
   target on the page must also invoke `target/target-started!`."
  (:require
    [com.fulcrologic.devtools.common.target :as target]
    [com.fulcrologic.devtools.chrome.target :as chrome]))

(target/set-factory! (chrome/->ChromeClientConnectionFactory))
(chrome/install!)
