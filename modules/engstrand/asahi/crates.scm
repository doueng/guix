;; Rust crates for m1n1 and triforce-lv2, copied from the asahi-guix channel
;; (https://codeberg.org/asahi-guix/channel, b9132769, packages/rust-crates.scm),
;; GPL-3.0-or-later.
(define-module (engstrand asahi crates)
  #:use-module (guix build-system cargo)
  #:use-module (guix download)
  #:use-module (guix git-download)
  #:use-module (guix packages)
  #:export (lookup-cargo-inputs))

(define rust-bitflags-2.13.0
  (crate-source "bitflags" "2.13.0"
                "1y239gpvl061rfvav7jds8mjs42kmwi39is7yx5d1qw3hvp8nf5l"))

(define rust-fatfs-0.4.0.4eccb50
  (origin
    (method git-fetch)
    (uri (git-reference
          (url "https://github.com/rafalh/rust-fatfs")
          (commit "4eccb50d011146fbed20e133d33b22f3c27292e7")))
    (file-name (git-file-name "rust-fatfs" "0.4.0.4eccb50"))
    (sha256 (base32 "113ws5b0pgyhaa7qnxd47lchyfbbc7zskgxwj0gxy848vdv23ixm"))))

(define rust-log-0.4.33
  (crate-source "log" "0.4.33"
                "1bd9dmk22pxgnf0h0slba6rz99zb0a0b2mdhpk8p92bp26ycbvhc"))

(define rust-sha1-smol-1.0.1
  (crate-source "sha1_smol" "1.0.1"
                "0pbh2xjfnzgblws3hims0ib5bphv7r5rfdpizyh51vnzvnribymv"))

(define rust-uuid-1.23.4
  (crate-source "uuid" "1.23.4"
                "0lws65rrqncssdz1rk8g8ww7xg6k4d3l6avzkslzwni78llag05z"))

(define rust-approx-0.5.1
  (crate-source "approx" "0.5.1"
                "1ilpv3dgd58rasslss0labarq7jawxmivk17wsh8wmkdm3q15cfa"))

(define rust-autocfg-1.5.1
  (crate-source "autocfg" "1.5.1"
                "0lqasy5i30flcgih1b50kvsk6z32g09r1q4ql7q81pj6228jy0zj"))

(define rust-bytemuck-1.25.2
  (crate-source "bytemuck" "1.25.2"
                "15rp2m7j7kq22s76cbjwmrkd5r8lvacnm0mnrj013cnzka22x0wm"))

(define rust-glam-0.30.10
  (crate-source "glam" "0.30.10"
                "1a9as4c2sm3z9sczl2m6gn8fsfmg4iw8rrpinv8i58iphhz47z0r"))

(define rust-glam-0.31.1
  (crate-source "glam" "0.31.1"
                "1rsv5f5fb70bfm6ji37pv66g7gf9f4bb8yqf9skib38bm4p6nvsm"))

(define rust-glam-0.32.1
  (crate-source "glam" "0.32.1"
                "186cjxn5qknagm31vmxvxk1kwwrfvv6cqj99nvvcngh6bdllj1zp"))

(define rust-glam-0.33.12
  (crate-source "glam" "0.33.12"
                "1i560qhmvimzrismli8cc0f3zv178dan0nhy316za8gcr0yyggrp"))

(define rust-lv2-0.6.0
  (crate-source "lv2" "0.6.0"
                "1xh4hjfh2w5rhzbk0g9845k25f6fxrv7xqpkr09p0x57b200qc41"))

(define rust-lv2-atom-2.0.0
  (crate-source "lv2-atom" "2.0.0"
                "0wd9rgsn8sag8wyhjccmnn82gx4w1yyiav52nyvk579l21xlw6wm"))

(define rust-lv2-core-3.0.0
  (crate-source "lv2-core" "3.0.0"
                "1pj9l15zwqwj2h83f3xfpwxsj70vvhkw52gyzkljafvrbx1h00fm"))

(define rust-lv2-core-derive-2.1.1
  (crate-source "lv2-core-derive" "2.1.1"
                "12w3l41jzargrcywz13hbmaazfw4ix2sljl3601h6jfbdrw8zybv"))

(define rust-lv2-midi-1.2.0
  (crate-source "lv2-midi" "1.2.0"
                "0x0glbrfri1glgcrmvc6i1jfv6azhpqvp4ibk5cihsq3s2yfc8xd"))

(define rust-lv2-sys-2.0.0
  (crate-source "lv2-sys" "2.0.0"
                "0c4f59mrjyy0z0wf033wp648df0sc6zirrcd6kndqj9nvvkzkl4x"))

(define rust-lv2-units-0.1.3
  (crate-source "lv2-units" "0.1.3"
                "0fdamp3hxdr36hqi1j6y01rz1x17if1ibzr7rr4nrabidw74gf82"))

(define rust-lv2-urid-2.1.0
  (crate-source "lv2-urid" "2.1.0"
                "0s2fcb0nyn54ml6azkbhnnxghy898x1q5vs5qgdznrhy9m20624c"))

(define rust-matrixmultiply-0.3.11
  (crate-source "matrixmultiply" "0.3.11"
                "1izd5yczz4kmlvk99zyasfcvhbkby8fsa5rl0kkqdw2kflipqq1z"))

(define rust-nalgebra-0.35.0
  (crate-source "nalgebra" "0.35.0"
                "0ij3kbcp1qv7iikazqcbb8p8vb8m228jczz48vzwdc0pq9h3mi5d"))

(define rust-nalgebra-macros-0.3.0
  (crate-source "nalgebra-macros" "0.3.0"
                "12a8xgamrayyin4a77dan0bcx0nlb1v8hl7cricx1kvqlrw72glp"))

(define rust-num-bigint-0.4.8
  (crate-source "num-bigint" "0.4.8"
                "0ry3xjal8f5xhdinani268ci13h14mf7j4w0y1gflfzhw3knk7n8"))

(define rust-num-complex-0.4.6
  (crate-source "num-complex" "0.4.6"
                "15cla16mnw12xzf5g041nxbjjm9m85hdgadd5dl5d0b30w9qmy3k"))

(define rust-num-integer-0.1.47
  (crate-source "num-integer" "0.1.47"
                "02z1p3azy6p10n99skrab4a6hhfd4amf2i9gm8sxqd1p9dfxkqkw"))

(define rust-num-rational-0.4.2
  (crate-source "num-rational" "0.4.2"
                "093qndy02817vpgcqjnj139im3jl7vkq4h68kykdqqh577d18ggq"))

(define rust-num-traits-0.2.19
  (crate-source "num-traits" "0.2.19"
                "0h984rhdkkqd4ny9cif7y2azl3xdfb7768hb9irhpsch4q3gq787"))

(define rust-proc-macro2-1.0.107
  (crate-source "proc-macro2" "1.0.107"
                "1nb6ly8kp65f724kj73ippc7lvydss24sm2vagk6qpklpg4pwplq"))

(define rust-quote-1.0.47
  (crate-source "quote" "1.0.47"
                "00ch0yyzvv6s671ik0kcsbw8nigdaj2g3fr61kcahwx48aqlvgqz"))

(define rust-rawpointer-0.2.1
  (crate-source "rawpointer" "0.2.1"
                "1qy1qvj17yh957vhffnq6agq0brvylw27xgks171qrah75wmg8v0"))

(define rust-safe-arch-1.2.0
  (crate-source "safe_arch" "1.2.0"
                "0przxg40043xxwgfgzx5qpx882x4q6qb07x6kjryrrkmb2hyzij2"))

(define rust-simba-0.10.2
  (crate-source "simba" "0.10.2"
                "1rzxhzvyx06fjz5sb0ygj4n40yjkw88jzlgg6m17n77zh86j19zi"))

(define rust-syn-1.0.109
  (crate-source "syn" "1.0.109"
                "0ds2if4600bd59wsv7jjgfkayfzy3hnazs394kz6zdkmna8l3dkj"))

(define rust-syn-2.0.119
  (crate-source "syn" "2.0.119"
                "15vjy620l91a3q4n4f4gzhnflmdr6pnm38v2m6cpk86i8av32a47"))

(define rust-typenum-1.20.1
  (crate-source "typenum" "1.20.1"
                "086s9ly0906kw5yw41249fba97w5zfxf03pyfwdkffvcprqfixdn"))

(define rust-unicode-ident-1.0.26
  (crate-source "unicode-ident" "1.0.26"
                "0m3915ipi4zz7isncf5k1dz47ys0nq9j7l4l2n2rm03zaxwg8ifj"))

(define rust-urid-0.1.0
  (crate-source "urid" "0.1.0"
                "195672gs136vczn1r4hkjg5vfa7vdzr26bzv6lwhk0z7cvbvaa38"))

(define rust-urid-derive-0.1.1
  (crate-source "urid-derive" "0.1.1"
                "0i1nf0sgq4ai051h17s9msaavl3jfzdmdlsy8455pr88y0pfx7l1"))

(define rust-wide-1.7.1
  (crate-source "wide" "1.7.1"
                "0kycv850zdsmjcg5ps6w25h04vad6axpvl5q1h8wxvf8qfcsq86r"))

(define rust-wmidi-3.1.0
  (crate-source "wmidi" "3.1.0"
                "1kxnbs18nmpzm2hfwaaa5h2s77cmk5w53srzxqmrqlkdpdcrjafa"))

(define-cargo-inputs lookup-cargo-inputs
  (m1n1 => (list rust-bitflags-2.13.0
                 rust-fatfs-0.4.0.4eccb50
                 rust-log-0.4.33
                 rust-sha1-smol-1.0.1
                 rust-uuid-1.23.4))
  (triforce-lv2 => (list rust-approx-0.5.1
                         rust-autocfg-1.5.1
                         rust-bytemuck-1.25.2
                         rust-glam-0.30.10
                         rust-glam-0.31.1
                         rust-glam-0.32.1
                         rust-glam-0.33.12
                         rust-lv2-0.6.0
                         rust-lv2-atom-2.0.0
                         rust-lv2-core-3.0.0
                         rust-lv2-core-derive-2.1.1
                         rust-lv2-midi-1.2.0
                         rust-lv2-sys-2.0.0
                         rust-lv2-units-0.1.3
                         rust-lv2-urid-2.1.0
                         rust-matrixmultiply-0.3.11
                         rust-nalgebra-0.35.0
                         rust-nalgebra-macros-0.3.0
                         rust-num-bigint-0.4.8
                         rust-num-complex-0.4.6
                         rust-num-integer-0.1.47
                         rust-num-rational-0.4.2
                         rust-num-traits-0.2.19
                         rust-proc-macro2-1.0.107
                         rust-quote-1.0.47
                         rust-rawpointer-0.2.1
                         rust-safe-arch-1.2.0
                         rust-simba-0.10.2
                         rust-syn-1.0.109
                         rust-syn-2.0.119
                         rust-typenum-1.20.1
                         rust-unicode-ident-1.0.26
                         rust-urid-0.1.0
                         rust-urid-derive-0.1.1
                         rust-wide-1.7.1
                         rust-wmidi-3.1.0)))
