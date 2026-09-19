(local lazy-spec (require :lib.lazy))
(local plugin lazy-spec.plugin)
(local key lazy-spec.key)

(var maven-terminal-win nil)
(var maven-terminal-buf nil)
(var maven-terminal-job nil)

(fn maven-project-root []
  (or (vim.fs.root 0 [:pom.xml])
      (vim.fn.getcwd)))

(fn current-test-class []
  (let [path (vim.api.nvim_buf_get_name 0)]
    (when (not= path "")
      (vim.fn.fnamemodify path ":t:r"))))

(fn test-method-under-cursor []
  (let [cursor (vim.api.nvim_win_get_cursor 0)
        node (vim.treesitter.get_node {:bufnr 0
                                       :pos [(- (. cursor 1) 1) (. cursor 2)]})]
    (var current node)
    (var method nil)
    (while (and current (not method))
      (if (= (current:type) "method_declaration")
          (let [name-node (. (current:field "name") 1)]
            (when name-node
              (set method (vim.treesitter.get_node_text name-node 0))))
          (set current (current:parent))))
    method))

(fn maven-terminal-is-valid []
  (and maven-terminal-win
       (vim.api.nvim_win_is_valid maven-terminal-win)
       maven-terminal-buf
       (vim.api.nvim_buf_is_valid maven-terminal-buf)
       (= (vim.api.nvim_win_get_buf maven-terminal-win)
          maven-terminal-buf)))

(fn prepare-maven-terminal []
  (if (maven-terminal-is-valid)
      (do
        (vim.api.nvim_set_current_win maven-terminal-win)
        (when (and maven-terminal-job (> maven-terminal-job 0))
          (vim.fn.jobstop maven-terminal-job))
        (let [old-buf maven-terminal-buf
              new-buf (vim.api.nvim_create_buf false true)]
          (vim.api.nvim_win_set_buf maven-terminal-win new-buf)
          (vim.api.nvim_buf_delete old-buf {:force true})
          (set maven-terminal-buf new-buf)))
      (let [height (math.floor (/ vim.o.lines 4))]
        (vim.cmd (.. "botright " height "new"))
        (set maven-terminal-win (vim.api.nvim_get_current_win))
        (set maven-terminal-buf (vim.api.nvim_get_current_buf)))))

(fn scroll-maven-terminal []
  (when (and (maven-terminal-is-valid)
             (vim.api.nvim_win_is_valid maven-terminal-win))
    (vim.schedule
      (fn []
        (when (maven-terminal-is-valid)
          (let [lines (vim.api.nvim_buf_line_count maven-terminal-buf)]
            (when (> lines 0)
              (vim.api.nvim_win_set_cursor maven-terminal-win [lines 0]))))))))

(fn run-maven [args]
  (let [source-win (vim.api.nvim_get_current_win)]
    (prepare-maven-terminal)
    (set maven-terminal-job
         (vim.fn.termopen
          (vim.list_extend
           ["mvn"
            "--batch-mode"
            "--no-transfer-progress"
            "-q"
            "-Dstyle.color=always"
            "-Dspring.output.ansi.enabled=ALWAYS"
            "-Dspring.main.banner-mode=off"
            "-Dlogging.level.root=WARN"]
           args)
          {:cwd (maven-project-root)
           :on_stdout (fn [_ _ _] (scroll-maven-terminal))
           :on_stderr (fn [_ _ _] (scroll-maven-terminal))}))
    (when (vim.api.nvim_win_is_valid source-win)
      (vim.api.nvim_set_current_win source-win))))

(fn run-test-under-cursor []
  (let [class (current-test-class)
        method (test-method-under-cursor)]
    (if (or (not class) (= class ""))
        (vim.notify "Current buffer is not a file" vim.log.levels.WARN)
        (if (not method)
            (vim.notify "No Java test method under cursor" vim.log.levels.WARN)
            (run-maven [(.. "-Dtest=" class "#" method) "test"])))))

(fn run-tests-in-file []
  (let [class (current-test-class)]
    (if (or (not class) (= class ""))
        (vim.notify "Current buffer is not a file" vim.log.levels.WARN)
        (run-maven [(.. "-Dtest=" class) "test"]))))

(fn run-all-project-tests []
  (run-maven ["test"]))

(fn java-setup []
  (let [java (require :java)
        jdk-home vim.env.JAVA_HOME]
    (java.setup
     {:jdtls {:path vim.env.NVIM_JDTLS_HOME
              :auto_install false}
      :lombok {:path vim.env.NVIM_JAVA_LOMBOK_JAR
               :auto_install false}
      ;; Nixpkgs provides these two VS Code extensions. Spring Boot Tools
      ;; remains nvim-java-managed because its extension is not in Nixpkgs.
      :java_test {:path vim.env.NVIM_JAVA_TEST_HOME
                  :auto_install false}
      :java_debug_adapter {:path vim.env.NVIM_JAVA_DEBUG_HOME
                           :auto_install false}
      :jdk {:path jdk-home
            :auto_install false}
      ;; Keep routine nvim-java/JDTLS info messages out of the UI and log file.
      :log {:use_console false
            :use_file false
            :level :error}})

    (vim.lsp.config
     :jdtls
     {:settings
      {:java
       {:configuration
        {:runtimes [{:name :JavaSE-25
                     :path jdk-home
                     :default true}]}
        ;; conform.nvim owns Java formatting through google-java-format.
        :format {:enabled false}}}})
    (vim.lsp.enable :jdtls)))

(plugin :nvim-java/nvim-java
        {:ft [:java]
         :dependencies [:MunifTanjim/nui.nvim
                        :mfussenegger/nvim-dap
                        (plugin :JavaHello/spring-boot.nvim
                                {:commit :218c0c26c14d99feca778e4d13f5ec3e8b1b60f0})]
         :keys [(key :<leader>tn run-test-under-cursor
                     {:desc "Run Maven test under cursor"})
                (key :<leader>tf run-tests-in-file
                     {:desc "Run Maven tests in file"})
                (key :<leader>ta run-all-project-tests
                     {:desc "Run all Maven tests"})]
         :config java-setup})
