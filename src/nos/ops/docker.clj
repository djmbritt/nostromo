(ns nos.ops.docker
  (:require
   [nos.core :as nos]
   [failjure.core :as f]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [contajners.core :as c]
   [taoensso.timbre :as log]
   [clj-compress.core :refer [create-archive]])
  (:import [java.io BufferedReader]
           [java.util.zip ZipEntry ZipOutputStream]))

(def api-version "v4.0.0")

(defn zip-folder [path]
  (with-open [zip (ZipOutputStream. (io/output-stream "foo.zip"))]
    (doseq [f (file-seq (io/file path)) :when (.isFile f)]
      (.putNextEntry zip (ZipEntry. (.getPath f)))
      (io/copy f zip)
      (.closeEntry zip))))

; Some functions below are from bob-cd
;
; Copyright 2018-2022 Rahul De
; Source: https://github.com/bob-cd/bob/blob/main/runner/src/runner/engine.clj
(defn sh-tokenize
  "Tokenizes a shell command given as a string into the command and its args.

  Either returns a list of tokens or throws an IllegalStateException.
  Sample input: sh -c 'while sleep 1; do echo \\\"${RANDOM}\\\"; done'
  Output: [sh, -c, while sleep 1; do echo \"${RANDOM}\"; done]"
  [command]
  (let [[escaped? current-arg args state]
        (loop [cmd         command
               escaped?    false
               state       :no-token
               current-arg ""
               args        []]
          (if (or (nil? cmd) (zero? (count cmd)))
            [escaped? current-arg args state]
            (let [char ^Character (first cmd)]
              (if escaped?
                (recur (rest cmd) false state (str current-arg char) args)
                (case state
                  :single-quote       (if (= char \')
                                        (recur (rest cmd) escaped? :normal current-arg args)
                                        (recur (rest cmd) escaped? state (str current-arg char) args))
                  :double-quote       (case char
                                        \" (recur (rest cmd) escaped? :normal current-arg args)
                                        \\ (let [next (second cmd)]
                                             (if (or (= next \") (= next \\))
                                               (recur (drop 2 cmd) escaped? state (str current-arg next) args)
                                               (recur (drop 2 cmd) escaped? state (str current-arg char next) args)))
                                        (recur (rest cmd) escaped? state (str current-arg char) args))
                  (:no-token :normal) (case char
                                        \\ (recur (rest cmd) true :normal current-arg args)
                                        \' (recur (rest cmd) escaped? :single-quote current-arg args)
                                        \" (recur (rest cmd) escaped? :double-quote current-arg args)
                                        (if-not (Character/isWhitespace char)
                                          (recur (rest cmd) escaped? :normal (str current-arg char) args)
                                          (if (= state :normal)
                                            (recur (rest cmd) escaped? :no-token "" (conj args current-arg))
                                            (recur (rest cmd) escaped? state current-arg args))))
                  (throw (IllegalStateException. (format "Invalid shell command: %s, unexpected token %s found."
                                                         command
                                                         state))))))))]
    (if escaped?
      (conj args (str current-arg \\))
      (if (not= state :no-token)
        (conj args current-arg)
        args))))

(defn docker-pull [conn image]
  (let [client (c/client {:engine :podman
                          :category :libpod/images
                          :conn conn
                          :version api-version})]
    (c/invoke client {:op :ImagePullLibpod
                      :params {:reference image}
                      :throw-exceptions true})))

(defn commit-container
  "Creates a new image from a container
  Returns image identifier or a failure."
  [container-id conn]
  (f/try-all [client (c/client {:engine :podman
                                :category :libpod/commit
                                :conn conn
                                :version api-version})
              params {:container container-id}
              result (c/invoke client
                               {:op :ImageCommitLibpod
                                :params params
                                :throw-exceptions true})]
             (:Id result)
             (f/when-failed [err]
                            (log/errorf "Could not commit image: %s" (f/message err))
                            err)))

(defn stream-log [client id reaction-fn]
  (let [log-stream (c/invoke client {:op :ContainerLogsLibpod
                                     :params {:name id
                                              :follow true
                                              :stdout true
                                              :stderr true}
                                     :as :stream
                                     :throw-exceptions true})]
    (future
      (with-open [rdr (io/reader log-stream)]
        (loop [r (BufferedReader. rdr)]
          (when-let [line (.readLine r)]
            (-> line
                (str/replace-first #"^\W+" "")
                (reaction-fn))
            (recur r)))))))

(defn put-container-archive
  "Copies a tar input stream to a path in the container"
  [client id archive-input-stream path]
  (let [result (f/try*
                (with-open [xin archive-input-stream]
                  (c/invoke client
                            {:op :PutContainerArchiveLibpod
                             :params {:name id
                                      :path path}
                             :data xin
                             :throw-exceptions true})))]
    (when (f/failed? result)
      (log/errorf "Could not put archive in container: %s" result)
      result)))

(defn get-container-archive
  "Returns a tar stream of a path in the container by id."
  [client id path]
  (f/try-all [result (c/invoke client
                               {:op :ContainerArchiveLibpod
                                :params {:name id
                                         :path path}
                                :as :stream
                                :throw-exceptions true})]
             result
             (f/when-failed [err]
                            (log/errorf "Error fetching container archive: %s" err)
                            err)))

(defn create-tar-archive [arch-name path]
  (create-archive arch-name [path] "/tmp" "xz"))

(defn copy-resources-to-container! [client container-id resources]
  (doseq [{:keys [source dest]} resources]
    (let [temp-archive (create-archive "resource.tar" [source] "/tmp" "xz")]
      (put-container-archive client container-id (io/input-stream temp-archive) dest))))

(defn do-command!
  "Runs a command in a container from the `image` and returns result image

  `log-fn` is a function like #(prn \"CNT: \" %)"
  [client cmd image work-dir log-fn conn]
  (f/try-all [_ (log-fn (str ">> Running " cmd))
              result (c/invoke client {:op :ContainerCreateLibpod
                                       :data {:image image
                                              :command (sh-tokenize cmd)
                                              :env {}
                                              :work_dir work-dir
                                              :cgroups_mode "disabled"}
                                       :throw-exceptions true})
              container-id (:Id result)

              _ (c/invoke client {:op :ContainerStartLibpod
                                  :params {:name container-id}
                                  :throw-exceptions true})

              _ (log/debugf "Attaching to container %s for logs" container-id)
              _ (stream-log client container-id log-fn)

              status (c/invoke client {:op :ContainerWaitLibpod
                                       :params {:name container-id}
                                       :throw-exceptions true})

              image (commit-container container-id conn)]
             (if (zero? status)
               image
               (let [msg (format "Container %s exited with non-zero status %d" container-id status)]
                 (log/debug msg)
                 (f/fail msg)))))

(defn do-commands!
  "Runs an sequence of `commands` starting from `image`"
  [client commands image work-dir conn]
  (let [log-file (java.io.File/createTempFile "log" ".txt")]
    (with-open [w (io/writer log-file)]
      (loop [cmds commands
             img image]
        (let [[cmd & rst] cmds]
          (if (nil? cmd)
            [:success (.getAbsolutePath log-file) img]
            (f/if-let-failed? [result (do-command! client cmd img work-dir #(.write w (str % "\n")) conn)]
                              (do
                                (prn image work-dir result)
                                [:error (.getAbsolutePath log-file) (f/message result)])
                              (recur rst result))))))))

;; resources = copied from local disk to container before run
;; artifacts = copied from container to local disk after run
(defmethod nos/run-op :docker/run [_ _ [{:keys [image cmd conn artifacts resources work-dir]
                                         :or {conn {:uri "http://localhost:8080"}
                                              work-dir "/root"
                                              resources []
                                              artifacts []}}]]
  (f/try-all [_ (docker-pull conn image)
              _ (log/debugf "Pulled image %s" image)
              client (c/client {:engine :podman
                                :category :libpod/containers
                                :conn conn
                                :version api-version})

              result (c/invoke client
                               {:op :ContainerCreateLibpod
                                :data {:image image}
                                :throw-exceptions true})

              container-id (:Id result)

              _ (when (not-empty resources)
                  (log/debugf "Copying resources to container %s" container-id)
                  (copy-resources-to-container! client container-id resources))
              image (commit-container container-id conn)

              last-image (do-commands! client cmd image work-dir conn)]
             last-image
             (f/when-failed [err]
                            (log/errorf ":docker/run failed" )
                            (prn err)
                            [:error (f/message err)])))

(comment
  (nos/run-op :docker/run nil
              [{:image "alpine" :cmd ["touch /root/test" "ls /root"] :conn {:uri "http://localhost:8080"}}]))
