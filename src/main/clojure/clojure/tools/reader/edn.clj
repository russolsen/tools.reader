;;   Copyright (c) Nicola Mometto, Rich Hickey & contributors.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "An EDN reader in clojure"
      :author "Bronsa"}
  clojure.tools.reader.edn
  (:refer-clojure :exclude [read read-string char default-data-readers])
  (:require [clojure.tools.reader.reader-types :refer
             [read-char reader-error throw-bad-arg throw-bad-token unread peek-char indexing-reader?
              get-line-number get-column-number get-file-name indexing-string-push-back-reader inspect]]
            [clojure.tools.reader.impl.utils :refer
             [char ex-info? whitespace? numeric? desugar-meta namespace-keys second']]
            [clojure.tools.reader.impl.commons :refer :all]
            [clojure.tools.reader :refer [default-data-readers]])
  (:import (clojure.lang PersistentHashSet IMeta RT PersistentVector)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare read macros dispatch-macros)

(defn- macro-terminating? [ch]
  (and (not (identical? \# ch))
       (not (identical? \' ch))
       (not (identical? \: ch))
       (macros ch)))

(defn- not-constituent? [ch]
  (or (identical? \@ ch)
      (identical? \` ch)
      (identical? \~ ch)))

(defn- ^String read-token
  ([rdr initch kind]
     (read-token rdr initch kind true))
  ([rdr initch kind validate-leading?]
     (cond
      (not initch)
        (reader-error rdr "Unexpected EOF while reading the start of " kind ".")

      (and validate-leading?
           (not-constituent? initch))
        (reader-error rdr "Invalid leading character at the start of a " kind " [" initch "].")

      :else
      (loop [sb (StringBuilder.)
             ch (do (unread rdr initch) initch)]
        (if (or (whitespace? ch)
                (macro-terminating? ch)
                (nil? ch))
          (str sb)
          (if (not-constituent? ch)
            (reader-error rdr "Invalid character ["  ch "] in " kind " starting with [" sb "].")
            (recur (doto sb (.append (read-char rdr))) (peek-char rdr))))))))

(declare read-tagged)

(defn- read-dispatch
  [rdr _ opts]
  (if-let [ch (read-char rdr)]
    (if-let [dm (dispatch-macros ch)]
      (dm rdr ch opts)
      (if-let [obj (read-tagged (doto rdr (unread ch)) ch opts)]
        obj
        (reader-error rdr "No dispatch macro for [" ch "].")))
    (reader-error rdr "Unexpected EOF while reading dispatch character.")))

(defn- read-unmatched-delimiter
  [rdr ch opts]
  (reader-error rdr "Unmatched delimiter [" ch "]."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; readers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- decode-unicode-token
  [^String token ^long offset ^long length ^long base]
   (let [l (+ offset length)]
     (when-not (== (count token) l)
       (throw-bad-token
         token
         (format
           "Invalid unicode literal: \\%s. Unicode literal should be %d characters long."
           token
           (inc l))))
     (loop [i offset uc 0]
       (if (== i l)
         (char uc)
         (let [d (Character/digit (int (nth token i)) (int base))]
           (if (== d -1)
             (throw-bad-token token
               (format
                 "Invalid digit in unicode literal [%s] at character index %d."
                 token
                 i))
             (recur (inc i) (long (+ d (* uc base))))))))))

(defn- read-unicode-char [rdr initch base length exact?]
   (let [length (long length)
         base (long base)]
     (loop [i 1 uc (Character/digit (int initch) (int base))]
       (if (== uc -1)
         (throw-bad-arg rdr (str "Invalid digit " initch " in unicode literal."))
         (if-not (== i length)
           (let [ch (peek-char rdr)]
             (if (or (whitespace? ch)
                     (macros ch)
                     (nil? ch))
               (if exact?
                 (throw-bad-arg
                         rdr
                         (format "Invalid unicode literal. Literal is %d characters long but Unicode literals should be %d characters long."
                         (inc i)
                         (inc length)))
                 (char uc))
               (let [d (Character/digit (int ch) (int base))]
                 (read-char rdr)
                 (if (== d -1)
                   (throw (IllegalArgumentException. (str "Invalid digit [" ch "] found while reading unicode literal.")))
                   (recur (inc i) (long (+ d (* uc base))))))))
           (char uc))))))

(def ^:private ^:const upper-limit (int \uD7ff))
(def ^:private ^:const lower-limit (int \uE000))

(defn- read-char*
  [rdr backslash opts]
  (let [ch (read-char rdr)]
    (if-not (nil? ch)
      (let [token (if (or (macro-terminating? ch)
                          (not-constituent? ch)
                          (whitespace? ch))
                    (str ch)
                    (read-token rdr ch "character literal" false))
            token-len (count token)]
        (cond

         (== 1 token-len)  (Character/valueOf (nth token 0))

         (= token "newline") \newline
         (= token "space") \space
         (= token "tab") \tab
         (= token "backspace") \backspace
         (= token "formfeed") \formfeed
         (= token "return") \return

         (.startsWith token "u")
         (let [c (decode-unicode-token token 1 4 16)
               ic (int c)]
           (if (and (> ic upper-limit)
                    (< ic lower-limit))
             (reader-error rdr "Invalid character literal: [\\u" (Integer/toString ic 16) "].")
             c))

         (.startsWith token "o")
         (let [len (dec token-len)]
           (if (> len 3)
             (reader-error rdr "Invalid octal escape sequence " token ". Octal escape sequences cannot be longer than 3 digits.
             The value supplied is " len " digits long.")
             (let [uc (decode-unicode-token token 1 len 8)]
               (if (> (int uc) 0377)
                 (reader-error rdr "Bad octal escape sequence " token ". Value must be in range [0, 377].")
                 uc))))

         :else (reader-error rdr "Unsupported character literal: \\" token ".")))
      (reader-error rdr "Unexpected EOF while reading character literal."))))

(defn- ^PersistentVector read-delimited
  [kind delim rdr opts]
  (let [first-line (when (indexing-reader? rdr)
                     (get-line-number rdr))
        delim (char delim)]
    (loop [a (transient [])]
      (let [ch (read-past whitespace? rdr)]
        (when-not ch
          (reader-error rdr "Unexpected EOF while reading " kind
                        (if first-line
                          (str ", starting at line " first-line ".")
                          ".")))
        (if (identical? delim (char ch))
          (persistent! a)
          (if-let [macrofn (macros ch)]
            (let [mret (macrofn rdr ch opts)]
              (recur (if-not (identical? mret rdr) (conj! a mret) a)))
            (let [o (read (doto rdr (unread ch)) true nil opts)]
              (recur (if-not (identical? o rdr) (conj! a o) a)))))))))

(defn- read-list
  [rdr _ opts]
  (let [the-list (read-delimited "list" \) rdr opts)]
    (if (empty? the-list)
      '()
      (clojure.lang.PersistentList/create the-list))))

(defn- read-vector
  [rdr _ opts]
  (read-delimited "vector" \] rdr opts))

(defn- read-map
  [rdr _ opts]
  (let [l (to-array (read-delimited "map" \} rdr opts))]
    (when (== 1 (bit-and (alength l) 1))
      (let [msg1 "Map literals must contain an even number of forms. "
            msg2 (format "The value supplied, which starts {%s...}, "
              (inspect (first l)))
            msg3 (format "contains %s form(s)." (alength l))]
        (reader-error rdr msg1 msg2 msg3)))
    (RT/map l)))

(defn- read-number
  [reader initch opts]
  (loop [sb (doto (StringBuilder.) (.append initch))
         ch (read-char reader)]
    (if (or (whitespace? ch) (macros ch) (nil? ch))
      (let [s (str sb)]
        (unread reader ch)
        (or (match-number s)
            (reader-error reader "Invalid number format " s ".")))
      (recur (doto sb (.append ch)) (read-char reader)))))

(defn- escape-char [sb rdr]
  (let [ch (read-char rdr)]
    (case ch
      \t "\t"
      \r "\r"
      \n "\n"
      \\ "\\"
      \" "\""
      \b "\b"
      \f "\f"
      \u (let [ch (read-char rdr)]
           (if (== -1 (Character/digit (int ch) 16))
             (reader-error rdr "Invalid Unicode escape: [\\u" ch "].")
             (read-unicode-char rdr ch 16 4 true)))
      (if (numeric? ch)
        (let [ch (read-unicode-char rdr ch 8 3 false)]
          (if (> (int ch) 0337)
            (reader-error rdr "Octal escape sequence must be in range [0, 377].")
            ch))
        (reader-error rdr "Unsupported escape character: \\" ch ".")))))

(defn- read-string*
  [reader _ opts]
  (loop [sb (StringBuilder.)
         ch (read-char reader)]
    (case ch
      nil (reader-error reader "Unexpected EOF while reading string starting with \"" sb ".")
      \\   (recur (doto sb (.append (escape-char sb reader)))
                (read-char reader))
      \"   (str sb)
      (recur (doto sb (.append ch)) (read-char reader)))))

(defn- read-symbol
  [rdr initch]
  (when-let [token (read-token rdr initch "symbol")]
    (case token

      ;; special symbols
      "nil" nil
      "true" true
      "false" false
      "/" '/
      "NaN" Double/NaN
      "-Infinity" Double/NEGATIVE_INFINITY
      ("Infinity" "+Infinity") Double/POSITIVE_INFINITY

      (or (when-let [p (parse-symbol token)]
            (symbol (p 0) (p 1)))
          (reader-error rdr "Invalid symbol " token ".")))))

(defn- read-keyword
  [reader initch opts]
  (let [ch (read-char reader)]
    (if-not (whitespace? ch)
      (let [token (read-token reader ch "keyword")
            s (parse-symbol token)]
        (if (and s (== -1 (.indexOf token "::")))
          (let [^String ns (s 0)
                ^String name (s 1)]
            (if (identical? \: (nth token 0))
              (reader-error reader "In EDN, symbols cannot start with two colons " token ".") ;; no ::keyword in edn
              (keyword ns name)))
          (reader-error reader "Invalid keyword: :" token ".")))
      (reader-error reader "A single colon is not a valid keyword."))))

(defn- wrapping-reader
  [sym]
  (fn [rdr _ opts]
    (list sym (read rdr true nil opts))))

(defn- read-meta
  [rdr _ opts]
  (let [m (desugar-meta (read rdr true nil opts))]
    (when-not (map? m)
      (reader-error
        rdr
        "Metadata must be Symbol, Keyword, String or Map, not "
        (inspect m)
        "."))
    (let [o (read rdr true nil opts)]
      (if (instance? IMeta o)
        (with-meta o (merge (meta o) m))
        (reader-error
          rdr
          "Metadata cannot be applied to "
          (inspect o)
          ". Metadata can only be applied to IMetas.")))))

(defn- read-set
  [rdr _ opts]
  (PersistentHashSet/createWithCheck (read-delimited "set" \} rdr opts)))

(defn- read-discard
  [rdr _ opts]
  (doto rdr
    (read true nil true)))

(defn- read-namespaced-map
  [rdr _ opts]
  (let [token (read-token rdr (read-char rdr) "namespaced map")]
    (if-let [ns (some-> token parse-symbol second')]
      (let [ch (read-past whitespace? rdr)]
        (if (identical? ch \{)
          (let [items (read-delimited "namespaced map" \} rdr opts)]
            (when (odd? (count items))
              (reader-error rdr (str
                                  "Map literals must contain an even number of forms. "
                                  "The value supplied contains "
                                  (count items)
                                  " forms." )))
            (let [keys (take-nth 2 items)
                  vals (take-nth 2 (rest items))]
              (zipmap (namespace-keys (str ns) keys) vals)))
          (reader-error rdr "Namespaced map with namespace [" token "] does not specify a map.")))
        (reader-error rdr "Invalid token used as namespace in namespaced map: [" token "]."))))

(defn- macros [ch]
  (case ch
    \" read-string*
    \: read-keyword
    \; read-comment
    \^ read-meta
    \( read-list
    \) read-unmatched-delimiter
    \[ read-vector
    \] read-unmatched-delimiter
    \{ read-map
    \} read-unmatched-delimiter
    \\ read-char*
    \# read-dispatch
    nil))

(defn- dispatch-macros [ch]
  (case ch
    \^ read-meta                ;deprecated
    \{ read-set
    \< (throwing-reader "Unreadable form.")
    \! read-comment
    \_ read-discard
    \: read-namespaced-map
    nil))

(defn- read-tagged [rdr initch opts]
  (let [tag (read rdr true nil opts)
        object (read rdr true nil opts)]
    (if-not (symbol? tag)
      (reader-error rdr "Reader tag must be a symbol, not " (inspect tag) "."))
    (if-let [f (or (get (:readers opts) tag)
                   (default-data-readers tag))]
      (f object)
      (if-let [d (:default opts)]
        (d tag object)
        (reader-error rdr "No reader function for " (inspect tag) ".")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read
  "Reads the first object from an IPushbackReader or a java.io.PushbackReader.
   Returns the object read. If EOF, throws if eof-error? is true otherwise returns eof.
   If no reader is provided, *in* will be used.

   Reads data in the edn format (subset of Clojure data):
   http://edn-format.org

   clojure.tools.reader.edn/read doesn't depend on dynamic Vars, all configuration
   is done by passing an opt map.

   opts is a map that can include the following keys:
   :eof - value to return on end-of-file. When not supplied, eof throws an exception.
   :readers  - a map of tag symbols to data-reader functions to be considered before default-data-readers.
              When not supplied, only the default-data-readers will be used.
   :default - A function of two args, that will, if present and no reader is found for a tag,
              be called with the tag and the value."
  ([] (read *in*))
  ([reader] (read {} reader))
  ([{:keys [eof] :as opts} reader]
     (let [eof-error? (not (contains? opts :eof))]
       (read reader eof-error? eof opts)))
  ([reader eof-error? eof opts]
     (try
       (loop []
         (let [ch (read-char reader)]
           (cond
            (whitespace? ch) (recur)
            (nil? ch) (if eof-error? (reader-error reader "EOF") eof)
            (number-literal? reader ch) (read-number reader ch opts)
            :else (let [f (macros ch)]
                    (if f
                      (let [res (f reader ch opts)]
                        (if (identical? res reader)
                          (recur)
                          res))
                      (read-symbol reader ch))))))
       (catch Exception e
         (if (ex-info? e)
           (let [d (ex-data e)]
             (if (= :reader-exception (:type d))
               (throw e)
               (throw (ex-info (.getMessage e)
                               (merge {:type :reader-exception}
                                      d
                                      (if (indexing-reader? reader)
                                        {:line   (get-line-number reader)
                                         :column (get-column-number reader)
                                         :file   (get-file-name reader)}))
                               e))))
           (throw (ex-info (.getMessage e)
                           (merge {:type :reader-exception}
                                  (if (indexing-reader? reader)
                                    {:line   (get-line-number reader)
                                     :column (get-column-number reader)
                                     :file   (get-file-name reader)}))
                           e)))))))

(defn read-string
  "Reads one object from the string s.
   Returns nil when s is nil or empty.

   Reads data in the edn format (subset of Clojure data):
   http://edn-format.org

   opts is a map as per clojure.tools.reader.edn/read"
  ([s] (read-string {:eof nil} s))
  ([opts s]
     (when (and s (not (identical? s "")))
       (read opts (indexing-string-push-back-reader s)))))
