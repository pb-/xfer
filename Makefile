develop:
	# e3b... is the empty string
	LOGIN_PASSWORD_HASH=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 clojure -M:nrepl
.PHONY: develop

run:
	# e3b... is the empty string
	LOGIN_PASSWORD_HASH=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 clojure -M -m xfer.core
.PHONY: run
