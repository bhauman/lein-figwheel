(ns figwheel.main.schema.shared
  (:require [figwheel.main.util :as util]
            [clojure.set]
            [expound.alpha :as exp]))

(defn has-cljs-source-files? [dir]
  (not-empty (clojure.set/intersection
               #{"cljs" "cljc"}
               (util/source-file-types-in-dir dir))))

(exp/def ::has-cljs-source-files has-cljs-source-files?
  "directory should contain cljs or cljc source files")
