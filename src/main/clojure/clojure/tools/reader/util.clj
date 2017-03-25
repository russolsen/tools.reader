;;   Copyright (c) Nicola Mometto, Rich Hickey & contributors.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "Convenience functions to read in edn in a reasonable way."
      :author "Russ Olsen"}
  clojure.tools.reader.util
  (:require [clojure.tools.reader.reader-types :as types]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.edn :as edn]))

;;   opts is a map that can include the following keys:
;;   :eof - value to return on end-of-file. When not supplied, eof throws an exception.
;;   :readers  - a map of tag symbols to data-reader functions to be considered before default-data-readers.
;;              When not supplied, only the default-data-readers will be used.
;;   :default - A function of two args, that will, if present and no reader is found for a tag,
;;              be called with the tag and the value."
 
(defn read-edn 
  ([rdr] (read-edn rdr {}))
  ([rdr opts]
   (let [reader-opts (select-keys opts [:eof :readers :default])
         buffer-length (or (:buffer-length opts) 1)
         file-name (:file-name opts)
         r (types/indexing-push-back-reader rdr buffer-length file-name)]
     (println "file name:::" (types/get-file-name r))
     (edn/read reader-opts r))))

(defn read-edn-file 
  ([^String path]
   (read-edn-file path {}))
  ([^String path opts]
   (let [options (if (:file-name opts)
                   opts 
                   (assoc opts :file-name path))]
     (read-edn (new java.io.FileReader path) (assoc opts :file-name path)))))
