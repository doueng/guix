(local lazy-spec (require :lib.lazy))
(local plugin lazy-spec.plugin)
(local key lazy-spec.key)

(fn preferred-gh-cmd []
  (if (= (vim.fn.executable :/opt/uber/bin/gh) 1) :/opt/uber/bin/gh :gh))

(fn merged-path [extra]
  (let [entries (vim.list_extend (or extra [])
                                 (vim.split (or vim.env.PATH "") ":"
                                            {:plain true}))
        seen {}
        out []]
    (each [_ entry (ipairs entries)]
      (when (and (not= entry "") (= (vim.fn.isdirectory entry) 1)
                 (not (. seen entry)))
        (tset seen entry true)
        (table.insert out entry)))
    (table.concat out ":")))

(fn parse-remote-url [url aliases]
  (when (and (= (type url) :string) (not= url ""))
    (var normalized (url:gsub "%.git$" ""))
    (set normalized (normalized:gsub "^[^:]+://" ""))
    (set normalized (normalized:gsub "^[^@]+@" ""))
    (var (host repo) (normalized:match "^([^:]+):(.+)$"))
    (when (or (not host) (not repo))
      (let [segments (vim.split normalized "/" {:plain true})]
        (when (>= (length segments) 3)
          (set host (. segments 1))
          (set repo (.. (. segments 2) "/" (. segments 3))))))
    (when (and host repo (not= host "") (not= repo ""))
      (each [alias remote-host (pairs (or aliases {}))]
        (let [pattern (alias:gsub "%-" "%%-")]
          (set host (host:gsub (.. "^" pattern "$") remote-host 1))))
      {: host : repo})))

(fn git-remotes [aliases]
  (let [output (vim.fn.system [:git :remote :-v])]
    (if (not= vim.v.shell_error 0)
        {}
        (let [remotes {}]
          (each [line (output:gmatch "[^\n]+")]
            (let [(name url) (line:match "^(%S+)%s+(%S+)")]
              (when (and name (not (. remotes name)))
                (let [remote (parse-remote-url url aliases)]
                  (when remote
                    (tset remotes name remote))))))
          remotes))))

(fn current-remote-host []
  (let [remotes (git-remotes {"github%.uberinternal%.com" :github.uberinternal.com})]
    (var found "")
    (each [_ name (ipairs [:github :upstream :origin])]
      (when (and (= found "") (. remotes name) (. remotes name :host))
        (set found (. remotes name :host))))
    found))

(fn patch-octo-remote-parser []
  (let [(ok utils) (pcall require :octo.utils)]
    (when (and ok (not utils._eng_remote_parser_patched))
      (set utils.parse_remote_url parse-remote-url)
      (set utils._eng_remote_parser_patched true))))

(local review-queries
       {:needs_review "repo:uber-code/go-code is:pr is:open review-requested:@me draft:false"
        :follow_up "repo:uber-code/go-code is:pr is:open involves:@me -author:@me -review-requested:@me"
        :my_prs "repo:uber-code/go-code is:pr is:open author:@me draft:false"
        :drafts "repo:uber-code/go-code is:pr is:open author:@me draft:true"})

(fn octo-search [query]
  ((. (require :octo.utils) :create_base_search_command) {: query}))

(fn uber-gh-env []
  (let [env {:PATH (merged-path [:/usr/local/bin
                                 :/opt/uber/bin
                                 (vim.fn.expand "~/.nix-profile/bin")
                                 (vim.fn.expand "~/.aw/pex_resources/scripts")
                                 (vim.fn.expand "~/.aw/pex_resources/scripts/binaries")
                                 :/opt/homebrew/bin
                                 :/opt/homebrew/sbin
                                 :/usr/bin
                                 :/bin
                                 :/usr/sbin
                                 :/sbin])}]
    (if (or (not= (current-remote-host) :github.uberinternal.com)
            (not= (vim.fn.executable :/opt/uber/bin/gh) 1))
        env
        (let [token (vim.trim (vim.fn.system [:/opt/uber/bin/gh
                                              :auth
                                              :token
                                              :--hostname
                                              :github.uberinternal.com]))]
          (if (or (not= vim.v.shell_error 0) (= token ""))
              env
              (do
                (set env.GH_HOST :github.uberinternal.com)
                (set env.GH_USE_BEARER_TOKEN :1)
                (set env.GH_ENTERPRISE_TOKEN token)
                (set env.GH_TOKEN token)
                (set env.GITHUB_TOKEN token)
                env))))))

[(plugin :pwntester/octo.nvim
         {:enabled (fn []
                     (or (= (vim.fn.executable :/opt/uber/bin/gh) 1)
                         (= (vim.fn.executable :gh) 1)))
          :cmd :Octo
          :dependencies [:nvim-lua/plenary.nvim
                         :folke/snacks.nvim
                         :nvim-tree/nvim-web-devicons]
          :keys [(key :<leader>oo :<cmd>Octo<cr> {:desc "Octo actions"})
                 (key :<leader>op
                      (fn [] (octo-search review-queries.needs_review))
                      {:desc "PRs needing review"})
                 (key :<leader>or
                      (fn [] (octo-search review-queries.needs_review))
                      {:desc "Needs review"})
                 (key :<leader>of
                      (fn [] (octo-search review-queries.follow_up))
                      {:desc "Follow up"})
                 (key :<leader>om (fn [] (octo-search review-queries.my_prs))
                      {:desc "My PRs"})
                 (key :<leader>od (fn [] (octo-search review-queries.drafts))
                      {:desc "Draft PRs"})
                 (key :<leader>os
                      (fn [] (octo-search "repo:uber-code/go-code is:pr "))
                      {:desc "Search PRs"})]
          :opts {:picker :snacks
                 :enable_builtin true
                 :default_remote [:github :upstream :origin]
                 :gh_cmd (preferred-gh-cmd)
                 :gh_env uber-gh-env
                 :timeout 30000
                 :ssh_aliases {"github%.uberinternal%.com" :github.uberinternal.com}
                 :mappings {:pull_request {:checkout_pr {:lhs :<leader>ok
                                                         :desc "checkout PR"}
                                           :list_changed_files {:lhs :<leader>oc
                                                                :desc "list changed files"}
                                           :show_pr_diff {:lhs :<leader>od
                                                          :desc "show PR diff"}}
                            :review_diff {:focus_files {:lhs :<leader>oc
                                                        :desc "focus changed files"}
                                          :toggle_files {:lhs :<leader>ob
                                                         :desc "toggle changed files"}}
                            :file_panel {:focus_files {:lhs :<leader>oc
                                                       :desc "focus changed files"}
                                         :toggle_files {:lhs :<leader>ob
                                                        :desc "toggle changed files"}}}}
          :config (fn [_ opts]
                    (patch-octo-remote-parser)
                    ((. (require :octo) :setup) opts))})]
