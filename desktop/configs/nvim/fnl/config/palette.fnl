;; SPC SPC chooses in the desktop's Dusk palette. Mappings with a description
;; run as if typed, so their own logging and modes apply. Ex commands open the
;; command line with the name filled in, so arguments can follow. The global
;; palette calls open over this Neovim's socket.

(local uv (or vim.uv vim.loop))
(local history-path (.. (vim.fn.stdpath :state) :/palette-history.json))

(fn socket-path []
  (.. (or vim.env.XDG_RUNTIME_DIR :/tmp) :/dusk- (or vim.env.WAYLAND_DISPLAY :wayland-0) :.sock))

(fn read-history []
  (let [(ok lines) (pcall vim.fn.readfile history-path)
        (ok2 decoded) (if (and ok (. lines 1)) (pcall vim.json.decode (. lines 1)) (values false nil))]
    (if (and ok2 (= (type decoded) :table)) decoded [])))

(fn remember [value]
  (let [kept [value]]
    (each [_ v (ipairs (read-history))]
      (when (and (not= v value) (< (length kept) 100)) (table.insert kept v)))
    (vim.fn.mkdir (vim.fn.fnamemodify history-path ":h") :p)
    (vim.fn.writefile [(vim.json.encode kept)] history-path)))

(fn leader-keys [lhs]
  "Write a leader mapping such as ' ff' as 'SPC f f'."
  (if (= (lhs:sub 1 1) " ")
      (let [keys [:SPC]]
        (var rest (lhs:sub 2))
        (while (> (length rest) 0)
          (let [token (or (rest:match "^<[^>]+>") (rest:sub 1 1))]
            (table.insert keys token)
            (set rest (rest:sub (+ 1 (length token))))))
        (table.concat keys " "))
      lhs))

(fn entries []
  "Described normal-mode mappings and every Ex command, recent choices first."
  (let [found []
        seen {}
        add (fn [e] (when (not (. seen e.value))
                      (tset seen e.value true)
                      (table.insert found e)))]
    (each [_ maps (ipairs [(vim.api.nvim_buf_get_keymap 0 :n) (vim.api.nvim_get_keymap :n)])]
      (each [_ m (ipairs maps)]
        (when (and m.desc (not= m.desc "") (not (m.lhs:match "<Plug>")))
          (add {:label m.desc :keys (leader-keys m.lhs) :source :Neovim :value (.. "map:" m.lhs)}))))
    (each [_ name (ipairs (vim.fn.getcompletion "" :command))]
      (add {:label (.. ":" name) :source :Neovim :value (.. "cmd:" name)}))
    (let [by-value {}]
      (each [_ e (ipairs found)] (tset by-value e.value e))
      (let [ordered []]
        (each [_ v (ipairs (read-history))]
          (when (. by-value v)
            (table.insert ordered (. by-value v))
            (tset by-value v nil)))
        (each [_ e (ipairs found)]
          (when (. by-value e.value) (table.insert ordered e)))
        ordered))))

(fn edn-string [s]
  (.. "\"" (pick-values 1 (: (pick-values 1 (s:gsub "[\\\"]" "\\%0")) :gsub "\n" "\\n")) "\""))

(fn request [es]
  (let [parts []]
    (each [_ e (ipairs es)]
      (table.insert parts (.. "{:label " (edn-string e.label)
                              (if e.keys (.. " :keys " (edn-string e.keys)) "")
                              " :source \"Neovim\" :value " (edn-string e.value) "}")))
    (.. "{:op :request :name :palette :args {:prompt \"Neovim\" :entries ["
        (table.concat parts " ") "]}}\n")))

(fn choice [reply]
  "The chosen value in Dusk's REPLY, or nil when cancelled."
  (let [start (reply:find ":value \"" 1 true)]
    (when start
      (var i (+ start 8))
      (local out [])
      (var done false)
      (while (and (not done) (<= i (length reply)))
        (let [c (reply:sub i i)]
          (if (= c "\\")
              (let [n (reply:sub (+ i 1) (+ i 1))]
                (table.insert out (if (= n :n) "\n" (= n :t) "\t" n))
                (set i (+ i 2)))
              (= c "\"") (set done true)
              (do (table.insert out c) (set i (+ i 1))))))
      (table.concat out))))

(fn run [win value]
  (when (vim.api.nvim_win_is_valid win) (vim.api.nvim_set_current_win win))
  (remember value)
  (let [(kind arg) (value:match "^(%a+):(.*)$")]
    (if (= kind :map)
        (vim.api.nvim_feedkeys (vim.keycode arg) :m false)
        (= kind :cmd)
        (vim.api.nvim_feedkeys (.. ":" arg " ") :n false))))

(fn open []
  "Show the palette. Returns false when Dusk is not running."
  (let [path (socket-path)]
    (if (not (uv.fs_stat path))
        false
        (let [win (vim.api.nvim_get_current_win)
              pipe (uv.new_pipe false)
              chunks []
              payload (request (entries))]
          (pipe:connect path
                        (fn [err]
                          (if err
                              (do (pipe:close)
                                  (vim.schedule #(vim.notify (.. "Dusk palette: " err) vim.log.levels.WARN)))
                              (do (pipe:write payload)
                                  (pipe:read_start
                                   (fn [_ data]
                                     (if data
                                         (table.insert chunks data)
                                         (do (pipe:close)
                                             (let [value (choice (table.concat chunks))]
                                               (when value (vim.schedule #(run win value))))))))))))
          true))))

{: open : entries : choice : request : leader-keys : run}
