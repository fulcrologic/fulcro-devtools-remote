(ns com.fulcrologic.devtools.electron.background.agpatch)

(set! (.-addEventListener goog/global) (fn [& _]))
