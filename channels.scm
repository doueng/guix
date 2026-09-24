(list
 (channel
  (name 'guix)
  (url "https://codeberg.org/guix/guix")
  (branch "master")
  (commit "7e74121a40a8308166e328a23647cf6f3768e6c8")
  (introduction
   (make-channel-introduction
    "9edb3f66fd807b096b48283debdcddccfea34bad"
    (openpgp-fingerprint
     "BBB0 2DDF 2CEA F6A8 0D1D  E643 A2A0 6DF2 A33A 54FA"))))
 (channel
  (name 'asahi)
  (url "https://codeberg.org/asahi-guix/channel")
  (branch "main")
  (commit "0a58b24a8448d75ec5570d28ebac2c88ebfbc540")
  (introduction
   (make-channel-introduction
    "3eeb493b037bea44f225c4314c5556aa25aff36c"
    (openpgp-fingerprint
     "D226 A339 D8DF 4481 5DDE  0CA0 3DDA 5252 7D2A C199"))))
 (channel
  (name 'rde)
  (url "https://git.sr.ht/~abcdw/rde")
  (branch "master")
  (commit "24955da51caf1aaad9a5f0e6c9c9845a504eff64")
  (introduction
   (make-channel-introduction
    "257cebd587b66e4d865b3537a9a88cccd7107c95"
    (openpgp-fingerprint
     "2841 9AC6 5038 7440 C7E9  2FFA 2208 D209 58C1 DEB0")))))
