(ns chatty.cljs.reader.reader-types)

(defmacro log-source
  "If reader is a SourceLoggingPushbackReader, execute body in a source
  logging context. Otherwise, execute body, returning the result."
  [reader & body]
  `(if (and (source-logging-reader? ~reader)
            (not (chatty.cljs.reader.impl.utils/whitespace? (peek-char ~reader))))
     (log-source* ~reader (^:once fn* [] ~@body))
     (do ~@body)))
