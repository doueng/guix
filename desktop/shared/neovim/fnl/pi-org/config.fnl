;; pi-org.nvim configuration: defaults + validation.

(local M {})

;; ----- defaults -----------------------------------------------------------

(local default-window {:position :botright
                       :split_ratio 0.6
                       :input_ratio 0.25
                       :float {:width "80%"
                               :height "80%"
                               :row :center
                               :col :center
                               :relative :editor
                               :border :rounded}
                       :hide_numbers true
                       :hide_signcolumn true})

(local default-keymaps {:submit :<C-n>
                        :abort :<C-c>
                        :toggle :<leader>po
                        :new_session :<leader>pn
                        :cycle_model :<leader>pm})

(local default-org {:show_thinking true
                    :show_tool_args true
                    :show_tool_results true
                    :wrap_width nil})

(local default-config {:command :pi
                       :args []
                       :cwd nil
                       :env nil
                       :window default-window
                       :keymaps default-keymaps
                       :org default-org
                       :auto_compaction true
                       :thinking_level nil})

;; ----- parse / validate ---------------------------------------------------

(fn notify-error [msg]
  (vim.notify (.. "pi-org: " msg) vim.log.levels.ERROR))

(fn parse [user ?silent]
  (let [config (vim.tbl_deep_extend :force (vim.deepcopy default-config)
                                    (or user {}))
        bad (fn [msg]
              (when (not ?silent) (notify-error msg))
              (vim.deepcopy default-config))]
    (if (or (not= (type config.command) :string) (= config.command ""))
        (bad "config.command must be a non-empty string")
        (not= (type config.args) :table)
        (bad "config.args must be a table")
        (or (not= (type config.window.split_ratio) :number)
            (<= config.window.split_ratio 0) (> config.window.split_ratio 1))
        (bad "window.split_ratio must be in (0,1]")
        (or (not= (type config.window.input_ratio) :number)
            (<= config.window.input_ratio 0) (> config.window.input_ratio 1))
        (bad "window.input_ratio must be in (0,1]")
        config)))

(tset M :defaults default-config)
(tset M :parse parse)
M
