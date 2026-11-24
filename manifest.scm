;; Development environment:  guix shell -m manifest.scm
;; shadow-cljs (from npm) needs Node and a JDK; git is for convenience.
(specifications->manifest
 (list "node" "openjdk@21" "git" "coreutils" "bash"))
