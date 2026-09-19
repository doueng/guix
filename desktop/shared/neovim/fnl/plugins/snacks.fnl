(var last-file-path nil)

(local lazy-spec (require :lib.lazy))
(local plugin lazy-spec.plugin)
(local key lazy-spec.key)

(fn current-file-path []
  (let [buf (vim.api.nvim_get_current_buf)
        path (vim.api.nvim_buf_get_name buf)
        buftype (. vim.bo buf :buftype)]
    (when (and (not= path "") (= buftype ""))
      (set last-file-path (vim.fn.fnamemodify path ":p")))
    last-file-path))

(fn copy-current-file-path []
  (let [path (current-file-path)]
    (if (or (not path) (= path ""))
        (vim.notify "Current buffer has no file path" vim.log.levels.WARN
                    {:title "Copy File Path"})
        (do
          (vim.fn.setreg "+" path)
          (vim.fn.setreg "*" path)
          (vim.notify path vim.log.levels.INFO {:title "Copied file path"})))))

(fn command-palette-confirm [picker item action]
  (if (not item) (picker:close)
      item.action (do
                    (picker:close)
                    (item.action))
      (and item.item item.item.lhs) (picker:norm (fn []
                                                   (picker:close)
                                                   (vim.api.nvim_input item.item.lhs)))
      item.cmd ((. (require :snacks.picker.actions) :cmd) picker item action)
      ((. (require :snacks.picker.actions) :jump) picker item action)))

(fn command-palette-actions-source []
  {:finder (fn []
             [{:text "Copy current file full path"
               :desc "Copy absolute path to clipboard"
               :action copy-current-file-path}])
   :format (fn [item]
             [[item.text :SnacksPickerCmd] [" "] [item.desc :SnacksPickerDesc]])
   :confirm (fn [picker item]
              (picker:close)
              (when (and item item.action)
                (item.action)))})

(fn valid-picker-window? [entry]
  (and entry.win (vim.api.nvim_win_is_valid entry.win)))

(fn focus-picker-window [picker direction]
  (var wins [{:name :input
              :win (and picker.input picker.input.win picker.input.win.win)}
             {:name :list
              :win (and picker.list picker.list.win picker.list.win.win)}
             {:name :preview
              :win (and picker.preview picker.preview.win
                        picker.preview.win.win)}])
  (set wins (vim.tbl_filter valid-picker-window? wins))
  (when (> (length wins) 0)
    (let [current (vim.api.nvim_get_current_win)]
      (var current-index 1)
      (each [index entry (ipairs wins)]
        (when (= entry.win current)
          (set current-index index)))
      (let [next-index (+ (% (+ (- current-index 1) direction) (length wins)) 1)]
        (picker:focus (. wins next-index :name) {:show true})))))

(local notification-window-keys
       {:<c-h> (plugin :notification_focus_prev
                       {:mode [:n :i] :desc "Previous Notification Window"})
        :<c-k> (plugin :notification_focus_prev
                       {:mode [:n :i] :desc "Previous Notification Window"})
        :<c-l> (plugin :notification_focus_next
                       {:mode [:n :i] :desc "Next Notification Window"})
        :<c-j> (plugin :notification_focus_next
                       {:mode [:n :i] :desc "Next Notification Window"})})

(fn apply-snacks-highlights []
  ;; Snacks picker/explorer uses NormalFloat by default, which is light grey
  ;; with doom-one. Use the regular editor background instead.
  (vim.api.nvim_set_hl 0 :SnacksPicker {:link :Normal})
  (vim.api.nvim_set_hl 0 :SnacksPickerList {:link :Normal})
  (vim.api.nvim_set_hl 0 :SnacksPickerInput {:link :Normal})
  (vim.api.nvim_set_hl 0 :SnacksPickerPreview {:link :Normal})
  (vim.api.nvim_set_hl 0 :SnacksPickerBox {:link :Normal})
  (vim.api.nvim_set_hl 0 :SnacksPickerBorder {:link :WinSeparator})
  (vim.api.nvim_set_hl 0 :SnacksPickerListBorder {:link :WinSeparator})
  (vim.api.nvim_set_hl 0 :SnacksPickerInputBorder {:link :WinSeparator})
  (vim.api.nvim_set_hl 0 :SnacksPickerPreviewBorder {:link :WinSeparator})
  (vim.api.nvim_set_hl 0 :SnacksPickerBoxBorder {:link :WinSeparator})
  (vim.api.nvim_set_hl 0 :SnacksPickerListCursorLine {:link :CursorLine}))

(plugin :folke/snacks.nvim
        {:priority 1000
         :lazy false
         :init (fn []
                 (vim.api.nvim_create_autocmd [:BufEnter :BufWinEnter]
                                              {:callback current-file-path})
                 (vim.api.nvim_create_user_command :CopyCurrentFilePath
                                                   copy-current-file-path
                                                   {:desc "Copy the absolute path of the current file"})
                 (vim.api.nvim_create_autocmd :ColorScheme
                                              {:callback apply-snacks-highlights})
                 (vim.schedule apply-snacks-highlights))
         :keys [(key :<leader><leader>
                     (fn []
                       (current-file-path)
                       (_G.Snacks.picker {:title "Command Palette"
                                          :multi [(command-palette-actions-source)
                                                  :commands
                                                  :keymaps]
                                          :confirm command-palette-confirm
                                          :layout {:preset :vscode}}))
                     {:desc "Command Palette"})
                [:<leader>/ false]
                [:<leader>ff false]
                [:<leader>fF false]
                [:<leader>fg false]
                [:<leader>sg false]
                [:<leader>sp false]
                [:<leader>sG false]
                (key :<leader>sw false {:mode [:n :x]})
                (key :<leader>sW false {:mode [:n :x]})]
         :opts {;; Prefer redraw smoothness over animated/decorated movement.
                :indent {:enabled false}
                :scope {:enabled false}
                :scroll {:enabled false}
                :words {:enabled false}
                :picker {:sources {:notifications {:layout {:preset :vertical
                                                            :layout {:width 0.9
                                                                     :height 0.85}}
                                                   :actions {:notification_focus_prev (fn [picker]
                                                                                        (focus-picker-window picker
                                                                                                             -1))
                                                             :notification_focus_next (fn [picker]
                                                                                        (focus-picker-window picker
                                                                                                             1))}
                                                   :win {:input {:keys notification-window-keys}
                                                         :list {:keys notification-window-keys}
                                                         :preview {:keys notification-window-keys}}}}}}})
