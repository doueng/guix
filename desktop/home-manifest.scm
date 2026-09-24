;; Keep substitute previews in sync with explicit packages declared in Home.
(use-modules (engstrand config)
             (guix profiles))

(packages->manifest %familiar-home-packages)
