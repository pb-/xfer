(ns xfer.core
  (:require [clojure.string :refer [starts-with? join]]
            [hiccup.page :refer [html5]]
            [hiccup.form :refer [form-to password-field submit-button file-upload]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :refer [resource-response content-type]]
            [ring.util.mime-type :refer [ext-mime-type]]
            [ring.util.io :refer [piped-input-stream]]
            [xfer.rate-limit :refer [wrap-token-bucket]])
  (:import [java.util.concurrent SynchronousQueue]
           [java.util.zip ZipOutputStream ZipEntry]
           [java.security MessageDigest]
           [java.time Instant]
           [dev.baecher.multipart StreamingMultipartParser])
  (:gen-class))

(defn parseHexDigits [s]
  (byte-array (drop 1 (.toByteArray (BigInteger. s 16)))))

(def login-password-hash (parseHexDigits (System/getenv "LOGIN_PASSWORD_HASH")))

(def login-interval-sec 60)
(def max-burst-logins 10)

(defonce app-state (atom {}))

(defn collect-garbage [state now]
  (into
    {}
    (for [transfer state
          :when (< now (+ (* 60 60 1000) (:started-at (val transfer))))]
      transfer)))

(defn generate-id []
  (let [alphabet "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"]
    (join (repeatedly 12 #(rand-nth alphabet)))))

(defn sha256 [s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

(defn match-route [prefix request]
  (let [uri (:uri request)]
    (when (starts-with? uri prefix)
      (update request :uri subs (count prefix)))))

(defn page
  ([body]
   (page body {}))
  ([body opts]
   {:status (or (:status opts) 200)
    :body (html5
            [:head
             [:meta {:charset "UTF-8"}]
             [:meta {:name "viewport"
                     :content "width=device-width, height=device-height, initial-scale=1.0, minimum-scale=1.0"}]
             [:link {:rel "stylesheet"
                     :href "/asset/main.css"
                     :type "text/css"}]
             (when (:refresh? opts)
               [:meta {:http-equiv "refresh"
                       :content "1"}])]
            [:body body])}))

(defn home-form [message]
  (page
    (form-to
      {:class "home"}
      [:post "/"]
      (password-field {:placeholder "Password"
                       :autofocus "autofocus"} "password")
      (when message
        [:p.message message])
      (submit-button {:name "send"} "Send data")
      (submit-button {:name "receive"} "Receive data"))))

(defn handle-form [request]
  (let [params (:form-params request)
        password (params "password")
        status (cond
                 (params "send") :waiting-for-receiver
                 (params "receive") :waiting-for-sender)]
    (if (MessageDigest/isEqual login-password-hash (sha256 password))
      (let [id (generate-id)
            path (case status
                   :waiting-for-receiver "s"
                   :waiting-for-sender "r")]
        (swap! app-state assoc id {:id id
                                   :started-at (System/currentTimeMillis)
                                   :status status})
        {:status 302
         :headers {"Location" (str "/" path "/" id)}})
      (home-form "Password is no good"))))

(def handle-form-rate-limited
  ((wrap-token-bucket (* 1000 login-interval-sec) max-burst-logins) handle-form))

(defn home [request]
  (if (empty? (:uri request))
    (if (= :post (:request-method request))
      (handle-form-rate-limited request)
      (home-form nil))
    (page [:p "Not here"] {:status 404})))


(defn upload-form [request]
  [:div
   [:h2 "Receiver connected, choose data to send"]
   (form-to
     {:class "upload-form"}
     [:post "."]
     (file-upload {:multiple "multiple"} "files")
     [:progress {:value "0"}]
     [:p#status]
     (submit-button "Send"))
   [:script {:src "/asset/vendor/no-sleep.min.js"}]
   [:script {:src "/asset/upload.js"}]])

(defn handle-upload [request]
  (let [done (promise)]
    (swap! app-state update (:uri request) merge {:status :data-ready
                                                  :done done
                                                  :input-stream (:body request)})
    (let [success? (deref done (* 10 60 1000) false)]
      (swap! app-state dissoc (:uri request))
      (if success?
        (page [:p "All done!"])
        (page [:p "Timed out."] {:status 400})))))

(defn handle-download [request transfer]
  {:status 200
   :headers {"Content-Disposition" (str "attachment; filename=\"transfer-" (:id transfer) ".zip\"")}
   :body (piped-input-stream
           (fn [output-stream]
             (let [parser (StreamingMultipartParser. (:input-stream transfer))
                   zip-stream (ZipOutputStream. output-stream)]
               (.setLevel zip-stream 0)
               (while (.hasNext parser)
                 (let [part (.next parser)]
                   (.putNextEntry zip-stream (ZipEntry. (.getFilename (.getHeaders part))))
                   (.transferTo (.getInputStream part) zip-stream)
                   (.closeEntry zip-stream)))
               (.finish zip-stream)
               (deliver (:done transfer) true))))})

(defn scan [transfer]
  (let [path (case  (:status transfer)
               :waiting-for-receiver "r"
               :waiting-for-sender "s")]
    (page
      [:div
       [:script {:src "/asset/vendor/qrcode.min.js"}]
       [:h2 "Scan with other device"]
       [:div#qrcode]
       [:script
        "const q = document.getElementById('qrcode')\n"
        "new QRCode(q, {"
        "width: q.scrollWidth,"
        "height: q.scrollWidth,"
        "text: document.location.origin + '/" path "/" (:id transfer) "'});"]]
      {:refresh? true})))

(defn set-transfer-status [state id status]
  (assoc-in state [id :status] status))

(defn sender [request]
  (if-let [transfer (@app-state (:uri request))]
    (if (and (= :picking-files (:status transfer))
             (= :post (:request-method request)))
      (do
        (swap! app-state set-transfer-status (:id transfer) :data-ready)
        (handle-upload request))
      (case (:status transfer)
        :waiting-for-receiver (scan transfer)
        :waiting-for-sender (do
                              (swap! app-state set-transfer-status (:id transfer) :picking-files)
                              (page (upload-form request)))
        :picking-files (page (upload-form request))))
    (page [:p "Not here. " [:a {:href "/"} "Transfer some data?"]] {:status 404})))

(defn waiting []
  (page [:h2 "Sender is picking files, hold on…"] {:refresh? true}))

(defn receiver [request]
  (if-let [transfer (@app-state (:uri request))]
    (case (:status transfer)
      :waiting-for-sender (scan transfer)
      :waiting-for-receiver (do
                              (swap! app-state set-transfer-status (:id transfer) :picking-files)
                              (waiting))
      :picking-files (waiting)
      :data-ready (do
                    (swap! app-state set-transfer-status (:id transfer) :transferring)
                    (page [:h2 "Data is ready! Downloading…"] {:refresh? true}))
      :transferring (handle-download request transfer))
    (page [:p "Not here."] {:status 404})))

(defn asset [request]
  (let [file (:uri request)]
    (if (#{"upload.js"
           "main.css"
           "vendor/no-sleep.min.js"
           "vendor/qrcode.min.js"} file)
      (content-type
        (resource-response file {:root "public"})
        (ext-mime-type file))
      (page [:p "Not here."] {:status 404}))))

(defn app [request]
  (let [now (Instant/now)]
    (println
      (str now)
      ((:headers request) "x-forwarded-for")
      (:uri request)
      ((:headers request) "user-agent"))
    (swap! app-state collect-garbage (inst-ms now))
    (condp match-route request
      "/asset/" :>> asset
      "/r/" :>> receiver
      "/s/" :>> sender
      "/" :>> (wrap-params home))))

(defn -main []
  (run-jetty app {:port 8080 :send-server-version? false}))

(comment
  ;; evaluate this to start the development server
  (do
    (require '[ring.middleware.reload :refer [wrap-reload]])
    (run-jetty (wrap-reload #'app) {:port 4711 :join? false})))
