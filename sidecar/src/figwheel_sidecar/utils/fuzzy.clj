;; Copyright © 2013-2015 Guillaume Plique

;; Permission is hereby granted, free of charge, to any person
;; obtaining a copy of this software and associated documentation
;; files (the "Software"), to deal in the Software without
;; restriction, including without limitation the rights to use, copy,
;; modify, merge, publish, distribute, sublicense, and/or sell copies
;; of the Software, and to permit persons to whom the Software is
;; furnished to do so, subject to the following conditions:

;; The above copyright notice and this permission notice shall be
;; included in all copies or substantial portions of the Software.

;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
;; EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
;; MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
;; NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
;; BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
;; ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
;; CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
;; SOFTWARE.
;; -------------------------------------------------------------------
;; clj-fuzzy Levenshtein
;; -------------------------------------------------------------------
;;
;;
;;   Author: PLIQUE Guillaume (Yomguithereal)
;;   Version: 0.1
;;   Source: https://gist.github.com/vishnuvyas/958488
;;
(ns figwheel-sidecar.utils.fuzzy)

;; extract
(defn named? [x]
  (or (string? x) (instance? clojure.lang.Named x)))

(defn- next-row
  [previous current other-seq]
  (reduce
    (fn [row [diagonal above other]]
      (let [update-val (if (= other current)
                          diagonal
                          (inc (min diagonal above (peek row))))]
        (conj row update-val)))
    [(inc (first previous))]
    (map vector previous (next previous) other-seq)))

(defn levenshtein
  "Compute the levenshtein distance between two [sequences]."
  [sequence1 sequence2]
  (peek
    (reduce (fn [previous current] (next-row previous current sequence2))
            (map #(identity %2) (cons nil sequence2) (range))
            sequence1)))

(defn step-log [thresh val]
  (if (< thresh val)
    (+ thresh (/ (- val thresh ) 2.0))
    val))

(defn ky-distance [ky ky1]
  (let [l (levenshtein (name ky) (name ky1))]
    (/ (float l)
       (step-log 5
                 (/ (float (+ (count (name ky))
                              (count (name ky1))))
                    2)))))

(defn similar-key [thresh k other-key]
  (and (and (named? k)
            (named? other-key))
       (< (ky-distance k other-key)
          0.51)))

(defn get-keylike [ky mp]
  (if-let [val (get mp ky)]
    [ky val]
    (when-let [res (not-empty
                    (sort-by
                     #(-> % first -)
                     (filter
                      #(first %)
                      (map (fn [[k v]] [(and (similar-key 0 k ky)
                                             (ky-distance k ky))
                                        [k v]]) mp))))]
      (-> res first second))))

(defn fuzzy-select-keys [m kys]
  (into {} (map #(get-keylike % m) kys)))

(defn fuzzy-select-keys-and-fix [m kys]
  (into {} (map #(let [[_ v] (get-keylike % m)] [% v]) kys)))

(comment

  (metrics/levenshtein "GSFD" "GFSD")

  (ky-distance :GFSD :GSFD)

  (ky-distance :figwheel :figwheeler)
  )
