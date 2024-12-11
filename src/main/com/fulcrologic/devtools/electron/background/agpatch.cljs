(ns com.fulcrologic.devtools.electron.background.agpatch
  (:require
    [cljs.core.async :as async]))

(set! (.-addEventListener goog/global) (fn [& _]))
(set! (.-setTimeout goog/global) (fn [f ms]
                                   (async/go
                                     (async/<! (async/timeout ms))
                                     (f))))
