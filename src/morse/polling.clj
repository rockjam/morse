(ns morse.polling
  "Declares long-polling routines to communicate with Telegram Bot API"
  (:require [clojure.core.async :as a]
            [morse.api :as api]))


(defn new-offset
  "Returns new offset for Telegram updates"
  [updates default]
  (if (seq updates)
    (-> updates last :update_id inc)
    default))


(defn create-producer
  "Passed channel should be always empty.
   Close it to stop long-polling.
   Returns channel with updates from Telegram"
  [running token opts]
  (let [updates (a/chan)
        ;; timeout for Telegram API in seconds
        timeout (or (:timeout opts) 1)]
    (a/go-loop [offset 0]
      (let [;; fix for JDK bug https://bugs.openjdk.java.net/browse/JDK-8075484
            ;; introduce additional timeout 10 times more that telegram's one
            wait-timeout (a/go (a/<! (a/timeout (* 1000 timeout 10)))
                               ::wait-timeout)
            response (api/get-updates-async token (assoc opts :offset offset))
            [data _] (a/alts! [running response wait-timeout])]
        (case data
          ;; running got closed by the user
          nil
          (do (println "Stopping Telegram polling...")
              (a/close! wait-timeout)
              (a/close! updates))

          ::wait-timeout
          (do (println "HTTP request timed out, stopping polling")
              (a/close! running)
              (a/close! updates))

          ::api/error
          (do (println "Got error from Telegram API, stopping polling")
              (a/close! running)
              (a/close! updates))

          (do (a/close! wait-timeout)
              (doseq [upd data] (a/>! updates upd))
              (recur (new-offset data offset))))))
    updates))


(defn create-consumer
  "Creates consumer from given handler function
   and channel with updates.

   Start infinite loop inside go-routine
   that will pull messages from channel.

   Will be stopped when channel is closed."
  [updates handler]
  (a/go-loop []
    (when-let [data (a/<! updates)]
      (try
        (handler data)
        (catch Throwable t
          (println t "Unable to handle update" data)))
      (recur))))


(defn start
  "Starts long-polling process.
  Handler is supposed to process immediately, as it will
  be called in a blocking manner."
  ([token handler] (start token handler {}))
  ([token handler opts]
   (let [running (a/chan)
         updates (create-producer running token opts)]
     (create-consumer updates handler)
     running)))


(defn stop
  "Stops everything"
  [running]
  (a/close! running))
