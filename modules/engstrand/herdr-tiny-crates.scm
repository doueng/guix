(define-module (engstrand herdr-tiny-crates)
  #:use-module (guix build-system cargo)
  #:export (herdr-tiny-fingers-crate-inputs))

(define rust-aho-corasick-1.1.4
  (crate-source "aho-corasick" "1.1.4"
                "00a32wb2h07im3skkikc495jvncf62jl6s96vwc7bhi70h9imlyx"))

(define rust-allocator-api2-0.2.21
  (crate-source "allocator-api2" "0.2.21"
                "08zrzs022xwndihvzdn78yqarv2b9696y67i6h78nla3ww87jgb8"))

(define rust-anyhow-1.0.103
  (crate-source "anyhow" "1.0.103"
                "1wsav2g6vxcvf2c0fv3jhxfr55l0p2g8nygy7rmmvcsfwgi8ahra"))

(define rust-approx-0.5.1
  (crate-source "approx" "0.5.1"
                "1ilpv3dgd58rasslss0labarq7jawxmivk17wsh8wmkdm3q15cfa"))

(define rust-atomic-0.6.1
  (crate-source "atomic" "0.6.1"
                "0h43ljcgbl6vk62hs6yk7zg7qn3myzvpw8k7isb9nzhkbdvvz758"))

(define rust-autocfg-1.5.1
  (crate-source "autocfg" "1.5.1"
                "0lqasy5i30flcgih1b50kvsk6z32g09r1q4ql7q81pj6228jy0zj"))

(define rust-base64-0.22.1
  (crate-source "base64" "0.22.1"
                "1imqzgh7bxcikp5vx3shqvw9j09g9ly0xr0jma0q66i52r7jbcvj"))

(define rust-bit-set-0.5.3
  (crate-source "bit-set" "0.5.3"
                "1wcm9vxi00ma4rcxkl3pzzjli6ihrpn9cfdi0c5b4cvga2mxs007"))

(define rust-bit-vec-0.6.3
  (crate-source "bit-vec" "0.6.3"
                "1ywqjnv60cdh1slhz67psnp422md6jdliji6alq0gmly2xm9p7rl"))

(define rust-bitflags-1.3.2
  (crate-source "bitflags" "1.3.2"
                "12ki6w8gn1ldq7yz9y680llwk5gmrhrzszaa17g1sbrw2r2qvwxy"))

(define rust-bitflags-2.13.0
  (crate-source "bitflags" "2.13.0"
                "1y239gpvl061rfvav7jds8mjs42kmwi39is7yx5d1qw3hvp8nf5l"))

(define rust-block-buffer-0.10.4
  (crate-source "block-buffer" "0.10.4"
                "0w9sa2ypmrsqqvc20nhwr75wbb5cjr4kkyhpjm1z1lv2kdicfy1h"))

(define rust-bumpalo-3.20.3
  (crate-source "bumpalo" "3.20.3"
                "0jc6va3nwcqikm7chnpdv1s87my3gs2j7g1sc7g3k91brg3arxbj"))

(define rust-by-address-1.2.1
  (crate-source "by_address" "1.2.1"
                "01idmag3lcwnnqrnnyik2gmbrr34drsi97q15ihvcbbidf2kryk4"))

(define rust-bytemuck-1.25.0
  (crate-source "bytemuck" "1.25.0"
                "1v1z32igg9zq49phb3fra0ax5r2inf3aw473vldnm886sx5vdvy8"))

(define rust-castaway-0.2.4
  (crate-source "castaway" "0.2.4"
                "0nn5his5f8q20nkyg1nwb40xc19a08yaj4y76a8q2y3mdsmm3ify"))

(define rust-cfg-if-1.0.4
  (crate-source "cfg-if" "1.0.4"
                "008q28ajc546z5p2hcwdnckmg0hia7rnx52fni04bwqkzyrghc4k"))

(define rust-cfg-aliases-0.2.1
  (crate-source "cfg_aliases" "0.2.1"
                "092pxdc1dbgjb6qvh83gk56rkic2n2ybm4yvy76cgynmzi3zwfk1"))

(define rust-compact-str-0.9.1
  (crate-source "compact_str" "0.9.1"
                "1aq0vx3xnaxf9k8p1pwch5v5av0xj2ddq2av25aa76jd4z1d3zcx"))

(define rust-convert-case-0.10.0
  (crate-source "convert_case" "0.10.0"
                "1fff1x78mp2c233g68my0ag0zrmjdbym8bfyahjbfy4cxza5hd33"))

(define rust-cpufeatures-0.2.17
  (crate-source "cpufeatures" "0.2.17"
                "10023dnnaghhdl70xcds12fsx2b966sxbxjq5sxs49mvxqw5ivar"))

(define rust-critical-section-1.2.0
  (crate-source "critical-section" "1.2.0"
                "02ylhcykxjc40xrfhk1lwc21jqgz4dbwv3jr49ymw733c51yl3kr"))

(define rust-crossterm-0.29.0
  (crate-source "crossterm" "0.29.0"
                "0yzqxxd90k7d2ac26xq1awsznsaq0qika2nv1ik3p0vzqvjg5ffq"))

(define rust-crossterm-winapi-0.9.1
  (crate-source "crossterm_winapi" "0.9.1"
                "0axbfb2ykbwbpf1hmxwpawwfs8wvmkcka5m561l7yp36ldi7rpdc"))

(define rust-crypto-common-0.1.7
  (crate-source "crypto-common" "0.1.7"
                "02nn2rhfy7kvdkdjl457q2z0mklcvj9h662xrq6dzhfialh2kj3q"))

(define rust-csscolorparser-0.6.2
  (crate-source "csscolorparser" "0.6.2"
                "1gxh11hajx96mf5sd0az6mfsxdryfqvcfcphny3yfbfscqq7sapb"))

(define rust-darling-0.23.0
  (crate-source "darling" "0.23.0"
                "179fj6p6ajw4dnkrik51wjhifxwy02x5zhligyymcb905zd17bi5"))

(define rust-darling-core-0.23.0
  (crate-source "darling_core" "0.23.0"
                "1c033vrks38vpw8kwgd5w088dsr511kfz55n9db56prkgh7sarcq"))

(define rust-darling-macro-0.23.0
  (crate-source "darling_macro" "0.23.0"
                "13fvzji9xyp304mgq720z5l0xgm54qj68jibwscagkynggn88fdc"))

(define rust-deltae-0.3.2
  (crate-source "deltae" "0.3.2"
                "1d3hw9hpvicl9x0x34jr2ybjk5g5ym1lhbyz6zj31110gq8zaaap"))

(define rust-deranged-0.5.8
  (crate-source "deranged" "0.5.8"
                "0711df3w16vx80k55ivkwzwswziinj4dz05xci3rvmn15g615n3w"))

(define rust-derive-more-2.1.1
  (crate-source "derive_more" "2.1.1"
                "0d5i10l4aff744jw7v4n8g6cv15rjk5mp0f1z522pc2nj7jfjlfp"))

(define rust-derive-more-impl-2.1.1
  (crate-source "derive_more-impl" "2.1.1"
                "1jwdp836vymp35d7mfvvalplkdgk2683nv3zjlx65n1194k9g6kr"))

(define rust-diff-0.1.13
  (crate-source "diff" "0.1.13"
                "1j0nzjxci2zqx63hdcihkp0a4dkdmzxd7my4m7zk6cjyfy34j9an"))

(define rust-digest-0.10.7
  (crate-source "digest" "0.10.7"
                "14p2n6ih29x81akj097lvz7wi9b6b9hvls0lwrv7b6xwyy0s5ncy"))

(define rust-document-features-0.2.12
  (crate-source "document-features" "0.2.12"
                "0qcgpialq3zgvjmsvar9n6v10rfbv6mk6ajl46dd4pj5hn3aif6l"))

(define rust-either-1.16.0
  (crate-source "either" "1.16.0"
                "17k7jfbdz7k440h6lws9baz8p9zlxgb41sig3w81h80nwzsjyqli"))

(define rust-equivalent-1.0.2
  (crate-source "equivalent" "1.0.2"
                "03swzqznragy8n0x31lqc78g2af054jwivp7lkrbrc0khz74lyl7"))

(define rust-errno-0.3.14
  (crate-source "errno" "0.3.14"
                "1szgccmh8vgryqyadg8xd58mnwwicf39zmin3bsn63df2wbbgjir"))

(define rust-euclid-0.22.14
  (crate-source "euclid" "0.22.14"
                "01ksjl4vb8ms89laswnjpld3z4n6c1s7qlqq0djx3imiwdjm787i"))

(define rust-fancy-regex-0.11.0
  (crate-source "fancy-regex" "0.11.0"
                "18j0mmzfycibhxhhhfja00dxd1vf8x5c28lbry224574h037qpxr"))

(define rust-fast-srgb8-1.0.0
  (crate-source "fast-srgb8" "1.0.0"
                "18g6xwwh4gnkyx1352hnvwagpv0n4y98yp2llm8vyvwxh487abnx"))

(define rust-filedescriptor-0.8.3
  (crate-source "filedescriptor" "0.8.3"
                "0bb8qqa9h9sj2mzf09yqxn260qkcqvmhmyrmdjvyxcn94knmh1z4"))

(define rust-finl-unicode-1.4.0
  (crate-source "finl_unicode" "1.4.0"
                "1md4j32sa8g6y7q9yphpslhhjdjxig1bczkjp8mxccz5lv1xsi4q"))

(define rust-fixedbitset-0.4.2
  (crate-source "fixedbitset" "0.4.2"
                "101v41amgv5n9h4hcghvrbfk5vrncx1jwm35rn5szv4rk55i7rqc"))

(define rust-fnv-1.0.7
  (crate-source "fnv" "1.0.7"
                "1hc2mcqha06aibcaza94vbi81j6pr9a1bbxrxjfhc91zin8yr7iz"))

(define rust-foldhash-0.2.0
  (crate-source "foldhash" "0.2.0"
                "1nvgylb099s11xpfm1kn2wcsql080nqmnhj1l25bp3r2b35j9kkp"))

(define rust-futures-core-0.3.32
  (crate-source "futures-core" "0.3.32"
                "07bbvwjbm5g2i330nyr1kcvjapkmdqzl4r6mqv75ivvjaa0m0d3y"))

(define rust-futures-task-0.3.32
  (crate-source "futures-task" "0.3.32"
                "14s3vqf8llz3kjza33vn4ixg6kwxp61xrysn716h0cwwsnri2xq3"))

(define rust-futures-util-0.3.32
  (crate-source "futures-util" "0.3.32"
                "1mn60lw5kh32hz9isinjlpw34zx708fk5q1x0m40n6g6jq9a971q"))

(define rust-generic-array-0.14.7
  (crate-source "generic-array" "0.14.7"
                "16lyyrzrljfq424c3n8kfwkqihlimmsg5nhshbbp48np3yjrqr45"))

(define rust-getrandom-0.3.4
  (crate-source "getrandom" "0.3.4"
                "1zbpvpicry9lrbjmkd4msgj3ihff1q92i334chk7pzf46xffz7c9"))

(define rust-getrandom-0.4.3
  (crate-source "getrandom" "0.4.3"
                "16b0202fkdwz3p2cyll82dv24ljbn0wiyy829v4lwbkbflyqh3ih"))

(define rust-hashbrown-0.16.1
  (crate-source "hashbrown" "0.16.1"
                "004i3njw38ji3bzdp9z178ba9x3k0c1pgy8x69pj7yfppv4iq7c4"))

(define rust-hashbrown-0.17.1
  (crate-source "hashbrown" "0.17.1"
                "0jmqz7i4yl6cm7rbn0i2ffkfrmwi6xkmzkaldr2v8bcsx2v0jngd"))

(define rust-heck-0.5.0
  (crate-source "heck" "0.5.0"
                "1sjmpsdl8czyh9ywl3qcsfsq9a307dg4ni2vnlwgnzzqhc4y0113"))

(define rust-hex-0.4.3
  (crate-source "hex" "0.4.3"
                "0w1a4davm1lgzpamwnba907aysmlrnygbqmfis2mqjx5m552a93z"))

(define rust-ident-case-1.0.1
  (crate-source "ident_case" "1.0.1"
                "0fac21q6pwns8gh1hz3nbq15j8fi441ncl6w4vlnd1cmc55kiq5r"))

(define rust-indexmap-2.14.0
  (crate-source "indexmap" "2.14.0"
                "1na9z6f0d5pkjr1lgsni470v98gv2r7c41j8w48skr089x2yjrnl"))

(define rust-indoc-2.0.7
  (crate-source "indoc" "2.0.7"
                "01np60qdq6lvgh8ww2caajn9j4dibx9n58rvzf7cya1jz69mrkvr"))

(define rust-instability-0.3.12
  (crate-source "instability" "0.3.12"
                "0wc98mr44w5k1y6pib2x0kydmhbff8gkfgiw36ls684ry47ddcjy"))

(define rust-itertools-0.14.0
  (crate-source "itertools" "0.14.0"
                "118j6l1vs2mx65dqhwyssbrxpawa90886m3mzafdvyip41w2q69b"))

(define rust-itoa-1.0.18
  (crate-source "itoa" "1.0.18"
                "10jnd1vpfkb8kj38rlkn2a6k02afvj3qmw054dfpzagrpl6achlg"))

(define rust-js-sys-0.3.103
  ;; TODO REVIEW: Check bundled sources.
  (crate-source "js-sys" "0.3.103"
                "00lib0b6hqmw56r2hjp7xrv730qacslirbkdlhvmi39zvgy4pd2k"))

(define rust-kasuari-0.4.12
  (crate-source "kasuari" "0.4.12"
                "1688q59qh1mxa28k00lnddn73mh3jcdmj3yrc7l99k23c5yhbrdx"))

(define rust-lab-0.11.0
  (crate-source "lab" "0.11.0"
                "13ymsn5cwl5i9pmp5mfmbap7q688dcp9a17q82crkvb784yifdmz"))

(define rust-lazy-static-1.5.0
  (crate-source "lazy_static" "1.5.0"
                "1zk6dqqni0193xg6iijh7i3i44sryglwgvx20spdvwk3r6sbrlmv"))

(define rust-libc-0.2.186
  (crate-source "libc" "0.2.186"
                "0rnyhzjyqq9x56skkllbjzzzwym3r61lq3l4hqj64v71gw0r3av8"))

(define rust-libm-0.2.16
  (crate-source "libm" "0.2.16"
                "10brh0a3qjmbzkr5mf5xqi887nhs5y9layvnki89ykz9xb1wxlmn"))

(define rust-line-clipping-0.3.7
  (crate-source "line-clipping" "0.3.7"
                "1y19rla4ivdwagf0y4yahvb8jzsddj3jcb8r0xa8n9i3fvsfhl1z"))

(define rust-linux-raw-sys-0.12.1
  ;; TODO REVIEW: Check bundled sources.
  (crate-source "linux-raw-sys" "0.12.1"
                "0lwasljrqxjjfk9l2j8lyib1babh2qjlnhylqzl01nihw14nk9ij"))

(define rust-litrs-1.0.0
  (crate-source "litrs" "1.0.0"
                "14p0kzzkavnngvybl88nvfwv031cc2qx4vaxpfwsiifm8grdglqi"))

(define rust-lock-api-0.4.14
  (crate-source "lock_api" "0.4.14"
                "0rg9mhx7vdpajfxvdjmgmlyrn20ligzqvn8ifmaz7dc79gkrjhr2"))

(define rust-log-0.4.33
  (crate-source "log" "0.4.33"
                "1bd9dmk22pxgnf0h0slba6rz99zb0a0b2mdhpk8p92bp26ycbvhc"))

(define rust-lru-0.18.0
  (crate-source "lru" "0.18.0"
                "1fagimn8n3ivc3diad3jf323lbx86x1cyffjky31dklgjq2hd1la"))

(define rust-mac-address-1.1.8
  (crate-source "mac_address" "1.1.8"
                "00r3n18mxglq1dzshnm0vxk1fgsp3c2hd08w6hfcqdp8ymmv5bn0"))

(define rust-memchr-2.8.3
  (crate-source "memchr" "2.8.3"
                "161xa63ipfanf8v3nb82xd5hqgydv55nzw59wyngqbz6alfaz2yg"))

(define rust-memmem-0.1.1
  (crate-source "memmem" "0.1.1"
                "05ccifqgxdfxk6yls41ljabcccsz3jz6549l1h3cwi17kr494jm6"))

(define rust-memoffset-0.9.1
  (crate-source "memoffset" "0.9.1"
                "12i17wh9a9plx869g7j4whf62xw68k5zd4k0k5nh6ys5mszid028"))

(define rust-minimal-lexical-0.2.1
  (crate-source "minimal-lexical" "0.2.1"
                "16ppc5g84aijpri4jzv14rvcnslvlpphbszc7zzp6vfkddf4qdb8"))

(define rust-mio-1.2.1
  (crate-source "mio" "1.2.1"
                "1nkggmrlnjs93w8rja4lvjj4aml1xqahgimv1h0p7d373kvhmg82"))

(define rust-nix-0.29.0
  (crate-source "nix" "0.29.0"
                "0ikvn7s9r2lrfdm3mx1h7nbfjvcc6s9vxdzw7j5xfkd2qdnp9qki"))

(define rust-nom-7.1.3
  (crate-source "nom" "7.1.3"
                "0jha9901wxam390jcf5pfa0qqfrgh8li787jx2ip0yk5b8y9hwyj"))

(define rust-num-conv-0.2.2
  (crate-source "num-conv" "0.2.2"
                "0hg4f9bwmy7cwpxdkm165dmkfc8jhkkayci234jsmi5ssb33j5sj"))

(define rust-num-derive-0.4.2
  (crate-source "num-derive" "0.4.2"
                "00p2am9ma8jgd2v6xpsz621wc7wbn1yqi71g15gc3h67m7qmafgd"))

(define rust-num-traits-0.2.19
  (crate-source "num-traits" "0.2.19"
                "0h984rhdkkqd4ny9cif7y2azl3xdfb7768hb9irhpsch4q3gq787"))

(define rust-num-threads-0.1.7
  (crate-source "num_threads" "0.1.7"
                "1ngajbmhrgyhzrlc4d5ga9ych1vrfcvfsiqz6zv0h2dpr2wrhwsw"))

(define rust-once-cell-1.21.4
  (crate-source "once_cell" "1.21.4"
                "0l1v676wf71kjg2khch4dphwh1jp3291ffiymr2mvy1kxd5kwz4z"))

(define rust-ordered-float-4.6.0
  (crate-source "ordered-float" "4.6.0"
                "0ldrcgilsiijd141vw51fbkziqmh5fpllil3ydhirjm67wdixdvv"))

(define rust-palette-0.7.6
  (crate-source "palette" "0.7.6"
                "1rmn02mv6cb112504qyg7pyfa83c08hxpk5sw7jc5v659hc73gsc"))

(define rust-palette-derive-0.7.6
  (crate-source "palette_derive" "0.7.6"
                "0c0xhpk1nqyq4jr2m8xnka7w47vqzc7m2vq9ih8wxyjv02phs0zm"))

(define rust-parking-lot-0.12.5
  (crate-source "parking_lot" "0.12.5"
                "06jsqh9aqmc94j2rlm8gpccilqm6bskbd67zf6ypfc0f4m9p91ck"))

(define rust-parking-lot-core-0.9.12
  (crate-source "parking_lot_core" "0.9.12"
                "1hb4rggy70fwa1w9nb0svbyflzdc69h047482v2z3sx2hmcnh896"))

(define rust-pest-2.8.7
  (crate-source "pest" "2.8.7"
                "1sc2jzy3hjvj7qqwbygl4psbnzf1lk2j9kbbiin2ssjw63bpsqj7"))

(define rust-pest-derive-2.8.7
  (crate-source "pest_derive" "2.8.7"
                "0n4xs953qz7yyl4f3iibcflh3fh3v98vl9wyd2midm6abqr58hjb"))

(define rust-pest-generator-2.8.7
  (crate-source "pest_generator" "2.8.7"
                "19z0jlls9aqn5yfrrpg2vfqq50ifzh1z19mwxjngga6pxa8hwk3c"))

(define rust-pest-meta-2.8.7
  (crate-source "pest_meta" "2.8.7"
                "0462h8zrm7vr1fdy49mxi5gfs7nlpbrbajwj6iiy1zhnh724nx7r"))

(define rust-phf-0.11.3
  (crate-source "phf" "0.11.3"
                "0y6hxp1d48rx2434wgi5g8j1pr8s5jja29ha2b65435fh057imhz"))

(define rust-phf-codegen-0.11.3
  (crate-source "phf_codegen" "0.11.3"
                "0si1n6zr93kzjs3wah04ikw8z6npsr39jw4dam8yi9czg2609y5f"))

(define rust-phf-generator-0.11.3
  (crate-source "phf_generator" "0.11.3"
                "0gc4np7s91ynrgw73s2i7iakhb4lzdv1gcyx7yhlc0n214a2701w"))

(define rust-phf-macros-0.11.3
  (crate-source "phf_macros" "0.11.3"
                "05kjfbyb439344rhmlzzw0f9bwk9fp95mmw56zs7yfn1552c0jpq"))

(define rust-phf-shared-0.11.3
  (crate-source "phf_shared" "0.11.3"
                "1rallyvh28jqd9i916gk5gk2igdmzlgvv5q0l3xbf3m6y8pbrsk7"))

(define rust-pin-project-lite-0.2.17
  (crate-source "pin-project-lite" "0.2.17"
                "1kfmwvs271si96zay4mm8887v5khw0c27jc9srw1a75ykvgj54x8"))

(define rust-portable-atomic-1.13.1
  (crate-source "portable-atomic" "1.13.1"
                "0j8vlar3n5acyigq8q6f4wjx3k3s5yz0rlpqrv76j73gi5qr8fn3"))

(define rust-powerfmt-0.2.0
  (crate-source "powerfmt" "0.2.0"
                "14ckj2xdpkhv3h6l5sdmb9f1d57z8hbfpdldjc2vl5givq2y77j3"))

(define rust-pretty-assertions-1.4.1
  (crate-source "pretty_assertions" "1.4.1"
                "0v8iq35ca4rw3rza5is3wjxwsf88303ivys07anc5yviybi31q9s"))

(define rust-proc-macro2-1.0.106
  (crate-source "proc-macro2" "1.0.106"
                "0d09nczyaj67x4ihqr5p7gxbkz38gxhk4asc0k8q23g9n85hzl4g"))

(define rust-quote-1.0.46
  (crate-source "quote" "1.0.46"
                "0s034glrlav8nzqy2yskqzv52ncy82k126sm2jk5j1vs1iylbg6z"))

(define rust-r-efi-5.3.0
  (crate-source "r-efi" "5.3.0"
                "03sbfm3g7myvzyylff6qaxk4z6fy76yv860yy66jiswc2m6b7kb9"))

(define rust-r-efi-6.0.0
  (crate-source "r-efi" "6.0.0"
                "1gyrl2k5fyzj9k7kchg2n296z5881lg7070msabid09asp3wkp7q"))

(define rust-rand-0.8.6
  (crate-source "rand" "0.8.6"
                "12kd4rljn86m00rcaz4c1rcya4mb4gk5ig6i8xq00a8wjgxfr82w"))

(define rust-rand-core-0.6.4
  (crate-source "rand_core" "0.6.4"
                "0b4j2v4cb5krak1pv6kakv4sz6xcwbrmy2zckc32hsigbrwy82zc"))

(define rust-ratatui-0.30.2
  (crate-source "ratatui" "0.30.2"
                "0zfmk50bl3ahjjq0z55h5rmx5njrvks20p80lb9cl6sy5h5blx1j"))

(define rust-ratatui-core-0.1.2
  (crate-source "ratatui-core" "0.1.2"
                "1727mqrvy80hmg5nbf0dwh68rrlnmsi76mqzkn08mqn86g27bcfb"))

(define rust-ratatui-crossterm-0.1.2
  (crate-source "ratatui-crossterm" "0.1.2"
                "1w59rh1xdvd133yxnqskxcjnf9lp2j3b8h6y4cy21a76n2iq8xan"))

(define rust-ratatui-macros-0.7.2
  (crate-source "ratatui-macros" "0.7.2"
                "056q80hzmwamv511xfygzcw0rq97hh3ypq389lza963lma6wczgd"))

(define rust-ratatui-termina-0.1.0
  (crate-source "ratatui-termina" "0.1.0"
                "1hj35knwflynqim7sxdbaarqda0f51m3hbnrb6kmgw36kqnr3gy0"))

(define rust-ratatui-termwiz-0.1.2
  (crate-source "ratatui-termwiz" "0.1.2"
                "0xz95i63n7nafpsk6jbm56h65w5dwd7j4x6bsra40x5ph01kxw7s"))

(define rust-ratatui-widgets-0.3.2
  (crate-source "ratatui-widgets" "0.3.2"
                "1l87cjanipc1bzwza1mywfn23wbzfrh3pnbpc8vwlc4irjdx3qv6"))

(define rust-redox-syscall-0.5.18
  (crate-source "redox_syscall" "0.5.18"
                "0b9n38zsxylql36vybw18if68yc9jczxmbyzdwyhb9sifmag4azd"))

(define rust-regex-1.12.4
  (crate-source "regex" "1.12.4"
                "1fm6si2xpmhwqflabdqsakc0qkq718wx2ljl37nbj75fb5vjnagi"))

(define rust-regex-automata-0.4.14
  (crate-source "regex-automata" "0.4.14"
                "13xf7hhn4qmgfh784llcp2kzrvljd13lb2b1ca0mwnf15w9d87bf"))

(define rust-regex-syntax-0.8.11
  (crate-source "regex-syntax" "0.8.11"
                "1m25h5q2wp976fb9gc3dsc9l99svcvd5cri8lncb51c46ydgzxnn"))

(define rust-rustc-version-0.4.1
  (crate-source "rustc_version" "0.4.1"
                "14lvdsmr5si5qbqzrajgb6vfn69k0sfygrvfvr2mps26xwi3mjyg"))

(define rust-rustix-1.1.4
  (crate-source "rustix" "1.1.4"
                "14511f9yjqh0ix07xjrjpllah3325774gfwi9zpq72sip5jlbzmn"))

(define rust-rustversion-1.0.23
  (crate-source "rustversion" "1.0.23"
                "07z2a843fs80fawwflj9jwn49k9b0bd0dhhbvy0ar69vaxd72m6g"))

(define rust-ryu-1.0.23
  (crate-source "ryu" "1.0.23"
                "0zs70sg00l2fb9jwrf6cbkdyscjs53anrvai2hf7npyyfi5blx4p"))

(define rust-scopeguard-1.2.0
  (crate-source "scopeguard" "1.2.0"
                "0jcz9sd47zlsgcnm1hdw0664krxwb5gczlif4qngj2aif8vky54l"))

(define rust-semver-1.0.28
  (crate-source "semver" "1.0.28"
                "1kaimrpy876bcgi8bfj0qqfxk77zm9iz2zhn1hp9hj685z854y4a"))

(define rust-serde-1.0.228
  (crate-source "serde" "1.0.228"
                "17mf4hhjxv5m90g42wmlbc61hdhlm6j9hwfkpcnd72rpgzm993ls"))

(define rust-serde-core-1.0.228
  (crate-source "serde_core" "1.0.228"
                "1bb7id2xwx8izq50098s5j2sqrrvk31jbbrjqygyan6ask3qbls1"))

(define rust-serde-derive-1.0.228
  (crate-source "serde_derive" "1.0.228"
                "0y8xm7fvmr2kjcd029g9fijpndh8csv5m20g4bd76w8qschg4h6m"))

(define rust-serde-json-1.0.150
  (crate-source "serde_json" "1.0.150"
                "1ffgfhy9kndjnrz8lmy95pr758p2zk8dxv6yi99x0vkkni24w0g8"))

(define rust-serde-spanned-0.6.9
  (crate-source "serde_spanned" "0.6.9"
                "18vmxq6qfrm110caszxrzibjhy2s54n1g5w1bshxq9kjmz7y0hdz"))

(define rust-sha2-0.10.9
  (crate-source "sha2" "0.10.9"
                "10xjj843v31ghsksd9sl9y12qfc48157j1xpb8v1ml39jy0psl57"))

(define rust-signal-hook-0.3.18
  (crate-source "signal-hook" "0.3.18"
                "1qnnbq4g2vixfmlv28i1whkr0hikrf1bsc4xjy2aasj2yina30fq"))

(define rust-signal-hook-mio-0.2.5
  (crate-source "signal-hook-mio" "0.2.5"
                "1k20rr76ngvmzr6kskkl7dv8iyb84cbydpjbjk3mpcj0lykijnmp"))

(define rust-signal-hook-registry-1.4.8
  (crate-source "signal-hook-registry" "1.4.8"
                "06vc7pmnki6lmxar3z31gkyg9cw7py5x9g7px70gy2hil75nkny4"))

(define rust-siphasher-1.0.3
  (crate-source "siphasher" "1.0.3"
                "0jg6l9xyzca5vy4h6gf8r6p4kk84g98fk95pzig1kq6cr4z8grcf"))

(define rust-slab-0.4.12
  (crate-source "slab" "0.4.12"
                "1xcwik6s6zbd3lf51kkrcicdq2j4c1fw0yjdai2apy9467i0sy8c"))

(define rust-smallvec-1.15.2
  (crate-source "smallvec" "1.15.2"
                "143wzbqf6vgapdp2z4qpl0yvlqcn17s8cnk8m28rqly808zsdmlf"))

(define rust-static-assertions-1.1.0
  (crate-source "static_assertions" "1.1.0"
                "0gsl6xmw10gvn3zs1rv99laj5ig7ylffnh71f9l34js4nr4r7sx2"))

(define rust-strsim-0.11.1
  (crate-source "strsim" "0.11.1"
                "0kzvqlw8hxqb7y598w1s0hxlnmi84sg5vsipp3yg5na5d1rvba3x"))

(define rust-strum-0.28.0
  (crate-source "strum" "0.28.0"
                "1ggr0if083c1mz9w33hkdjsp0iqk2fz9n49bvb73knwihydxwa4n"))

(define rust-strum-macros-0.28.0
  (crate-source "strum_macros" "0.28.0"
                "0r7n6v5b3x85m52isyc8wq78irmr22g0hmj1xn3pbq8f4yhfx1db"))

(define rust-syn-1.0.109
  (crate-source "syn" "1.0.109"
                "0ds2if4600bd59wsv7jjgfkayfzy3hnazs394kz6zdkmna8l3dkj"))

(define rust-syn-2.0.118
  (crate-source "syn" "2.0.118"
                "08hlbc32lqd5d67p26ck7chg0rkclsw9as6f96vfn4s2j1zyb6hv"))

(define rust-termina-0.3.3
  (crate-source "termina" "0.3.3"
                "0km0c8zdpprqin2i1r9vr9nv362i519pzbz0vv6sad7yxy4shj4h"))

(define rust-terminfo-0.9.0
  (crate-source "terminfo" "0.9.0"
                "0qp6rrzkxcg08vjzsim2bw7mid3vi29mizrg70dzbycj0q7q3snl"))

(define rust-termios-0.3.3
  (crate-source "termios" "0.3.3"
                "0sxcs0g00538jqh5xbdqakkzijadr8nj7zmip0c7jz3k83vmn721"))

(define rust-termwiz-0.23.3
  (crate-source "termwiz" "0.23.3"
                "1xzq6l7rx285ax57dz8gdh44kp1790x0knvfynmimgfc89rb6xj6"))

(define rust-thiserror-1.0.69
  (crate-source "thiserror" "1.0.69"
                "0lizjay08agcr5hs9yfzzj6axs53a2rgx070a1dsi3jpkcrzbamn"))

(define rust-thiserror-2.0.18
  (crate-source "thiserror" "2.0.18"
                "1i7vcmw9900bvsmay7mww04ahahab7wmr8s925xc083rpjybb222"))

(define rust-thiserror-impl-1.0.69
  (crate-source "thiserror-impl" "1.0.69"
                "1h84fmn2nai41cxbhk6pqf46bxqq1b344v8yz089w1chzi76rvjg"))

(define rust-thiserror-impl-2.0.18
  (crate-source "thiserror-impl" "2.0.18"
                "1mf1vrbbimj1g6dvhdgzjmn6q09yflz2b92zs1j9n3k7cxzyxi7b"))

(define rust-time-0.3.53
  (crate-source "time" "0.3.53"
                "0l4aans0kv47y53736cjs0pnvdz91iyywrkqbrxk6cmrvknsmpqq"))

(define rust-time-core-0.1.9
  (crate-source "time-core" "0.1.9"
                "028ix0ax7ixp1h1k5zsqwgw85w6y1q32irslma7ci6ddd5kr074y"))

(define rust-toml-0.8.23
  (crate-source "toml" "0.8.23"
                "0qnkrq4lm2sdhp3l6cb6f26i8zbnhqb7mhbmksd550wxdfcyn6yw"))

(define rust-toml-datetime-0.6.11
  (crate-source "toml_datetime" "0.6.11"
                "077ix2hb1dcya49hmi1avalwbixmrs75zgzb3b2i7g2gizwdmk92"))

(define rust-toml-edit-0.22.27
  (crate-source "toml_edit" "0.22.27"
                "16l15xm40404asih8vyjvnka9g0xs9i4hfb6ry3ph9g419k8rzj1"))

(define rust-toml-write-0.1.2
  (crate-source "toml_write" "0.1.2"
                "008qlhqlqvljp1gpp9rn5cqs74gwvdgbvs92wnpq8y3jlz4zi6ax"))

(define rust-typenum-1.20.1
  (crate-source "typenum" "1.20.1"
                "086s9ly0906kw5yw41249fba97w5zfxf03pyfwdkffvcprqfixdn"))

(define rust-ucd-trie-0.1.7
  (crate-source "ucd-trie" "0.1.7"
                "0wc9p07sqwz320848i52nvyjvpsxkx3kv5bfbmm6s35809fdk5i8"))

(define rust-unicode-ident-1.0.24
  (crate-source "unicode-ident" "1.0.24"
                "0xfs8y1g7syl2iykji8zk5hgfi5jw819f5zsrbaxmlzwsly33r76"))

(define rust-unicode-segmentation-1.13.3
  (crate-source "unicode-segmentation" "1.13.3"
                "1a47zaq83p386r3baq4m018xd5q4q0grdg56i1x042dzn71x7xf6"))

(define rust-unicode-truncate-2.0.1
  (crate-source "unicode-truncate" "2.0.1"
                "19g9af5v0a8xaigqbs9hi9csxkg1fff07ycilvwfaqw64fhq1cqn"))

(define rust-unicode-width-0.2.2
  (crate-source "unicode-width" "0.2.2"
                "0m7jjzlcccw716dy9423xxh0clys8pfpllc5smvfxrzdf66h9b5l"))

(define rust-utf8parse-0.2.2
  (crate-source "utf8parse" "0.2.2"
                "088807qwjq46azicqwbhlmzwrbkz7l4hpw43sdkdyyk524vdxaq6"))

(define rust-uuid-1.23.4
  (crate-source "uuid" "1.23.4"
                "0lws65rrqncssdz1rk8g8ww7xg6k4d3l6avzkslzwni78llag05z"))

(define rust-version-check-0.9.5
  (crate-source "version_check" "0.9.5"
                "0nhhi4i5x89gm911azqbn7avs9mdacw2i3vcz3cnmz3mv4rqz4hb"))

(define rust-vtparse-0.6.2
  (crate-source "vtparse" "0.6.2"
                "1l5yz9650zhkaffxn28cvfys7plcw2wd6drajyf41pshn37jm6vd"))

(define rust-wasi-0.11.1+wasi-snapshot-preview1
  (crate-source "wasi" "0.11.1+wasi-snapshot-preview1"
                "0jx49r7nbkbhyfrfyhz0bm4817yrnxgd3jiwwwfv0zl439jyrwyc"))

(define rust-wasip2-1.0.4+wasi-0.2.12
  (crate-source "wasip2" "1.0.4+wasi-0.2.12"
                "11wl7lqwq4pbmlmzr6n7bwz0hzy1z6sxc4554bkmrr86w4vznzmn"))

(define rust-wasm-bindgen-0.2.126
  (crate-source "wasm-bindgen" "0.2.126"
                "197rma4qg1kb8l4bl7857pgszzval8s1w740g9myyjh92467q1jb"))

(define rust-wasm-bindgen-macro-0.2.126
  (crate-source "wasm-bindgen-macro" "0.2.126"
                "1cda6wl5zyiy7777cfgrix7fhpaqba55l5zpqj4zig7ng7jyaz0n"))

(define rust-wasm-bindgen-macro-support-0.2.126
  (crate-source "wasm-bindgen-macro-support" "0.2.126"
                "03iq412frl2py55skwb3ya08xha0cf6q22zr5kqlwbr675w7r6gk"))

(define rust-wasm-bindgen-shared-0.2.126
  (crate-source "wasm-bindgen-shared" "0.2.126"
                "097a3kbjls447s1lwr41l21x5crrh5vq3h6zsxccz7slrjq4q6yw"))

(define rust-wezterm-bidi-0.2.3
  (crate-source "wezterm-bidi" "0.2.3"
                "1v7kwmnxfplv9kgdmamn6csbn2ag5xjr0y6gs797slk0alsnw2hc"))

(define rust-wezterm-blob-leases-0.1.1
  (crate-source "wezterm-blob-leases" "0.1.1"
                "1dwf8bm3cwdi37fandwbk7nsfhn9spv4wm0l86gf551xv7vaybb9"))

(define rust-wezterm-color-types-0.3.0
  (crate-source "wezterm-color-types" "0.3.0"
                "15j29f60p1dc0msx50x940niyv9d5zpynavpcc6jf44hbkrixs3x"))

(define rust-wezterm-dynamic-0.2.1
  (crate-source "wezterm-dynamic" "0.2.1"
                "1b6mrk09xxiz66dj3912kmiq8rl7dqig6rwminkfmmhg287bcajz"))

(define rust-wezterm-dynamic-derive-0.1.1
  (crate-source "wezterm-dynamic-derive" "0.1.1"
                "0nspip7gwzmfn66fbnbpa2yik2sb97nckzmgir25nr4wacnwzh26"))

(define rust-wezterm-input-types-0.1.0
  (crate-source "wezterm-input-types" "0.1.0"
                "0zp557014d458a69yqn9dxfy270b6kyfdiynr5p4algrb7aas4kh"))

(define rust-winapi-0.3.9
  (crate-source "winapi" "0.3.9"
                "06gl025x418lchw1wxj64ycr7gha83m44cjr5sarhynd9xkrm0sw"))

(define rust-winapi-i686-pc-windows-gnu-0.4.0
  (crate-source "winapi-i686-pc-windows-gnu" "0.4.0"
                "1dmpa6mvcvzz16zg6d5vrfy4bxgg541wxrcip7cnshi06v38ffxc"))

(define rust-winapi-x86-64-pc-windows-gnu-0.4.0
  (crate-source "winapi-x86_64-pc-windows-gnu" "0.4.0"
                "0gqq64czqb64kskjryj8isp62m2sgvx25yyj3kpc2myh85w24bki"))

(define rust-windows-link-0.2.1
  (crate-source "windows-link" "0.2.1"
                "1rag186yfr3xx7piv5rg8b6im2dwcf8zldiflvb22xbzwli5507h"))

(define rust-windows-sys-0.61.2
  ;; TODO REVIEW: Check bundled sources.
  (crate-source "windows-sys" "0.61.2"
                "1z7k3y9b6b5h52kid57lvmvm05362zv1v8w0gc7xyv5xphlp44xf"))

(define rust-winnow-0.7.15
  (crate-source "winnow" "0.7.15"
                "0i9rkl2rqpbnnxlgs20gmkj3nd0b2k8q55mjmpc2ybb84xwxjyfz"))

(define rust-wit-bindgen-0.57.1
  (crate-source "wit-bindgen" "0.57.1"
                "0vjk2jb593ri9k1aq4iqs2si9mrw5q46wxnn78im7hm7hx799gqy"))

(define rust-yansi-1.0.1
  (crate-source "yansi" "1.0.1"
                "0jdh55jyv0dpd38ij4qh60zglbw9aa8wafqai6m0wa7xaxk3mrfg"))

(define rust-zmij-1.0.21
  (crate-source "zmij" "1.0.21"
                "1amb5i6gz7yjb0dnmz5y669674pqmwbj44p4yfxfv2ncgvk8x15q"))



(define-public herdr-tiny-fingers-crate-inputs
  (list
    rust-aho-corasick-1.1.4

    rust-allocator-api2-0.2.21

    rust-anyhow-1.0.103

    rust-approx-0.5.1

    rust-atomic-0.6.1

    rust-autocfg-1.5.1

    rust-base64-0.22.1

    rust-bit-set-0.5.3

    rust-bit-vec-0.6.3

    rust-bitflags-1.3.2

    rust-bitflags-2.13.0

    rust-block-buffer-0.10.4

    rust-bumpalo-3.20.3

    rust-by-address-1.2.1

    rust-bytemuck-1.25.0

    rust-castaway-0.2.4

    rust-cfg-if-1.0.4

    rust-cfg-aliases-0.2.1

    rust-compact-str-0.9.1

    rust-convert-case-0.10.0

    rust-cpufeatures-0.2.17

    rust-critical-section-1.2.0

    rust-crossterm-0.29.0

    rust-crossterm-winapi-0.9.1

    rust-crypto-common-0.1.7

    rust-csscolorparser-0.6.2

    rust-darling-0.23.0

    rust-darling-core-0.23.0

    rust-darling-macro-0.23.0

    rust-deltae-0.3.2

    rust-deranged-0.5.8

    rust-derive-more-2.1.1

    rust-derive-more-impl-2.1.1

    rust-diff-0.1.13

    rust-digest-0.10.7

    rust-document-features-0.2.12

    rust-either-1.16.0

    rust-equivalent-1.0.2

    rust-errno-0.3.14

    rust-euclid-0.22.14

    rust-fancy-regex-0.11.0

    rust-fast-srgb8-1.0.0

    rust-filedescriptor-0.8.3

    rust-finl-unicode-1.4.0

    rust-fixedbitset-0.4.2

    rust-fnv-1.0.7

    rust-foldhash-0.2.0

    rust-futures-core-0.3.32

    rust-futures-task-0.3.32

    rust-futures-util-0.3.32

    rust-generic-array-0.14.7

    rust-getrandom-0.3.4

    rust-getrandom-0.4.3

    rust-hashbrown-0.16.1

    rust-hashbrown-0.17.1

    rust-heck-0.5.0

    rust-hex-0.4.3

    rust-ident-case-1.0.1

    rust-indexmap-2.14.0

    rust-indoc-2.0.7

    rust-instability-0.3.12

    rust-itertools-0.14.0

    rust-itoa-1.0.18

    rust-js-sys-0.3.103

    rust-kasuari-0.4.12

    rust-lab-0.11.0

    rust-lazy-static-1.5.0

    rust-libc-0.2.186

    rust-libm-0.2.16

    rust-line-clipping-0.3.7

    rust-linux-raw-sys-0.12.1

    rust-litrs-1.0.0

    rust-lock-api-0.4.14

    rust-log-0.4.33

    rust-lru-0.18.0

    rust-mac-address-1.1.8

    rust-memchr-2.8.3

    rust-memmem-0.1.1

    rust-memoffset-0.9.1

    rust-minimal-lexical-0.2.1

    rust-mio-1.2.1

    rust-nix-0.29.0

    rust-nom-7.1.3

    rust-num-conv-0.2.2

    rust-num-derive-0.4.2

    rust-num-traits-0.2.19

    rust-num-threads-0.1.7

    rust-once-cell-1.21.4

    rust-ordered-float-4.6.0

    rust-palette-0.7.6

    rust-palette-derive-0.7.6

    rust-parking-lot-0.12.5

    rust-parking-lot-core-0.9.12

    rust-pest-2.8.7

    rust-pest-derive-2.8.7

    rust-pest-generator-2.8.7

    rust-pest-meta-2.8.7

    rust-phf-0.11.3

    rust-phf-codegen-0.11.3

    rust-phf-generator-0.11.3

    rust-phf-macros-0.11.3

    rust-phf-shared-0.11.3

    rust-pin-project-lite-0.2.17

    rust-portable-atomic-1.13.1

    rust-powerfmt-0.2.0

    rust-pretty-assertions-1.4.1

    rust-proc-macro2-1.0.106

    rust-quote-1.0.46

    rust-r-efi-5.3.0

    rust-r-efi-6.0.0

    rust-rand-0.8.6

    rust-rand-core-0.6.4

    rust-ratatui-0.30.2

    rust-ratatui-core-0.1.2

    rust-ratatui-crossterm-0.1.2

    rust-ratatui-macros-0.7.2

    rust-ratatui-termina-0.1.0

    rust-ratatui-termwiz-0.1.2

    rust-ratatui-widgets-0.3.2

    rust-redox-syscall-0.5.18

    rust-regex-1.12.4

    rust-regex-automata-0.4.14

    rust-regex-syntax-0.8.11

    rust-rustc-version-0.4.1

    rust-rustix-1.1.4

    rust-rustversion-1.0.23

    rust-ryu-1.0.23

    rust-scopeguard-1.2.0

    rust-semver-1.0.28

    rust-serde-1.0.228

    rust-serde-core-1.0.228

    rust-serde-derive-1.0.228

    rust-serde-json-1.0.150

    rust-serde-spanned-0.6.9

    rust-sha2-0.10.9

    rust-signal-hook-0.3.18

    rust-signal-hook-mio-0.2.5

    rust-signal-hook-registry-1.4.8

    rust-siphasher-1.0.3

    rust-slab-0.4.12

    rust-smallvec-1.15.2

    rust-static-assertions-1.1.0

    rust-strsim-0.11.1

    rust-strum-0.28.0

    rust-strum-macros-0.28.0

    rust-syn-1.0.109

    rust-syn-2.0.118

    rust-termina-0.3.3

    rust-terminfo-0.9.0

    rust-termios-0.3.3

    rust-termwiz-0.23.3

    rust-thiserror-1.0.69

    rust-thiserror-2.0.18

    rust-thiserror-impl-1.0.69

    rust-thiserror-impl-2.0.18

    rust-time-0.3.53

    rust-time-core-0.1.9

    rust-toml-0.8.23

    rust-toml-datetime-0.6.11

    rust-toml-edit-0.22.27

    rust-toml-write-0.1.2

    rust-typenum-1.20.1

    rust-ucd-trie-0.1.7

    rust-unicode-ident-1.0.24

    rust-unicode-segmentation-1.13.3

    rust-unicode-truncate-2.0.1

    rust-unicode-width-0.2.2

    rust-utf8parse-0.2.2

    rust-uuid-1.23.4

    rust-version-check-0.9.5

    rust-vtparse-0.6.2

    rust-wasi-0.11.1+wasi-snapshot-preview1

    rust-wasip2-1.0.4+wasi-0.2.12

    rust-wasm-bindgen-0.2.126

    rust-wasm-bindgen-macro-0.2.126

    rust-wasm-bindgen-macro-support-0.2.126

    rust-wasm-bindgen-shared-0.2.126

    rust-wezterm-bidi-0.2.3

    rust-wezterm-blob-leases-0.1.1

    rust-wezterm-color-types-0.3.0

    rust-wezterm-dynamic-0.2.1

    rust-wezterm-dynamic-derive-0.1.1

    rust-wezterm-input-types-0.1.0

    rust-winapi-0.3.9

    rust-winapi-i686-pc-windows-gnu-0.4.0

    rust-winapi-x86-64-pc-windows-gnu-0.4.0

    rust-windows-link-0.2.1

    rust-windows-sys-0.61.2

    rust-winnow-0.7.15

    rust-wit-bindgen-0.57.1

    rust-yansi-1.0.1

    rust-zmij-1.0.21

    ))
