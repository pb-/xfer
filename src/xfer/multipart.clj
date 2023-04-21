(ns xfer.multipart)

(def ^:private buffer-size 4096)

(defn ^:private compact-buffer
  "Move data to the front of the buffer."
  [{:keys [data offset length] :as buffer}]
  (if (pos? offset)
    (do
      (System/arraycopy data offset data 0 length)
      (assoc buffer :offset 0))
    buffer))

(defn ^:private refill-buffer
  "Fill up the buffer from input stream."
  [{:keys [data offset length input-stream eof?] :as buffer}]
  (assert (not eof?))
  (let [capacity (- (count data) offset length)]
    (if (pos? capacity)
      (let [bytes-read (.readNBytes input-stream data (+ offset length) capacity)]
        (if (pos? bytes-read)
          (update buffer :length + bytes-read)
          (assoc buffer :eof? true)))
      buffer)))

(defn ^:private check-part-end [buffer]
  buffer)

(defn ^:private prepare-buffer [buffer]
  (check-part-end (refill-buffer (compact-buffer buffer))))

(defn ^:private boundary
  "Get the boundary at the beginning of the buffer"
  [{:keys [data offset length]}]
  (byte-array (take-while (complement #{13}) (take length (drop offset data)))))

(defn ^:private read-part [state]
  (let [status (:status @state)]
    (when (not= status :read-header)
      (throw (IllegalStateException. "must read data before reading next part")))
    (swap! state update :buffer prepare-buffer)
    (when-not (= (take 2 (:data @state)) [45 45]))))

(defn ^:private read-parts [state]
  (lazy-seq
    (cons (read-part state)
          (read-parts state))))

(defn parts [input-stream]
  (let [state (atom {:buffer {:input-stream input-stream
                              :data (byte-array buffer-size)
                              :offset 0
                              :length 0
                              :eof? false}
                     :status :read-header})]
    (swap! state update :buffer refill-buffer)
    (swap! state assoc :boundary (boundary (:buffer @state)))
    (swap! state update-in [:buffer :offset] + (count (:boundary @state)))
    (read-parts state)))


(comment
  (parts
    (java.io.ByteArrayInputStream.
      (.getBytes
        (str "----AaB03x\r\n"
             "Content-Disposition: form-data; name=\"submit-name\"\r\n"
             "\r\n"
             "Larry\r\n"
             "----AaB03x\r\n"
             "Content-Disposition: form-data; name=\"files\"; filename=\"file1.txt\"\r\n"
             "Content-Type: text/plain\r\n"
             "\r\n"
             "HELLO WORLD!\r\n"
             "----AaB03x--\r\n")
        "utf-8"))))
