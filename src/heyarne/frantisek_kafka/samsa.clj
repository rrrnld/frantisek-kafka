(ns heyarne.frantisek-kafka.samsa
  "Create your own Kafka."
  (:require [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [taoensso.timbre :as log]
            [clj-http.client :as http]
            [dotenv.core :refer [env]]
            [heyarne.frantisek-kafka.markov :as markov])
  (:gen-class))

;; parsing the input text

(defn read-corpus [files]
  (flatten (for [file files]
             (-> (slurp file)
                 (str/replace #"[^\sa-zA-ZäöüÄÖÜß',.:!?]" "")
                 (str/split #"\s+")))))

(defn sentence-ending? [words]
  (let [last-word (last words)
        second-to-last (last (butlast words))]
    (and
     (re-find #"[.?!]$" last-word)
     (or
      (not (#{"Mr." "Mrs."} last-word))
      ;; if it ends with a title, the title needs an article before that
      (and (#{"Mr." "Mrs."} last-word)
           (not (#{"the" "a"} second-to-last)))))))

(defn sentence-start? [state]
  (some? (re-find #"^[A-Z]" (first state))))

(defn generate-sentence [markov-chain]
  (let [start (->> (markov/states markov-chain)
                   (filter sentence-start?)
                   (rand-nth))]
    (str/join " " (markov/generate markov-chain start sentence-ending?))))

;; interacting with the API

(defn environment-setup? []
  (and (:mastodon-instance env) (:access-token env)))

(defn send-toot! [text]
  (when (environment-setup?)
    (http/post (str (:mastodon-instance env) "/api/v1/statuses")
               {:form-params {:status text}
                :headers {:authorization (str "Bearer " (:access-token env))}}))
  (log/info text))

;; defining the command line interface

(def cli-options
  [["-o" "--order ORDER" "Order of the Markov chain. Defaults to 2."
    :default 2
    :parse-fn #(Integer/parseInt %)
    :validate [(partial < 0) "Must be greater than 0."]]
   ["-i" "--interval INTERVAL" "Interval between toots in seconds."
    :default 21600
    :parse-fn #(Integer/parseInt %)
    :validate [(partial < 0) "Must be greater than 0."]]
   ["-h" "--help"]])

(defn usage [summary]
  (str/join \newline
            ["František Kafka is a Markov chain bot that infinitely generates text"
             "from one or more text files. botsin.space/@frantisek hosts a live"
             "version of it that generates an infinite version of Franz Kafka's Metamorphosis."
             ""
             "Usage: frantisek [options] [corpus...]"
             ""
             "Options:"
             summary]))

(defn error-message [errors]
  (str "Could not parse the command:\n\n" (str/join \newline errors)))

(defn validate-args
  "Validate command line arguments and return a map describing how to proceed."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (error-message errors)}

      (> (count arguments) 0)
      (merge options {:corpus arguments})

      :else
      {:exit-message (usage summary)})))

(defn exit [status message]
  (println message)
  (System/exit status))

(defn count-words [sentence]
  (inc (count (re-seq #" " sentence))))

(defn start! [{:keys [corpus order interval]}]
  (let [markov-chain (->> (read-corpus corpus)
                          (markov/chain order))]
    (log/warn "David Foster Wallace once claimed that Kafka sat in his room at night, writing his stories and driving all of his neighbors into insanity because he could not stop laughing manically. I don't know if that is true, but it is an interesting story to tell, right?")
    (when-not (environment-setup?)
      (log/warn "Please set :access-token and :mastodon-instance in the .env file.")
      (log/warn "The bot is running in debug mode for now."))
    (loop [sentence (generate-sentence markov-chain)]
      (when (< (count-words sentence) 20)
        ;; shorter sentences are more likely to be coherent. :)
        (send-toot! sentence)
        (Thread/sleep (* interval 1000)))
      (recur (generate-sentence markov-chain)))))

(defn -main [& args]
  (let [parsed-args (validate-args args)]
    (if (:exit-message parsed-args)
      (exit (if (:ok? parsed-args) 0 1) (:exit-message parsed-args))
      (start! parsed-args))))
