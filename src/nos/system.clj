(ns nos.system
  (:require
   [clojure.string :as str]
   [konserve.core :as kv]
   [clojure.java.io :as io]
   [nos.core :as nos]
   [clojure.core.async :as a :refer [>! <! >!! <!! go go-loop chan put!]]
   [taoensso.timbre :refer [log]])
  (:import org.apache.commons.io.FilenameUtils
           java.util.Base64))

(defn get-log-filename [log-dir flow-id]
  (str log-dir flow-id ".txt"))

(defn- process-event!
  "Trigger a flow after delivering an external result.

  The value of `op` will be set to `result` in `flow-id` and the flow is then
  run from start."
  ([fe flow-id]
   (process-event! fe flow-id [nil nil]))

  ([{:nos/keys [vault store log-dir] :as fe} flow-id [op result]]
   (log :info "Starting flow " flow-id)

   (let [log-file-name (get-log-filename log-dir flow-id)
         _             (log :info "Log file name is " log-file-name)
         _             (io/make-parents log-file-name)]
     (with-open [log-file (io/writer log-file-name :append true)]
       (let [flow (<!! (nos/load-flow flow-id store))]
         (binding [*out* log-file]
           (prn "Starting flow " flow-id)
           (as-> flow f
             (if op (assoc-in f [:results op] result) f)
             (nos/run-flow! fe f)
             (kv/assoc store flow-id f)
             (<!! f))))))))

(defn use-nostromo
  [{:nos/keys [store vault] :as system}]
  (let [flow-chan (chan 2000)
        system    (-> system
                      (assoc :nos/flow-chan flow-chan)
                      (update :system/stop conj #(a/close! flow-chan)))
        fe        (select-keys system [:nos/vault :nos/store :nos/flow-chan :nos/log-dir])]
    (println "Starting flow engine loop...")
    (go-loop []
      (when-let [[event & data :as m] (<! flow-chan)]
        (log :info "Received message " m)
        (case event
          :trigger
          (try (process-event! fe (first data))
               (catch Exception e
                 (log :error "Failed to process event" e)))
          :deliver
          (if-let [[flow-id op] (<! (kv/get-in store [(first data) :deliver]))]
            (do (log :info "Deliver future" (first data))
                (process-event! system flow-id [op (second data)]))
            (log :warn "Future not registered" (first data)))
          :fx
          (let [[flow-id op fxs] data
                flow             (<! (nos/load-flow flow-id store))]
            (log :info "Process fx" fxs)
            (->> flow
                 (nos/handle-fx fe op fxs)
                 (kv/assoc store flow-id)
                 <!)))
        (recur)))
    system))
