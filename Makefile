develop:
	clojure -M:nrepl
.PHONY: develop

run:
	clojure -M -m xfer.core
.PHONY: run
