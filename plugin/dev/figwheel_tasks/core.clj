(ns figwheel-tasks.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.java.shell :as shell]))

(defn internal-version [version line]
  (if (.contains line "def _figwheel-version_")
    (pr-str (list 'def '_figwheel-version_ version))
    line))

(defn project-version [version line]
  (if-let [[_ project-name] (re-matches
                             #".*defproject\s+([\w\-]+).*"
                             line)]
    (str "(defproject " (pr-str project-name) " "(pr-str version))
    line))

(let [reg #"(.*)\[(lein-figwheel|figwheel-sidecar|figwheel)\s+\"[\w\.\-\d]+\"(.*)"]
  (defn project-deps-version [version line]
    (if (re-matches reg line)
      (string/replace line
                      reg
                      (str "$1[$2 " (pr-str version) "$3"))
      line)))

(def add-newline #(str % "\n"))

(defn chg-file-version [func version file]
  (->> (line-seq (io/reader file))
       (mapv (partial func version))
       (string/join "\n")
       add-newline
       (spit (io/file file))))

(defn change-version [version]
  (doseq [file ["project.clj"
                "../sidecar/project.clj"
                "../support/project.clj"]]
    (chg-file-version project-version version file))
  (doseq [file ["project.clj"
                 "../sidecar/project.clj"
                 "../example/project.clj"]]
    (chg-file-version project-deps-version version file))
  (doseq [file ["src/leiningen/figwheel.clj"
                 "../sidecar/src/figwheel_sidecar/config.clj"
                 "../support/src/figwheel/client.cljs"]]
    (chg-file-version internal-version version file)))

(defn install-all []
  (doseq [dir ["../support" "../sidecar" "./"]]
    (-> (shell/sh "lein" "install" :dir dir) :out println)
    (flush)))

#_(change-version "AAAAAAAAAAAAA")

(defn -main [command & args]
  (condp = command
    ":change-version" (change-version (first args))
    ":install-all" (install-all)))
