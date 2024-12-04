(ns com.fulcrologic.devtools.common.transit-packer
  (:require
    [com.fulcrologic.devtools.common.transit :as itransit]
    [taoensso.sente.interfaces :refer (pack unpack)]))

(deftype TransitPacker [transit-fmt writer-opts reader-opts]
  taoensso.sente.interfaces/IPacker
  (pack [_ x] (itransit/write x))
  (unpack [_ s] (itransit/read s)))

(defn get-transit-packer "Returns a new TransitPacker"
  ([] (get-transit-packer :json {} {}))
  ([transit-fmt] (get-transit-packer transit-fmt {} {}))
  ([transit-fmt writer-opts reader-opts]
   ;; No transit-cljs support for msgpack atm
   (TransitPacker. transit-fmt writer-opts reader-opts)))

(defn make-packer
  "Returns a json packer for use with sente."
  [{:keys [read write]}]
  (->TransitPacker :json
    {:handlers (cond-> {}
                 write (merge write))}
    {:handlers (cond-> {}
                 read (merge read))}))
