(define-module (engstrand packages python-anthropic)
  #:use-module (gnu packages python-build)
  #:use-module (gnu packages python-web)
  #:use-module (gnu packages python-xyz)
  #:use-module (guix build-system pyproject)
  #:use-module (guix download)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define-public python-anthropic
  (package
    (name "python-anthropic")
    (version "1.14.0")
    (source
     (origin
       (method url-fetch)
       (uri (pypi-uri "anthropic" version))
       (sha256
        (base32 "0f4fmiw9vp772k49graj75sdk2j8zm8wrb7hhznmg12ahsalha6r"))))
    (build-system pyproject-build-system)
    (arguments
     ;; The test suite needs a Prism mock of the Anthropic API.
     (list #:tests? #f))
    (native-inputs
     (list python-hatch-fancy-pypi-readme python-hatchling))
    (propagated-inputs
     (list python-anyio
           python-docstring-parser
           python-httpx2
           python-jiter
           python-opentelemetry-api
           python-pydantic
           python-sniffio
           python-typing-extensions))
    (home-page "https://github.com/anthropics/anthropic-sdk-python")
    (synopsis "Python client for the Anthropic API")
    (description
     "This package provides the official Python library for the Anthropic
REST API, with synchronous and asynchronous clients and typed request and
response models.")
    (license license:expat)))
