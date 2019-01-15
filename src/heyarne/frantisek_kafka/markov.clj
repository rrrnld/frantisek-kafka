(ns heyarne.frantisek-kafka.markov
  "A simple markov model for sequential data. Basically a port of
  https://www.youtube.com/watch?v=-51qWZdA8zM.")

(defn vconj
  "Like conj but creates a vector if coll is nil"
  [coll x]
  (conj (or coll []) x))

(defn chain
  "Returns a lookup map that describes a Markov chain. Expects an order > 0 and a list of tokens, which resemble the preprocessed corpus."
  [order tokens]
  {:pre [(> order 0)]}
  (->>
   ;; make sure we don't have any dead ends
   (cycle tokens)
   (take (+ (count tokens) order))
   ;; construct the lookup map
   (partition (inc order) 1)
   (reduce (fn [prev n-gram]
             (update prev (butlast n-gram) vconj (last n-gram))) {})))

(defn generate
  "Generates a sequence from a Markov chain lookup map, beginning at `start`,
  continuing until `should-end?` returns true."
  [markov-chain start should-end?]
  {:pre [(some? (get markov-chain start))]}
  (loop [phrase (vec start)]
    (let [next-word (rand-nth (get markov-chain (take-last (count start) phrase)))
          next-phrase (conj phrase next-word)]
      (if (should-end? next-word)
        next-phrase
        (recur next-phrase)))))

(defn states
  "Returns all items in the markov chain for which a transition is defined."
  [markov-chain]
  (vec (keys markov-chain)))
