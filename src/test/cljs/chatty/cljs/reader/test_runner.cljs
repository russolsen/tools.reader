(ns cljs.cljs.reader.test-runner
  (:require chatty.cljs.reader.metadata-test
            chatty.cljs.reader.reader-test
            chatty.cljs.reader.reader-edn-test
            [cljs.test :refer-macros [run-all-tests]]))

(enable-console-print!)
(run-all-tests)
