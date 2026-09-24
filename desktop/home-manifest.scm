;; Keep substitute previews in sync with the packages declared in Home.
;; Home services can add packages of their own; this covers explicit packages.
(use-modules (engstrand desktop)
             (gnu home)
             (guix profiles))

(packages->manifest (home-environment-packages %familiar-home))
