(ns cljs.cljs.reader.test-runner
  (:require cljs.tools.metadata-test
            chatty.cljs.reader-test
            chatty.cljs.reader-edn-test
            [cljs.test :refer-macros [run-all-tests]]))

(enable-console-print!)
(run-all-tests)
