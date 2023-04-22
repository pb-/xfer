(ns xfer.rate-limit)

(defn ^:private update-rate-limit [state now interval-ms burst]
  (if (< (:tokens state) burst)
    (let [new-tokens (quot (- now (:last-addition state))
                           interval-ms)]
      (if (pos? new-tokens)
        (merge state
               {:last-addition now
                :tokens (min burst (+ (:tokens state) new-tokens))})
        state))
    state))

(defn ^:private consume-token [state now interval-ms burst]
  (let [updated-state (update-rate-limit state now interval-ms burst)]
    (if (pos? (:tokens updated-state))
      (merge updated-state
             {:tokens (dec (:tokens updated-state))
              :consumed? true})
      (assoc updated-state :consumed? false))))

(defn ^:private default-over-limit-handler [request]
  {:status 429
   :body "Too many requests"})

(defn wrap-token-bucket [replenish-interval-ms burst-count]
  (let [state (atom {:tokens burst-count
                     :last-addition (System/currentTimeMillis)})]
    (fn [handler]
      (fn [request]
        (let [now (System/currentTimeMillis)
              new-state (swap! state consume-token now replenish-interval-ms burst-count)]
          (if (:consumed? new-state)
            (handler request)
            (default-over-limit-handler request)))))))
